package app.omnisonic.core.data.db.aggregation

import app.omnisonic.core.data.history.HistoryPreferences
import app.omnisonic.core.data.history.HistoryPreferencesStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Options for the album/artist recompute that runs inside a history write.
 *
 * Defaults follow the studied behaviour [INFERRED from `DATABASE_SCHEMA.md`
 * §4.3]: album play count is the rounded average of its songs, and artist play
 * count includes only track-level credits. Since Phase 6 task 9 the options
 * persist through [HistoryPreferencesStore]; this holder stays the single
 * synchronous read point for the write path once [awaitReady] returns.
 */
@Singleton
class PlayAggregateSettings private constructor(
    private val store: HistoryPreferencesStore,
    private val persist: Boolean,
) {

    @Inject
    constructor(store: HistoryPreferencesStore) : this(store, persist = true)

    /** In-memory-only instance for tests and non-persisted wiring. */
    constructor() : this(HistoryPreferencesStore.inMemory(), persist = false)

    data class Options(
        val albumCountRule: AlbumCountRule = AlbumCountRule.AVERAGE,
        val includeSupportingArtists: Boolean = false,
    )

    private val _options = MutableStateFlow(Options())

    @Volatile
    private var current = Options()
    private val stateLock = Any()
    private var initialized = false
    private var pendingOptions: Options? = null
    private val ready = CompletableDeferred<Unit>()

    // One lane: persisted writes must land in update order.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    init {
        if (persist) {
            scope.launch {
                val seeded = store.current().toOptions()
                val persistPending = synchronized(stateLock) {
                    val selected = pendingOptions ?: seeded
                    current = selected
                    _options.value = selected
                    initialized = true
                    pendingOptions != null
                }
                try {
                    if (persistPending) {
                        val options = current
                        store.setAggregateOptions(options.albumCountRule, options.includeSupportingArtists)
                    }
                } finally {
                    ready.complete(Unit)
                }
            }
        } else {
            initialized = true
            ready.complete(Unit)
        }
    }

    suspend fun awaitReady() = ready.await()

    fun options(): Options = current

    fun optionsFlow() = _options.asStateFlow()

    fun update(options: Options) {
        val persistNow = synchronized(stateLock) {
            current = options
            _options.value = options
            if (initialized) {
                true
            } else {
                pendingOptions = options
                false
            }
        }
        if (persist && persistNow) {
            scope.launch {
                store.setAggregateOptions(options.albumCountRule, options.includeSupportingArtists)
            }
        }
    }

    private fun HistoryPreferences.toOptions() = Options(
        albumCountRule = albumCountRule,
        includeSupportingArtists = includeSupportingArtists,
    )
}
