package app.omnisonic.core.data.sync

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory live status of library walks (D-16). [progress] feeds the sync
 * screens; [tryBegin]/[finish] guard against starting the same provider twice
 * (resume-on-launch and setup completion can race). Only one walk publishes at
 * a time in practice, so the flow carries the most recent value.
 */
@Singleton
class LibrarySyncStatusRepository @Inject constructor() {

    private val _progress = MutableStateFlow<LibrarySyncProgress?>(null)
    val progress: StateFlow<LibrarySyncProgress?> = _progress.asStateFlow()

    private val running = ConcurrentHashMap.newKeySet<Long>()

    private val _completions = MutableSharedFlow<Long>(extraBufferCapacity = 64)

    /**
     * Emits the provider id after its walk finished. Automatic-rule reactions
     * listen here because a pass that yielded to a running sync has no other
     * database event guaranteeing a fresh recompute (task 8a).
     */
    val completions: SharedFlow<Long> = _completions.asSharedFlow()

    /** Claims the provider for a new walk; false means one is already running. */
    fun tryBegin(providerId: Long): Boolean = running.add(providerId)

    fun finish(providerId: Long) {
        if (running.remove(providerId)) _completions.tryEmit(providerId)
    }

    fun isRunning(providerId: Long): Boolean = providerId in running

    fun publish(progress: LibrarySyncProgress) {
        _progress.value = progress
    }
}
