package app.omnisonic.playback.controller

import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.events.PlaybackEventBus
import app.omnisonic.playback.api.queue.PlaybackQueueEntry

/**
 * Publishes one [PlaybackEvent.ScrobblePoint] per played playback of an entry,
 * at the scrobble threshold. History writes (play events, counters, outbox
 * scrobbles) belong to the Phase 6 play counter — this only emits the event.
 *
 * A playback begins at every Started and non-terminal TrackChanged (skips,
 * advances, repeat-one restarts, queue edits); pause/resume and seeks inside
 * the same playback keep the marker. Threshold crossings count only while the
 * engine reports playing; a natural finish counts as a full play even when
 * the metadata duration overshoots the actual audio.
 */
class ScrobblePointTracker(
    private val bus: PlaybackEventBus,
    private val thresholdFraction: Double = DEFAULT_THRESHOLD_FRACTION,
) {
    private var emittedThisPlayback = false

    fun onPlaybackStarted() {
        emittedThisPlayback = false
    }

    fun onPosition(entry: PlaybackQueueEntry?, positionMs: Long, isPlaying: Boolean) {
        val duration = entry?.durationMs ?: return
        if (emittedThisPlayback || !isPlaying || duration <= 0L) return
        if (positionMs >= (duration * thresholdFraction).toLong()) {
            emittedThisPlayback = true
            bus.emit(PlaybackEvent.ScrobblePoint(entry, positionMs))
        }
    }

    fun onFinished(entry: PlaybackQueueEntry, playedAtMs: Long) {
        if (emittedThisPlayback) return
        emittedThisPlayback = true
        bus.emit(PlaybackEvent.ScrobblePoint(entry, playedAtMs))
    }

    companion object {
        /** Default play-count threshold; Phase 6 turns this into the setting. */
        const val DEFAULT_THRESHOLD_FRACTION = 0.95
    }
}
