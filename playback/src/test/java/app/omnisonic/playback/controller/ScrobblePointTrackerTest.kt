package app.omnisonic.playback.controller

import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.events.PlaybackEventBus
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.jupiter.api.Test

private class RecordingBus : PlaybackEventBus {
    val emitted = mutableListOf<PlaybackEvent>()
    override val events: SharedFlow<PlaybackEvent> = MutableSharedFlow()

    override fun emit(event: PlaybackEvent) {
        emitted += event
    }
}

private fun entry(durationMs: Long? = 200_000L) = PlaybackQueueEntry(
    providerId = 1,
    externalId = "ext-1",
    queueItemId = 7,
    title = "T1",
    durationMs = durationMs,
)

class ScrobblePointTrackerTest {

    @Test
    fun `emits once at the threshold crossing`() {
        val bus = RecordingBus()
        val tracker = ScrobblePointTracker(bus)

        tracker.onPosition(entry(), 189_999, isPlaying = true)
        assertThat(bus.emitted).isEmpty()

        tracker.onPosition(entry(), 190_000, isPlaying = true)
        tracker.onPosition(entry(), 195_000, isPlaying = true)

        val points = bus.emitted.filterIsInstance<PlaybackEvent.ScrobblePoint>()
        assertThat(points.single().positionMs).isEqualTo(190_000L)
        assertThat(points.single().entry.queueItemId).isEqualTo(7L)
    }

    @Test
    fun `ignores positions without a duration or while not playing`() {
        val bus = RecordingBus()
        val tracker = ScrobblePointTracker(bus)

        tracker.onPosition(entry(durationMs = null), 199_000, isPlaying = true)
        tracker.onPosition(null, 199_000, isPlaying = true)
        tracker.onPosition(entry(), 199_000, isPlaying = false)
        tracker.onPosition(entry(durationMs = 0), 199_000, isPlaying = true)

        assertThat(bus.emitted).isEmpty()
    }

    @Test
    fun `a seek past the threshold while paused counts once playback resumes`() {
        val bus = RecordingBus()
        val tracker = ScrobblePointTracker(bus)

        tracker.onPosition(entry(), 195_000, isPlaying = false)
        assertThat(bus.emitted).isEmpty()

        tracker.onPosition(entry(), 195_000, isPlaying = true)
        assertThat(bus.emitted.filterIsInstance<PlaybackEvent.ScrobblePoint>()).hasSize(1)
    }

    @Test
    fun `positions inside one playback emit once`() {
        val bus = RecordingBus()
        val tracker = ScrobblePointTracker(bus)

        tracker.onPosition(entry(), 191_000, isPlaying = true)
        tracker.onPosition(entry(), 192_000, isPlaying = true)

        assertThat(bus.emitted).hasSize(1)
    }

    @Test
    fun `a new playback re-arms the marker`() {
        val bus = RecordingBus()
        val tracker = ScrobblePointTracker(bus)

        tracker.onPlaybackStarted()
        tracker.onPosition(entry(), 191_000, isPlaying = true)
        assertThat(bus.emitted).hasSize(1)

        tracker.onPlaybackStarted()
        tracker.onPosition(entry(), 192_000, isPlaying = true)
        assertThat(bus.emitted).hasSize(2)
    }

    @Test
    fun `a natural finish below the threshold emits a full-play point`() {
        val bus = RecordingBus()
        val tracker = ScrobblePointTracker(bus)

        tracker.onPosition(entry(), 100_000, isPlaying = true)
        assertThat(bus.emitted).isEmpty()

        tracker.onFinished(entry(), playedAtMs = 198_000)
        tracker.onFinished(entry(), playedAtMs = 198_000)

        val points = bus.emitted.filterIsInstance<PlaybackEvent.ScrobblePoint>()
        assertThat(points.single().positionMs).isEqualTo(198_000L)
    }

    @Test
    fun `finish after a threshold emission does not double emit`() {
        val bus = RecordingBus()
        val tracker = ScrobblePointTracker(bus)

        tracker.onPosition(entry(), 191_000, isPlaying = true)
        tracker.onFinished(entry(), playedAtMs = 200_000)

        assertThat(bus.emitted.filterIsInstance<PlaybackEvent.ScrobblePoint>()).hasSize(1)
    }
}
