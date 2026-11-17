package app.omnisonic.feature.library.browse

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.browse.BrowseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Load-more paging engine behind one section's [BrowseUiState]. Hand-rolled
 * LIMIT/OFFSET windows: the first page also fetches the total count, further
 * pages continue from an explicit cursor, and `hasMore` comes from the
 * repository's fetch-one-past-the-window probe.
 *
 * Overlap safety: a live sync inserts rows at arbitrary positions, so the
 * OFFSET window behind the loaded list can shift between two fetches and a
 * page can repeat rows the list already shows. Duplicate rows are fatal for
 * the Compose lazy bodies, which key every item by row id, so appends dedupe
 * the fetched page against the loaded window via [keyOf]. The cursor always
 * advances by the **raw** fetched count — a fully-duplicate page must still
 * move it forward, or `hasMore` would keep re-fetching the same offset with a
 * spinning footer — and a raw empty page ends paging defensively. Rows a
 * shift skipped stay absent until the next [changes] refresh re-reads the
 * window.
 *
 * Guards: `loadInitial` is idempotent while a page loads or content already
 * exists (only [BrowseUiState.Error] re-enters), and `loadMore` ignores calls
 * while any job is running or `hasMore` is false. A failed append keeps the
 * loaded items and raises [BrowseUiState.Content.appendFailed]; `loadMore`
 * retries the same cursor.
 *
 * [changes] drives live refresh (D-16): a catalog write re-fetches the window
 * the pager currently shows plus its count, so a sync fills the section in
 * front of the user. Pages that were never opened ignore changes, and a change
 * that lands mid-load is replayed once the in-flight job finishes. While the
 * user is scrolling ([setScrolling], D-27) a change is deferred instead and
 * replayed once when scrolling stops, so the sync never replaces the window
 * under the finger.
 */
internal class SectionPager<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int,
    private val loadPage: suspend (offset: Int, limit: Int) -> BrowsePage<T>,
    private val loadCount: suspend () -> Int,
    private val keyOf: (T) -> Long,
    changes: Flow<Unit> = emptyFlow(),
) {

    private val _state = MutableStateFlow<BrowseUiState<T>>(BrowseUiState.Loading)
    val state: StateFlow<BrowseUiState<T>> = _state.asStateFlow()

    private var job: Job? = null
    private var started = false
    private var refreshPending = false
    private var scrolling = false
    private var refreshDeferred = false
    private var nextOffset = 0

    init {
        scope.launch {
            changes.collect { refresh() }
        }
    }

    fun loadInitial() {
        if (job?.isActive == true) return
        if (started && _state.value !is BrowseUiState.Error) return
        started = true
        _state.value = BrowseUiState.Loading
        job = scope.launch {
            _state.value = runCatching {
                val page = loadPage(0, pageSize)
                val count = loadCount()
                page to count
            }.fold(
                onSuccess = { (page, count) ->
                    if (page.items.isEmpty()) {
                        BrowseUiState.Empty
                    } else {
                        nextOffset = page.items.size
                        BrowseUiState.Content(
                            items = page.items,
                            totalCount = count,
                            hasMore = page.hasMore,
                        )
                    }
                },
                onFailure = { BrowseUiState.Error },
            )
            replayPendingRefresh()
        }
    }

    fun loadMore() {
        @Suppress("UNCHECKED_CAST")
        val content = _state.value as? BrowseUiState.Content<T> ?: return
        if (!content.hasMore || content.appending || job?.isActive == true) return
        val requestOffset = nextOffset
        _state.value = content.copy(appending = true, appendFailed = false)
        job = scope.launch {
            _state.value = runCatching { loadPage(requestOffset, pageSize) }.fold(
                onSuccess = { page ->
                    val current = _state.value as? BrowseUiState.Content<T> ?: content
                    val known = current.items.mapTo(HashSet(current.items.size)) { keyOf(it) }
                    val fresh = page.items.filterNot { keyOf(it) in known }
                    nextOffset = requestOffset + page.items.size
                    current.copy(
                        items = current.items + fresh,
                        hasMore = page.hasMore && page.items.isNotEmpty(),
                        appending = false,
                        appendFailed = false,
                    )
                },
                onFailure = {
                    val current = _state.value as? BrowseUiState.Content<T> ?: content
                    current.copy(appending = false, appendFailed = true)
                },
            )
            replayPendingRefresh()
        }
    }

    /**
     * Scroll gate (D-27). While a section's list is scrolling, an invalidation
     * only marks the window stale; the refresh runs once when scrolling stops,
     * so the sync never re-reads (and clamps) the window under the user's
     * finger. A stale flag set before the section is left is replayed on the
     * next entry, which also reports scrolling as stopped.
     */
    fun setScrolling(scrolling: Boolean) {
        this.scrolling = scrolling
        if (!scrolling && refreshDeferred) {
            refreshDeferred = false
            refresh()
        }
    }

    private fun refresh() {
        if (!started) return
        if (scrolling) {
            refreshDeferred = true
            return
        }
        if (job?.isActive == true) {
            refreshPending = true
            return
        }
        launchRefresh()
    }

    /**
     * Replays an invalidation that arrived during an in-flight job. The replay
     * re-checks the scroll gate: the user may have started scrolling while the
     * job ran, in which case the refresh is deferred to scroll end instead of
     * bypassing Fix 2.
     */
    private fun replayPendingRefresh() {
        if (!refreshPending) return
        refreshPending = false
        if (scrolling) {
            refreshDeferred = true
        } else {
            launchRefresh()
        }
    }

    private fun launchRefresh() {
        val current = _state.value
        val window = when (current) {
            is BrowseUiState.Content -> current.items.size
                .coerceAtLeast(pageSize)
                .coerceAtMost(BrowseRepository.MAX_PAGE_LIMIT)
            BrowseUiState.Empty -> pageSize
            else -> return
        }
        job = scope.launch {
            val fresh = runCatching {
                val page = loadPage(0, window)
                val count = loadCount()
                page to count
            }.getOrNull()
            if (fresh != null) {
                val (page, count) = fresh
                if (page.items.isEmpty()) {
                    _state.value = BrowseUiState.Empty
                } else {
                    nextOffset = page.items.size
                    _state.value = BrowseUiState.Content(
                        items = page.items,
                        totalCount = count,
                        hasMore = page.hasMore,
                    )
                }
            }
            replayPendingRefresh()
        }
    }
}
