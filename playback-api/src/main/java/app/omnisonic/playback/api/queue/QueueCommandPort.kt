package app.omnisonic.playback.api.queue

import kotlinx.coroutines.flow.StateFlow

/**
 * Queue-transport surface the exposed player's queue-aware commands route to.
 * Implemented by the controller wiring over the queue machine; the exposed
 * player never touches the queue machine directly. [queueState] drives the
 * exposed full-queue timeline and command availability.
 */
interface QueueCommandPort {
    val queueState: StateFlow<QueueState>

    fun skipNext()
    fun skipPrevious()

    /** Queue jump in effective-order coordinates. */
    fun jumpToOrderIndex(orderIndex: Int)

    fun setRepeatMode(mode: RepeatMode)
    fun setShuffleEnabled(enabled: Boolean)
}
