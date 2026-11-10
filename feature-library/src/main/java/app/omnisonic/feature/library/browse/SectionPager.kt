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
 * pages start at the currently accumulated size, and `hasMore` comes from the
 * repository's fetch-one-past-the-window probe.
 *
 * Guards: `loadInitial` is idempotent while a page loads or content already
 * exists (only [BrowseUiState.Error] re-enters), and `loadMore` ignores calls
 * while any job is running or `hasMore` is false. A failed append keeps the
 * loaded items and raises [BrowseUiState.Content.appendFailed]; `loadMore`
 * retries it.
 *
 * [changes] drives live refresh (D-16): a catalog write re-fetches the window
 * the pager currently shows plus its count, so a sync fills the section in
 * front of the user. Pages that were never opened ignore changes, and a change
 * that lands mid-load is replayed once the in-flight job finishes.
 */
internal class SectionPager<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int,
    private val loadPage: suspend (offset: Int, limit: Int) -> BrowsePage<T>,
    private val loadCount: suspend () -> Int,
    changes: Flow<Unit> = emptyFlow(),
) {

    private val _state = MutableStateFlow<BrowseUiState<T>>(BrowseUiState.Loading)
    val state: StateFlow<BrowseUiState<T>> = _state.asStateFlow()

    private var job: Job? = null
    private var started = false
    private var refreshPending = false

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
        val requestOffset = content.items.size
        _state.value = content.copy(appending = true, appendFailed = false)
        job = scope.launch {
            _state.value = runCatching { loadPage(requestOffset, pageSize) }.fold(
                onSuccess = { page ->
                    val current = _state.value as? BrowseUiState.Content<T> ?: content
                    current.copy(
                        items = current.items + page.items,
                        hasMore = page.hasMore,
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

    private fun refresh() {
        if (!started) return
        if (job?.isActive == true) {
            refreshPending = true
            return
        }
        launchRefresh()
    }

    private fun replayPendingRefresh() {
        if (!refreshPending) return
        refreshPending = false
        launchRefresh()
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
                _state.value = if (page.items.isEmpty()) {
                    BrowseUiState.Empty
                } else {
                    BrowseUiState.Content(
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
