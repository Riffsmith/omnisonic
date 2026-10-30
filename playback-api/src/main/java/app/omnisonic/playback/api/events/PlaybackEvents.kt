package app.omnisonic.playback.api.events

import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import kotlinx.coroutines.flow.SharedFlow

enum class PauseReason { USER, FOCUS }
enum class TrackChangeCause { SOURCE_START, AUTO_ADVANCE, USER_NEXT, USER_PREV, JUMP, REPEAT_ONE, QUEUE_EDIT, RESTORE }
enum class EndCause { NATURAL, SKIPPED, REPLACED, STOPPED }

sealed interface PlaybackEvent {
    data class Started(val entry: PlaybackQueueEntry, val positionMs: Long) : PlaybackEvent
    data class Paused(val entry: PlaybackQueueEntry, val positionMs: Long, val reason: PauseReason) : PlaybackEvent
    data class Resumed(val entry: PlaybackQueueEntry, val positionMs: Long) : PlaybackEvent
    data class Position(val entry: PlaybackQueueEntry?, val positionMs: Long, val isPlaying: Boolean) : PlaybackEvent
    data class TrackChanged(
        val previous: PlaybackQueueEntry?,
        val current: PlaybackQueueEntry?,
        val cause: TrackChangeCause,
        val queueState: QueueState,
    ) : PlaybackEvent

    /** Track ended because the engine reached its end. */
    data class Finished(val entry: PlaybackQueueEntry, val playedAtMs: Long) : PlaybackEvent

    /** User moved off a track before it ended. */
    data class Skipped(val entry: PlaybackQueueEntry, val playedAtMs: Long, val manual: Boolean) : PlaybackEvent

    /** Emitted once per played playback of an entry once the scrobble threshold passes. */
    data class ScrobblePoint(val entry: PlaybackQueueEntry, val positionMs: Long) : PlaybackEvent
}

interface PlaybackEventBus {
    val events: SharedFlow<PlaybackEvent>
    fun emit(event: PlaybackEvent)
}
