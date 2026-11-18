package app.omnisonic.playback.history

import app.omnisonic.core.model.log.ConsoleLogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.events.PlaybackEventBus
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Watches the playback bus and turns it into listen-history writes.
 *
 * Events are funnelled through an unbounded channel and drained by one
 * coroutine, so writes never interleave and the recorded order is the emission
 * order. All eligibility state belongs to that single writer; no locks and no
 * shared mutable state. A failed write is logged and skipped: the next event is
 * still processed, so one bad transaction cannot silently stop listen history
 * for the rest of the process.
 *
 * One playback is one Started / non-terminal TrackChanged span. A play counts
 * when a position while playing reaches [playThresholdFraction] of the
 * duration, or when the engine reports a natural finish. `ScrobblePoint` is
 * only a hint: it is honoured under the same rule, so a threshold configured
 * below it still counts at the configured position and a higher one does not
 * count early.
 *
 * A position event only advances an already open span: a restart, seek or a
 * tick for the next queue item can never open (or re-open) a playback, and the
 * stored position is the latest reported one, so seeking backwards moves the
 * resume candidate back with it.
 *
 * A track change (skip, jump, queue edit) closes the span; a change below the
 * play threshold above the minimum position stores the resume point. Queue
 * `Skipped`/`Finished` events carry the last playback position in their
 * `playedAtMs` field, not a wall-clock stamp, so timestamps come from [clock].
 *
 * Radio entries have no stable library identity and are never recorded.
 */
class PlayHistoryRecorder(
    private val bus: PlaybackEventBus,
    private val sink: PlayHistorySink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val playThresholdFraction: Double = PlayHistoryRules.DEFAULT_PLAY_THRESHOLD_FRACTION,
    private val resumeMinPositionMs: Long = PlayHistoryRules.DEFAULT_RESUME_MIN_POSITION_MS,
    private val resumeMinFraction: Double = PlayHistoryRules.DEFAULT_RESUME_MIN_FRACTION,
    private val logger: OmnisonicLogger = OmnisonicLogger(ConsoleLogSink()),
) {

    private class OpenPlayback(
        val entry: PlaybackQueueEntry,
        var positionMs: Long,
        var counted: Boolean = false,
    )

    private var open: OpenPlayback? = null

    fun start(scope: CoroutineScope): Job {
        val inbox = Channel<PlaybackEvent>(Channel.UNLIMITED)
        scope.launch {
            bus.events.collect { inbox.trySend(it) }
        }
        return scope.launch {
            for (event in inbox) {
                try {
                    handle(event)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    logger.warn(TAG, "history write failed for ${event::class.simpleName}", failure)
                }
            }
        }
    }

    private suspend fun handle(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.Started -> begin(event.entry, event.positionMs)
            is PlaybackEvent.Resumed -> advance(event.entry, event.positionMs)
            is PlaybackEvent.Paused -> advance(event.entry, event.positionMs)
            is PlaybackEvent.Position -> onPosition(event.entry, event.positionMs, event.isPlaying)
            is PlaybackEvent.ScrobblePoint -> onThresholdHint(event.entry, event.positionMs)
            is PlaybackEvent.Finished -> onFinished(event.entry, event.playedAtMs)
            is PlaybackEvent.Skipped -> onSkipped(event.entry, event.playedAtMs)
            is PlaybackEvent.TrackChanged -> onTrackChanged(event.previous, event.current)
        }
    }

    private fun begin(entry: PlaybackQueueEntry, positionMs: Long) {
        open = if (entry.isRadioStation) null else OpenPlayback(entry, positionMs)
    }

    private fun advance(entry: PlaybackQueueEntry, positionMs: Long) {
        val current = open ?: return
        if (current.entry.queueItemId != entry.queueItemId) return
        current.positionMs = positionMs
    }

    private suspend fun onPosition(entry: PlaybackQueueEntry?, positionMs: Long, isPlaying: Boolean) {
        if (entry == null) return
        if (entry.isRadioStation) {
            open = null
            return
        }
        val current = open?.takeIf { it.entry.queueItemId == entry.queueItemId } ?: return
        current.positionMs = positionMs
        if (isPlaying && !current.counted && PlayHistoryRules.countsAsPlay(positionMs, entry.durationMs, playThresholdFraction)) {
            current.counted = true
            sink.recordPlay(entry, clock(), entry.durationMs ?: current.positionMs)
        }
    }

    private suspend fun onThresholdHint(entry: PlaybackQueueEntry, positionMs: Long) {
        val current = open?.takeIf { it.entry.queueItemId == entry.queueItemId } ?: return
        current.positionMs = positionMs
        if (current.counted) return
        if (PlayHistoryRules.countsAsPlay(positionMs, entry.durationMs, playThresholdFraction)) {
            current.counted = true
            sink.recordPlay(entry, clock(), entry.durationMs ?: current.positionMs)
        }
    }

    private suspend fun onFinished(entry: PlaybackQueueEntry, positionHint: Long) {
        val current = open?.takeIf { it.entry.queueItemId == entry.queueItemId } ?: return
        if (!current.counted) {
            current.counted = true
            val duration = entry.durationMs ?: maxOf(positionHint, current.positionMs)
            sink.recordPlay(entry, clock(), duration)
        }
        open = null
    }

    private suspend fun onSkipped(entry: PlaybackQueueEntry, positionHint: Long) {
        val current = open?.takeIf { it.entry.queueItemId == entry.queueItemId } ?: return
        current.positionMs = maxOf(current.positionMs, positionHint.coerceAtLeast(0L))
        if (!current.counted && current.positionMs > 0L) {
            sink.recordSkip(entry, clock(), current.positionMs)
        }
        saveResumeIfEarly(entry, current.positionMs)
        open = null
    }

    private suspend fun onTrackChanged(previous: PlaybackQueueEntry?, current: PlaybackQueueEntry?) {
        val closing = open
        if (closing != null && previous != null && closing.entry.queueItemId == previous.queueItemId) {
            saveResumeIfEarly(closing.entry, closing.positionMs)
            open = null
        }
        if (current != null) {
            open = if (current.isRadioStation) null else OpenPlayback(current, 0L)
        }
    }

    private suspend fun saveResumeIfEarly(entry: PlaybackQueueEntry, positionMs: Long) {
        val resume = PlayHistoryRules.resumePositionMs(
            positionMs = positionMs,
            durationMs = entry.durationMs,
            minPositionMs = resumeMinPositionMs,
            minFraction = resumeMinFraction,
            playThresholdFraction = playThresholdFraction,
        ) ?: return
        sink.saveResumePoint(entry, resume, clock())
    }

    private companion object {
        const val TAG = "PlayHistoryRecorder"
    }
}
