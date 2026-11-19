package app.omnisonic.core.data.history

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * Per-provider recording pause (D-7). The in-memory [pausedProviders] flow is
 * the read point for the write path once [awaitReady] returns; with the app
 * store injected it is seeded once from and persisted through
 * [HistoryPreferencesStore] (Phase 6 task 9), so the pause survives a restart
 * and a backup restore. Changes made during the seed are replayed over the
 * persisted set, then coalesced into one ordered write.
 */
@Singleton
class HistoryRecordingGate private constructor(
    private val store: HistoryPreferencesStore,
    private val persist: Boolean,
) {

    @Inject
    constructor(store: HistoryPreferencesStore) : this(store, persist = true)

    /** In-memory-only instance for tests and non-persisted wiring. */
    constructor() : this(HistoryPreferencesStore.inMemory(), persist = false)

    private val _pausedProviders = MutableStateFlow<Set<Long>>(emptySet())
    private val stateLock = Any()
    private var initialized = false
    private val pendingChanges = mutableListOf<Pair<Long, Boolean>>()
    private val ready = CompletableDeferred<Unit>()

    val pausedProviders: StateFlow<Set<Long>> = _pausedProviders.asStateFlow()

    // One lane: persisted writes must land in setPaused order, so the pause
    // set never regresses to an earlier toggle.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    init {
        if (persist) {
            scope.launch {
                val seeded = store.current().pausedProviderIds
                val pending = synchronized(stateLock) {
                    var merged = seeded
                    pendingChanges.forEach { (providerId, paused) ->
                        merged = if (paused) merged + providerId else merged - providerId
                    }
                    _pausedProviders.value = merged
                    initialized = true
                    pendingChanges.isNotEmpty()
                }
                try {
                    if (pending) store.setPausedProviderIds(_pausedProviders.value)
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

    fun isPaused(providerId: Long): Boolean = providerId in _pausedProviders.value

    fun setPaused(providerId: Long, paused: Boolean) {
        val persistNow = synchronized(stateLock) {
            _pausedProviders.updateAndGet { current ->
                if (paused) current + providerId else current - providerId
            }
            if (initialized) {
                true
            } else {
                pendingChanges += providerId to paused
                false
            }
        }
        if (persist && persistNow) {
            val next = _pausedProviders.value
            scope.launch { store.setPausedProviderIds(next) }
        }
    }
}
