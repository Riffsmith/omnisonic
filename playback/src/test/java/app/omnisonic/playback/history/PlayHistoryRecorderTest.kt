package app.omnisonic.playback.history

import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.events.PlaybackEventBus
import app.omnisonic.playback.api.events.TrackChangeCause
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private const val NOW = 1_700_000_000_000L
private const val DURATION = 200_000L

private class RecordingBus : PlaybackEventBus {
    private val mutable = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PlaybackEvent> = mutable

    override fun emit(event: PlaybackEvent) {
        mutable.tryEmit(event)
    }
}

private class FakeSink : PlayHistorySink {
    val calls = mutableListOf<String>()
    val plays = mutableListOf<String>()
    val skips = mutableListOf<String>()
    val resumes = mutableListOf<Pair<String, Long>>()

    override suspend fun recordPlay(entry: PlaybackQueueEntry, playedAtMs: Long, durationMs: Long) {
        calls += "play:${entry.externalId}:$playedAtMs:$durationMs"
        plays += entry.externalId
    }

    override suspend fun recordSkip(entry: PlaybackQueueEntry, playedAtMs: Long, durationMs: Long) {
        calls += "skip:${entry.externalId}:$playedAtMs:$durationMs"
        skips += entry.externalId
    }

    override suspend fun saveResumePoint(entry: PlaybackQueueEntry, positionMs: Long, now: Long) {
        calls += "resume:${entry.externalId}:$positionMs:$now"
        resumes += entry.externalId to positionMs
    }
}

private class Harness(
    scope: CoroutineScope,
    thresholdFraction: Double = PlayHistoryRules.DEFAULT_PLAY_THRESHOLD_FRACTION,
    resumeMinPositionMs: Long = PlayHistoryRules.DEFAULT_RESUME_MIN_POSITION_MS,
) {
    val bus = RecordingBus()
    val sink = FakeSink()

    init {
        PlayHistoryRecorder(
            bus = bus,
            sink = sink,
            clock = { NOW },
            playThresholdFraction = thresholdFraction,
            resumeMinPositionMs = resumeMinPositionMs,
        ).start(scope)
    }
}

private fun TestScope.harness(
    thresholdFraction: Double = PlayHistoryRules.DEFAULT_PLAY_THRESHOLD_FRACTION,
    resumeMinPositionMs: Long = PlayHistoryRules.DEFAULT_RESUME_MIN_POSITION_MS,
): Harness = Harness(backgroundScope, thresholdFraction, resumeMinPositionMs).also { runCurrent() }

private fun entry(
    externalId: String = "song-1",
    durationMs: Long? = DURATION,
    queueItemId: Long = 1,
    radio: Boolean = false,
) = PlaybackQueueEntry(
    providerId = 1,
    externalId = externalId,
    queueItemId = queueItemId,
    title = externalId,
    durationMs = durationMs,
    isRadioStation = radio,
)

private val emptyQueue = QueueState()

class PlayHistoryRecorderTest {

    @Test
    fun `counts at the threshold and only once per playback`() = runTest {
        val h = harness()
        val e = entry()

        h.bus.emit(PlaybackEvent.Started(e, 0))
        h.bus.emit(PlaybackEvent.Position(e, 189_999, isPlaying = true))
        runCurrent()
        assertThat(h.sink.plays).isEmpty()

        h.bus.emit(PlaybackEvent.Position(e, 190_000, isPlaying = true))
        h.bus.emit(PlaybackEvent.Position(e, 195_000, isPlaying = true))
        h.bus.emit(PlaybackEvent.ScrobblePoint(e, 195_000))
        runCurrent()

        assertThat(h.sink.plays).containsExactly("song-1")
        assertThat(h.sink.calls).containsExactly("play:song-1:$NOW:$DURATION")
    }

    @Test
    fun `a paused position never crosses the threshold`() = runTest {
        val h = harness()
        val e = entry()

        h.bus.emit(PlaybackEvent.Started(e, 0))
        h.bus.emit(PlaybackEvent.Position(e, 195_000, isPlaying = false))
        runCurrent()

        assertThat(h.sink.plays).isEmpty()
    }

    @Test
    fun `a natural finish below the threshold still counts as a full play`() = runTest {
        val h = harness()
        val e = entry()

        h.bus.emit(PlaybackEvent.Started(e, 0))
        h.bus.emit(PlaybackEvent.Position(e, 100_000, isPlaying = true))
        h.bus.emit(PlaybackEvent.Finished(e, 198_000))
        h.bus.emit(PlaybackEvent.TrackChanged(e, null, TrackChangeCause.AUTO_ADVANCE, emptyQueue))
        runCurrent()

        assertThat(h.sink.plays).containsExactly("song-1")
        assertThat(h.sink.resumes).isEmpty()
    }

