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

    /** Removes one queue entry; removing the playing entry advances playback. */
    fun removeFromQueue(queueItemId: Long)

    /** Reorders one queue entry to [toEntriesIndex] in the stable entries list. */
    fun moveInQueue(queueItemId: Long, toEntriesIndex: Int)

    /** Drops every entry except the current one ("clear remaining" on the queue screen). */
    fun clearQueueExceptCurrent()

    /**
     * Transport play/pause requested through the exposed player (session,
     * media button, lock screen). Returns true when the port handled the
     * command; a false result lets the player command its engine directly.
     */
    fun onPlayWhenReady(playWhenReady: Boolean): Boolean = false
}
