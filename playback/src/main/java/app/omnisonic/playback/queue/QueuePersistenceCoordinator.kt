package app.omnisonic.playback.queue

import app.omnisonic.playback.api.queue.QueuePlayer
import app.omnisonic.playback.api.queue.QueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Debounced automatic queue snapshotting around a [QueueSnapshotStore].
 * An empty state seen before any queue existed is ignored, so an empty startup
 * state never wipes a saved queue before [restoreInto] runs; once a queue has
 * existed (restored or played), an empty state is a real clear and is stored.
 * Every change is written after [debounceMs]; [flush] writes the pending
 * snapshot immediately (controller pause and service stop call it). Snapshot
 * writes are serialized and finish even when their debounce job is superseded.
 *
 * [restoreInto] must run on the engine thread because [QueuePlayer.restore]
 * commands the player synchronously; the observer may start before or after it.
 */
class QueuePersistenceCoordinator(
    private val store: QueueSnapshotStore,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {

    private val mutex = Mutex()
    private var observer: Job? = null
    private var saveJob: Job? = null

    @Volatile
    private var hasQueue = false

    @Volatile
    private var latest: QueueState? = null

    @Volatile
    private var lastSaved: QueueState? = null

    fun observe(state: StateFlow<QueueState>): Job {
        observer?.cancel()
        val job = scope.launch {
            state.collect { snapshot ->
                if (snapshot.entries.isEmpty() && !hasQueue) return@collect
                if (snapshot.entries.isNotEmpty()) hasQueue = true
                latest = snapshot
                saveJob?.cancel()
                saveJob = scope.launch {
                    delay(debounceMs)
                    persistLatest()
                }
            }
        }
        observer = job
        return job
    }

    suspend fun flush() {
        saveJob?.cancel()
        saveJob = null
        persistLatest()
    }

    suspend fun restoreInto(applySnapshot: (QueueState) -> Unit): Boolean {
        val snapshot = store.restore() ?: return false
        applySnapshot(snapshot)
        hasQueue = true
        latest = snapshot
        lastSaved = snapshot
        return true
    }

    suspend fun restoreInto(queue: QueuePlayer): Boolean = restoreInto(queue::restore)

    fun stop() {
        observer?.cancel()
        observer = null
        saveJob?.cancel()
        saveJob = null
    }

    private suspend fun persistLatest() {
        mutex.withLock {
            val snapshot = latest ?: return
            if (snapshot == lastSaved) return
            withContext(NonCancellable) { store.save(snapshot) }
            lastSaved = snapshot
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 500L
    }
}