    @Test
    fun `the scrobble hint respects the configured threshold`() = runTest {
        val e = entry()

        val high = harness()
        high.bus.emit(PlaybackEvent.Started(e, 0))
        high.bus.emit(PlaybackEvent.ScrobblePoint(e, 100_000))
        runCurrent()
        assertThat(high.sink.plays).isEmpty()

        val low = harness(thresholdFraction = 0.5)
        low.bus.emit(PlaybackEvent.Started(e, 0))
        low.bus.emit(PlaybackEvent.ScrobblePoint(e, 100_000))
        runCurrent()
        assertThat(low.sink.plays).containsExactly("song-1")
    }

    @Test
    fun `an early skip above the minimum logs a skip and saves the resume point`() = runTest {
        val h = harness()
        val e = entry()

        h.bus.emit(PlaybackEvent.Started(e, 0))
        h.bus.emit(PlaybackEvent.Position(e, 40_000, isPlaying = true))
        h.bus.emit(PlaybackEvent.Skipped(e, 40_000, manual = true))
        h.bus.emit(PlaybackEvent.TrackChanged(e, null, TrackChangeCause.USER_NEXT, emptyQueue))
        runCurrent()

        assertThat(h.sink.skips).containsExactly("song-1")
        assertThat(h.sink.resumes).containsExactly("song-1" to 40_000L)
        assertThat(h.sink.plays).isEmpty()
    }

    @Test
    fun `an early skip below the minimum logs the skip without a resume point`() = runTest {
        val h = harness()
        val e = entry()

        h.bus.emit(PlaybackEvent.Started(e, 0))
        h.bus.emit(PlaybackEvent.Position(e, 2_000, isPlaying = true))
        h.bus.emit(PlaybackEvent.Skipped(e, 2_000, manual = true))
        runCurrent()

        assertThat(h.sink.skips).containsExactly("song-1")
        assertThat(h.sink.resumes).isEmpty()
    }

    @Test
    fun `a plain track change saves a resume point and never logs a skip`() = runTest {
        val h = harness()
        val first = entry("song-1", queueItemId = 1)
        val second = entry("song-2", queueItemId = 2)

        h.bus.emit(PlaybackEvent.Started(first, 0))
        h.bus.emit(PlaybackEvent.Position(first, 70_000, isPlaying = true))
        h.bus.emit(PlaybackEvent.TrackChanged(first, second, TrackChangeCause.JUMP, emptyQueue))
        runCurrent()

        assertThat(h.sink.resumes).containsExactly("song-1" to 70_000L)
        assertThat(h.sink.skips).isEmpty()

        h.bus.emit(PlaybackEvent.Position(second, 195_000, isPlaying = true))
        runCurrent()
        assertThat(h.sink.plays).containsExactly("song-2")
    }

    @Test
    fun `repeat one counts each pass`() = runTest {
        val h = harness()
        val e = entry()

        h.bus.emit(PlaybackEvent.Started(e, 0))
        h.bus.emit(PlaybackEvent.Position(e, 195_000, isPlaying = true))
        h.bus.emit(PlaybackEvent.Finished(e, DURATION))
        h.bus.emit(PlaybackEvent.TrackChanged(e, e, TrackChangeCause.REPEAT_ONE, emptyQueue))
        h.bus.emit(PlaybackEvent.Position(e, 195_000, isPlaying = true))
        runCurrent()

        assertThat(h.sink.plays).containsExactly("song-1", "song-1").inOrder()
    }

    @Test
    fun `a completed playback is not counted again when it changes`() = runTest {
        val h = harness()
        val e = entry()

        h.bus.emit(PlaybackEvent.Started(e, 0))
        h.bus.emit(PlaybackEvent.Position(e, 196_000, isPlaying = true))
        h.bus.emit(PlaybackEvent.TrackChanged(e, null, TrackChangeCause.SOURCE_START, emptyQueue))
        runCurrent()

        assertThat(h.sink.plays).hasSize(1)
        assertThat(h.sink.resumes).isEmpty()
    }

    @Test
    fun `radio entries never reach the history`() = runTest {
        val h = harness()
        val e = entry(radio = true)

        h.bus.emit(PlaybackEvent.Started(e, 0))
        h.bus.emit(PlaybackEvent.Position(e, 195_000, isPlaying = true))
        h.bus.emit(PlaybackEvent.Finished(e, DURATION))
        runCurrent()

        assertThat(h.sink.calls).isEmpty()
    }

    @Test
    fun `a burst of events is written in emission order`() = runTest {
        val h = harness()
        val first = entry("song-1", queueItemId = 1)
        val second = entry("song-2", queueItemId = 2)

        h.bus.emit(PlaybackEvent.Started(first, 0))
        h.bus.emit(PlaybackEvent.Position(first, 195_000, isPlaying = true))
        h.bus.emit(PlaybackEvent.TrackChanged(first, second, TrackChangeCause.AUTO_ADVANCE, emptyQueue))
        h.bus.emit(PlaybackEvent.Position(second, 195_000, isPlaying = true))
        runCurrent()

        assertThat(h.sink.plays).containsExactly("song-1", "song-2").inOrder()
        assertThat(h.sink.calls).hasSize(2)
    }
}
