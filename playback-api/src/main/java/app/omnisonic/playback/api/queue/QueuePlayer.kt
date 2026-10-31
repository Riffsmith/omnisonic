package app.omnisonic.playback.api.queue

import kotlinx.coroutines.flow.StateFlow

/**
 * Queue machine. Owns entries + play order + repeat semantics; commands an
 * [app.omnisonic.playback.api.player.OmniPlayer] per current entry. Emissions
 * of queue-structure events (track changed / finished / skipped / scrobble
 * point) belong here; transport events (started/paused/resumed) belong to the
 * controller layer wiring.
 */
interface QueuePlayer {
    val state: StateFlow<QueueState>

    fun setQueue(
        entries: List<PlaybackQueueEntry>,
        startEntriesIndex: Int = 0,
        sourceType: String? = null,
        sourceData: String? = null,
    )
    fun append(entries: List<PlaybackQueueEntry>, sourceType: String? = null, sourceData: String? = null)
    fun prependNext(entry: PlaybackQueueEntry)
    fun removeEntry(queueItemId: Long)
    fun moveEntry(queueItemId: Long, toEntriesIndex: Int)
    fun jumpToEntry(orderIndex: Int)
    fun clearQueue(keepCurrent: Boolean)
    fun restack()

    fun skipNext(manual: Boolean)
    fun skipPrevious()

    fun setShuffleEnabled(enabled: Boolean)
    fun reshuffle(seed: Long)
    fun setRepeatMode(mode: RepeatMode)
    fun cycleRepeatMode()
    fun setPlaybackSpeed(scale: Float)
    fun setPlaybackPitch(pitch: Float)

    /** Guarded restore for queue persistence consumers. */
    fun restore(snapshot: QueueState)

    /** Wire-up for engine-observed state moving through the controller. */
    fun onEngineFinishedCurrent()
    fun onEngineError()

    /**
     * The engine advanced to the prepared next entry on its own. The queue
     * machine validates the entry against its expected next, adopts it without
     * resolving or replaying, and prepares the following entry.
     */
    fun onEngineAutoAdvanced(entry: PlaybackQueueEntry)
    fun onPositionTick(positionMs: Long)

    fun release()
}
