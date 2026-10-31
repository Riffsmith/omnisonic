package app.omnisonic.playback.queue

import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.events.PlaybackEventBus
import app.omnisonic.playback.api.events.TrackChangeCause
import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.OmniPlayer
import app.omnisonic.playback.api.player.OmniPlayerListener
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.queue.ShuffleOrders
import app.omnisonic.playback.api.queue.ShuffleState
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.api.stream.StreamResolver
import androidx.media3.common.Player
import app.omnisonic.playback.api.equalizer.PeqConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.jupiter.api.Test

private class FakeOmniPlayer : OmniPlayer {
    data class PlayedCall(val stream: ResolvedStream, val entry: PlaybackQueueEntry)

    val played = mutableListOf<PlayedCall>()
    val prepared = mutableListOf<ResolvedStream?>()
    val seeks = mutableListOf<Long>()
    val speeds = mutableListOf<Float>()
    val pitches = mutableListOf<Float>()
    var stopCount = 0
    var released = false

    override fun exposedPlayer(): Player = error("not used in queue tests")

    override fun play(stream: ResolvedStream, entry: PlaybackQueueEntry) {
        played += PlayedCall(stream, entry)
    }

    override fun prepareNext(stream: ResolvedStream?) {
        prepared += stream
    }

    override fun pause() = Unit
    override fun resume() = Unit

    override fun stop() {
        stopCount++
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
    }

    override fun seekToStart() {
        seeks += 0L
    }

    override fun setPlaybackSpeed(scale: Float) {
        speeds += scale
    }

    override fun setPlaybackPitch(pitch: Float) {
        pitches += pitch
    }

    override fun setReplayGainLinear(multiplier: Float) = Unit
    override fun setMono(enabled: Boolean) = Unit
    override fun setPeq(config: PeqConfig?) = Unit
    override fun fadePause() = Unit
    override fun fadeResume() = Unit
    override fun applyEngineConfig(config: EngineConfig) = Unit

    override fun setStreamResolver(resolver: StreamResolver) = Unit

    override fun setListener(listener: OmniPlayerListener?) = Unit

    override fun release() {
        released = true
    }
}

private class FakeStreamResolver(
    private val failures: Set<Long> = emptySet(),
) : StreamResolver {
    override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream? {
        if (entry.queueItemId in failures) return null
        return ResolvedStream(
            entry = entry,
            uri = "stream://qa/${entry.queueItemId}",
            requiresNetwork = false,
        )
    }
}

private class FakeBus : PlaybackEventBus {
    val emitted = mutableListOf<PlaybackEvent>()
    override val events: SharedFlow<PlaybackEvent> = MutableSharedFlow()
    override fun emit(event: PlaybackEvent) {
        emitted += event
    }
}

private fun entry(id: Long, durationMs: Long? = null) = PlaybackQueueEntry(
    providerId = 1,
    externalId = "ext-$id",
    queueItemId = id,
    title = "T$id",
    durationMs = durationMs,
)

private class Fixture(
    val queue: QueuePlayerImpl,
    val playerFake: FakeOmniPlayer,
    val bus: FakeBus,
)

private fun newFixture(
    resolver: StreamResolver = FakeStreamResolver(),
    prevRestartThresholdMs: Long = QueuePlayerImpl.DEFAULT_PREV_RESTART_THRESHOLD_MS,
): Fixture {
    val bus = FakeBus()
    val playerFake = FakeOmniPlayer()
    val queue = QueuePlayerImpl(
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        player = playerFake,
        streamResolver = resolver,
        bus = bus,
        prevRestartThresholdMs = prevRestartThresholdMs,
    )
    return Fixture(queue, playerFake, bus)
}

private fun List<PlaybackEvent>.trackChanges() = filterIsInstance<PlaybackEvent.TrackChanged>()
private fun List<PlaybackEvent>.skips() = filterIsInstance<PlaybackEvent.Skipped>()
private fun List<PlaybackEvent>.finishes() = filterIsInstance<PlaybackEvent.Finished>()

@Suppress("PrivateApiUsage")
class QueuePlayerImplTest {

    @Test
    fun `setQueue starts the resolved entry and emits TrackChanged`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))

        assertThat(f.playerFake.played).hasSize(1)
        assertThat(f.playerFake.played.single().entry.queueItemId).isEqualTo(1L)
        assertThat(f.playerFake.played.single().stream.uri).isEqualTo("stream://qa/1")
        val changed = f.bus.emitted.trackChanges()
        assertThat(changed).hasSize(1)
        assertThat(changed.single().previous).isNull()
        assertThat(changed.single().current?.queueItemId).isEqualTo(1L)
        assertThat(changed.single().cause).isEqualTo(TrackChangeCause.SOURCE_START)
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
    }

    @Test
    fun `setQueue clamps an out-of-range start index to the nearest valid bound`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3)), startEntriesIndex = 9)

        assertThat(f.playerFake.played.single().entry.queueItemId).isEqualTo(3L)
        val g = newFixture()
        g.queue.setQueue(listOf(entry(1), entry(2), entry(3)), startEntriesIndex = -4)
        assertThat(g.playerFake.played.single().entry.queueItemId).isEqualTo(1L)
    }

    @Test
    fun `setQueue with empty list stops and nulls the current entry`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1)))
        f.queue.setQueue(emptyList())

        assertThat(f.playerFake.stopCount).isEqualTo(1)
        assertThat(f.queue.state.value.entries).isEmpty()
        assertThat(f.queue.state.value.currentEntry).isNull()
        val changed = f.bus.emitted.trackChanges()
        assertThat(changed).hasSize(2)
        assertThat(changed.last().previous?.queueItemId).isEqualTo(1L)
        assertThat(changed.last().current).isNull()
        assertThat(changed.last().cause).isEqualTo(TrackChangeCause.SOURCE_START)
    }

    @Test
    fun `setQueue replaces a playing queue, skips the old track and carries source info`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1)))
        f.queue.setQueue(listOf(entry(2), entry(3)), sourceType = "album", sourceData = "alb-9")

        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L, 2L).inOrder()
        val skips = f.bus.emitted.skips()
        assertThat(skips).hasSize(1)
        assertThat(skips.single().entry.queueItemId).isEqualTo(1L)
        assertThat(skips.single().manual).isTrue()
        val changed = f.bus.emitted.trackChanges()
        assertThat(changed).hasSize(2)
        assertThat(changed.last().previous?.queueItemId).isEqualTo(1L)
        assertThat(changed.last().current?.queueItemId).isEqualTo(2L)
        assertThat(changed.last().cause).isEqualTo(TrackChangeCause.SOURCE_START)
        assertThat(f.queue.state.value.sourceType).isEqualTo("album")
        assertThat(f.queue.state.value.sourceData).isEqualTo("alb-9")
    }

    @Test
    fun `append extends the queue without interrupting playback and dedupes ids`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1)), sourceType = "radio")
        f.queue.append(listOf(entry(2), entry(2), entry(3)))

        assertThat(f.playerFake.played).hasSize(1)
        assertThat(f.queue.state.value.entries.map { it.queueItemId }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
        assertThat(f.queue.state.value.sourceType).isEqualTo("radio")
        val appended = entry(4)
        f.queue.append(listOf(appended), sourceType = "search", sourceData = "s")
        assertThat(f.queue.state.value.sourceType).isEqualTo("search")
        assertThat(f.queue.state.value.sourceData).isEqualTo("s")
    }

    @Test
    fun `skipNext with manual false advances with AUTO_ADVANCE`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        f.queue.skipNext(false)

        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L, 2L).inOrder()
        val skips = f.bus.emitted.skips()
        assertThat(skips.single().manual).isFalse()
        assertThat(f.bus.emitted.trackChanges().last().cause).isEqualTo(TrackChangeCause.AUTO_ADVANCE)
    }

    @Test
    fun `prependNext while playing inserts after current without restarting playback`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(3)))
        f.queue.prependNext(entry(2))

        assertThat(f.playerFake.played).hasSize(1)
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
        f.queue.skipNext(true)
        f.queue.skipNext(true)
        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `prependNext while idle starts the entry`() {
        val f = newFixture()
        f.queue.prependNext(entry(1))

        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L)
        val changed = f.bus.emitted.trackChanges().single()
        assertThat(changed.current?.queueItemId).isEqualTo(1L)
        assertThat(changed.cause).isEqualTo(TrackChangeCause.SOURCE_START)
    }

    @Test
    fun `removeEntry before current shifts the order but keeps playback`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3)), startEntriesIndex = 1)
        f.queue.removeEntry(1L)

        assertThat(f.playerFake.played).hasSize(1)
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(2L)
        val skips = f.bus.emitted.skips()
        assertThat(skips).isEmpty()
        val changed = f.bus.emitted.trackChanges()
        assertThat(changed).hasSize(1)
    }

    @Test
    fun `removeEntry after current only updates the queue state`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3)))
        f.queue.removeEntry(3L)

        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
        assertThat(f.queue.state.value.entries).hasSize(2)
        assertThat(f.bus.emitted.trackChanges()).hasSize(1)
    }

    @Test
    fun `removeEntry of the current entry advances to the next one`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        f.queue.removeEntry(1L)

        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L, 2L).inOrder()
        assertThat(f.bus.emitted.skips().single().entry.queueItemId).isEqualTo(1L)
        val changed = f.bus.emitted.trackChanges()
        assertThat(changed).hasSize(2)
        assertThat(changed.last().previous?.queueItemId).isEqualTo(1L)
        assertThat(changed.last().current?.queueItemId).isEqualTo(2L)
        assertThat(changed.last().cause).isEqualTo(TrackChangeCause.QUEUE_EDIT)
    }

    @Test
    fun `removeEntry that empties the queue stops playback`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1)))
        f.queue.removeEntry(1L)

        assertThat(f.playerFake.stopCount).isEqualTo(1)
        assertThat(f.queue.state.value.entries).isEmpty()
        val changed = f.bus.emitted.trackChanges()
        assertThat(changed).hasSize(2)
        assertThat(changed.last().previous?.queueItemId).isEqualTo(1L)
        assertThat(changed.last().current).isNull()
    }

    @Test
    fun `moveEntry with shuffle off reorders entries and playIndex follows the current entry`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3)), startEntriesIndex = 1)
        f.queue.moveEntry(1L, toEntriesIndex = 2)

        assertThat(f.queue.state.value.entries.map { it.queueItemId }).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(2L)
        f.queue.skipNext(true)
        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(2L, 3L).inOrder()
    }

    @Test
    fun `moveEntry with shuffle on snapshots the play order and maps the entry to its new spot`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3), entry(4)), startEntriesIndex = 2)
        f.queue.setShuffleEnabled(true)
        val orderWhileShuffled = f.queue.state.value.effectiveOrder()
        val playIndexBefore = f.queue.state.value.playIndex

        f.queue.moveEntry(1L, toEntriesIndex = 0)

        val after = f.queue.state.value
        assertThat(after.effectiveOrder()).isEqualTo(orderWhileShuffled)
        assertThat(after.playIndex).isEqualTo(playIndexBefore)
        assertThat(after.entries.first().queueItemId).isEqualTo(1L)
        assertThat(after.entries.first().externalId).isEqualTo("ext-1")
        assertThat(after.currentEntry?.queueItemId).isEqualTo(3L)
        assertThat(after.entriesIndexAt()).isEqualTo(orderWhileShuffled[playIndexBefore])
    }

    @Test
    fun `jumpToEntry plays the target and skips the previous track`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3)))
        f.queue.jumpToEntry(2)

        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L, 3L).inOrder()
        assertThat(f.bus.emitted.skips().single().manual).isTrue()
        val changed = f.bus.emitted.trackChanges().last()
        assertThat(changed.previous?.queueItemId).isEqualTo(1L)
        assertThat(changed.current?.queueItemId).isEqualTo(3L)
        assertThat(changed.cause).isEqualTo(TrackChangeCause.JUMP)
    }

    @Test
    fun `jumpToEntry on the current order position is a no-op`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        val before = f.bus.emitted.toList()
        f.queue.jumpToEntry(0)

        assertThat(f.playerFake.played).hasSize(1)
        assertThat(f.bus.emitted).isEqualTo(before)
    }

    @Test
    fun `shuffle enable anchors the current entry first and skip advances through the shuffled order`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3), entry(4)), startEntriesIndex = 1)
        f.queue.setShuffleEnabled(true)

        val state = f.queue.state.value
        val shuffle = checkNotNull(state.shuffle)
        val expectedOrder = ShuffleOrders.shuffled(4, shuffle.seed, 1)
        assertThat(shuffle.order).isEqualTo(expectedOrder)
        assertThat(shuffle.order.first()).isEqualTo(1)
        assertThat(state.currentEntry?.queueItemId).isEqualTo(2L)

        f.queue.skipNext(true)
        val nextEntriesIndex = expectedOrder[1]
        assertThat(f.playerFake.played.last().entry.queueItemId)
            .isEqualTo(entry(nextEntriesIndex + 1L).queueItemId)
    }

    @Test
    fun `shuffle disable restores the entries order with current still in place`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3), entry(4)), startEntriesIndex = 2)
        f.queue.setShuffleEnabled(true)
        val currentId = f.queue.state.value.currentEntry?.queueItemId
        f.queue.setShuffleEnabled(false)

        assertThat(f.queue.state.value.shuffle).isNull()
        assertThat(f.queue.state.value.playIndex).isEqualTo(2)
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(currentId)
        f.queue.skipNext(true)
        assertThat(f.playerFake.played.last().entry.queueItemId).isEqualTo(4L)
    }

    @Test
    fun `reshuffle regenerates a deterministic order for the given seed`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3), entry(4)), startEntriesIndex = 0)
        f.queue.setShuffleEnabled(true)
        f.queue.reshuffle(42L)

        val state = f.queue.state.value
        assertThat(state.shuffle!!.seed).isEqualTo(42L)
        assertThat(state.shuffle!!.order).isEqualTo(ShuffleOrders.shuffled(4, 42L, 0))
    }

    @Test
    fun `natural finish with repeat OFF on the last entry stops and emits Finished plus null TrackChanged`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        f.queue.skipNext(false)
        f.queue.onPositionTick(5_000L)
        f.queue.onEngineFinishedCurrent()

        assertThat(f.bus.emitted.finishes().single().entry.queueItemId).isEqualTo(2L)
        val changed = f.bus.emitted.trackChanges().last()
        assertThat(changed.previous?.queueItemId).isEqualTo(2L)
        assertThat(changed.current).isNull()
        assertThat(changed.cause).isEqualTo(TrackChangeCause.AUTO_ADVANCE)
        assertThat(f.playerFake.stopCount).isEqualTo(1)
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(2L)
    }

    @Test
    fun `natural finish with repeat ALL wraps to the first entry`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        f.queue.setRepeatMode(RepeatMode.ALL)
        f.queue.skipNext(false)
        f.queue.onEngineFinishedCurrent()

        val changed = f.bus.emitted.trackChanges().last()
        assertThat(changed.previous?.queueItemId).isEqualTo(2L)
        assertThat(changed.current?.queueItemId).isEqualTo(1L)
        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L, 2L, 1L).inOrder()
    }

    @Test
    fun `natural finish with repeat ALL and shuffle on regenerates the order with a new seed`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3)), startEntriesIndex = 0)
        f.queue.setShuffleEnabled(true)
        val seedAfterEnable = f.queue.state.value.shuffle!!.seed
        f.queue.setRepeatMode(RepeatMode.ALL)
        f.queue.skipNext(false)
        f.queue.skipNext(false)
        f.queue.onEngineFinishedCurrent()

        val state = f.queue.state.value
        val newSeed = state.shuffle!!.seed
        assertThat(newSeed).isEqualTo(seedAfterEnable + 1)
        assertThat(state.playIndex).isEqualTo(0)
        assertThat(state.shuffle!!.order).isEqualTo(ShuffleOrders.shuffled(3, newSeed, -1))
        assertThat(state.entriesIndexAt()).isEqualTo(state.shuffle!!.order[0])
    }

    @Test
    fun `natural finish with repeat ONE restarts the same entry`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        f.queue.setRepeatMode(RepeatMode.ONE)
        f.queue.onPositionTick(999L)
        f.queue.onEngineFinishedCurrent()

        assertThat(f.playerFake.played).hasSize(2)
        assertThat(f.playerFake.played[1].entry.queueItemId).isEqualTo(1L)
        val finished = f.bus.emitted.finishes().single()
        assertThat(finished.entry.queueItemId).isEqualTo(1L)
        val changed = f.bus.emitted.trackChanges().last()
        assertThat(changed.previous?.queueItemId).isEqualTo(1L)
        assertThat(changed.current?.queueItemId).isEqualTo(1L)
        assertThat(changed.cause).isEqualTo(TrackChangeCause.REPEAT_ONE)
    }

    @Test
    fun `manual skip under repeat ONE leaves the repeated entry`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        f.queue.setRepeatMode(RepeatMode.ONE)
        f.queue.skipNext(true)

        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L, 2L).inOrder()
        assertThat(f.bus.emitted.trackChanges().last().cause).isEqualTo(TrackChangeCause.USER_NEXT)
    }

    @Test
    fun `skipNext on the last entry with repeat OFF stops playback`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        f.queue.skipNext(false)
        f.queue.skipNext(true)

        assertThat(f.playerFake.stopCount).isEqualTo(1)
        val changed = f.bus.emitted.trackChanges().last()
        assertThat(changed.previous?.queueItemId).isEqualTo(2L)
        assertThat(changed.current).isNull()
        assertThat(changed.cause).isEqualTo(TrackChangeCause.USER_NEXT)
    }

    @Test
    fun `skipPrevious deep into the track restarts it without queue events`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        val eventBaseline = f.bus.emitted.size
        f.queue.onPositionTick(5_000L)
        f.queue.skipPrevious()

        assertThat(f.playerFake.seeks).containsExactly(0L)
        assertThat(f.bus.emitted.size).isEqualTo(eventBaseline)
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
    }

    @Test
    fun `skipPrevious near the start advances to the previous entry as a manual skip`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)), startEntriesIndex = 1)
        f.queue.onPositionTick(900L)
        f.queue.skipPrevious()

        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(2L, 1L).inOrder()
        val skips = f.bus.emitted.skips()
        assertThat(skips).hasSize(1)
        assertThat(skips.single().manual).isTrue()
        assertThat(skips.single().playedAtMs).isEqualTo(900L)
        val changed = f.bus.emitted.trackChanges().last()
        assertThat(changed.cause).isEqualTo(TrackChangeCause.USER_PREV)
        assertThat(changed.previous?.queueItemId).isEqualTo(2L)
        assertThat(changed.current?.queueItemId).isEqualTo(1L)
    }

    @Test
    fun `skipPrevious from the first entry with repeat OFF restarts and with repeat ALL wraps`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        val eventBaseline = f.bus.emitted.size
        f.queue.skipPrevious()

        assertThat(f.playerFake.seeks).containsExactly(0L)
        assertThat(f.bus.emitted.size).isEqualTo(eventBaseline)

        val g = newFixture()
        g.queue.setQueue(listOf(entry(1), entry(2)))
        g.queue.setRepeatMode(RepeatMode.ALL)
        g.queue.skipPrevious()

        assertThat(g.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L, 2L).inOrder()
        assertThat(g.bus.emitted.trackChanges().last().current?.queueItemId).isEqualTo(2L)
    }

    @Test
    fun `position ticks carry into later Skipped events`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        f.queue.onPositionTick(1_234L)
        f.queue.skipNext(true)

        assertThat(f.bus.emitted.skips().single().playedAtMs).isEqualTo(1_234L)
    }

    @Test
    fun `start failures bounce to the next entry and a fully failing queue stops`() {
        val f = newFixture(FakeStreamResolver(failures = setOf(1L)))
        f.queue.setQueue(listOf(entry(1), entry(2)))

        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(2L)
        val changed = f.bus.emitted.trackChanges().single()
        assertThat(changed.current?.queueItemId).isEqualTo(2L)

        val g = newFixture(FakeStreamResolver(failures = setOf(1L, 2L)))
        g.queue.setQueue(listOf(entry(1), entry(2)))

        assertThat(g.playerFake.played).isEmpty()
        assertThat(g.queue.state.value.currentEntry?.queueItemId).isEqualTo(2L)
        val lastChanged = g.bus.emitted.trackChanges().last()
        assertThat(lastChanged.current).isNull()
        assertThat(g.playerFake.stopCount).isEqualTo(1)
    }

    @Test
    fun `engine errors advance without a Skipped emission`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        f.queue.onEngineError()

        val skips = f.bus.emitted.skips()
        assertThat(skips).isEmpty()
        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L, 2L).inOrder()
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(2L)
    }

    @Test
    fun `speed and pitch are clamped, forwarded and survive invalid input`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1)))

        f.queue.setPlaybackSpeed(2f)
        f.queue.setPlaybackPitch(0.5f)
        assertThat(f.playerFake.speeds.last()).isEqualTo(2f)
        assertThat(f.playerFake.pitches.last()).isEqualTo(0.5f)
        assertThat(f.queue.state.value.speed).isEqualTo(2f)
        assertThat(f.queue.state.value.pitch).isEqualTo(0.5f)

        f.queue.setPlaybackSpeed(9f)
        assertThat(f.queue.state.value.speed).isEqualTo(QueuePlayerImpl.MAX_SCALE)
        f.queue.setPlaybackPitch(Float.NaN)
        assertThat(f.queue.state.value.pitch).isEqualTo(0.5f)
        assertThat(f.playerFake.speeds).containsNoDuplicates()
    }

    @Test
    fun `repeat mode cycles OFF ALL ONE OFF`() {
        val f = newFixture()
        f.queue.cycleRepeatMode()
        assertThat(f.queue.state.value.repeatMode).isEqualTo(RepeatMode.ALL)
        f.queue.cycleRepeatMode()
        assertThat(f.queue.state.value.repeatMode).isEqualTo(RepeatMode.ONE)
        f.queue.cycleRepeatMode()
        assertThat(f.queue.state.value.repeatMode).isEqualTo(RepeatMode.OFF)
    }

    @Test
    fun `clearQueue keeps only the current entry when asked and drops everything otherwise`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3)))
        f.queue.clearQueue(keepCurrent = true)

        assertThat(f.queue.state.value.entries.map { it.queueItemId }).containsExactly(1L)
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
        assertThat(f.playerFake.stopCount).isEqualTo(0)

        f.queue.clearQueue(keepCurrent = false)
        assertThat(f.queue.state.value.currentEntry).isNull()
        assertThat(f.playerFake.stopCount).isEqualTo(1)
        assertThat(f.queue.state.value.sourceType).isNull()
        assertThat(f.bus.emitted.trackChanges().last().current).isNull()
    }

    @Test
    fun `restack regenerates the shuffle order anchored at the current entry`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3)), startEntriesIndex = 1)
        f.queue.setShuffleEnabled(true)
        val seedBefore = f.queue.state.value.shuffle!!.seed

        f.queue.restack()

        val state = f.queue.state.value
        val newSeed = state.shuffle!!.seed
        assertThat(newSeed).isGreaterThan(seedBefore)
        assertThat(state.currentEntry?.queueItemId).isEqualTo(2L)
        assertThat(state.playIndex).isEqualTo(0)
        assertThat(state.entriesIndexAt()).isEqualTo(1)
    }

    @Test
    fun `restore adopts a valid snapshot without playing and emits RESTORE`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)), sourceType = "album", sourceData = "a1")
        f.queue.setPlaybackSpeed(1.5f)
        val snapshot = f.queue.state.value.copy()

        val g = newFixture()
        g.queue.restore(snapshot)

        assertThat(g.playerFake.played).isEmpty()
        assertThat(g.playerFake.speeds).containsExactly(1.5f)
        assertThat(g.queue.state.value.entries).isEqualTo(snapshot.entries)
        assertThat(g.queue.state.value.playIndex).isEqualTo(snapshot.playIndex)
        assertThat(g.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
        assertThat(g.queue.state.value.sourceType).isEqualTo("album")
        val changed = g.bus.emitted.trackChanges().single()
        assertThat(changed.previous).isNull()
        assertThat(changed.current?.queueItemId).isEqualTo(1L)
        assertThat(changed.cause).isEqualTo(TrackChangeCause.RESTORE)
    }

    @Test
    fun `restore keeps a valid shuffled order and drops an invalid one`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3)))
        f.queue.setShuffleEnabled(true)
        val shuffledSnapshot = f.queue.state.value.copy()

        val g = newFixture()
        g.queue.restore(shuffledSnapshot)
        assertThat(g.queue.state.value.shuffle).isNotNull()
        assertThat(g.queue.state.value.shuffle!!.order).isEqualTo(shuffledSnapshot.shuffle!!.order)
        assertThat(g.queue.state.value.entriesIndexAt()).isEqualTo(shuffledSnapshot.entriesIndexAt())

        val h = newFixture()
        val broken = shuffledSnapshot.copy(
            shuffle = shuffledSnapshot.shuffle!!.copy(order = listOf(0, 0, 2)),
        )
        h.queue.restore(broken)
        assertThat(h.queue.state.value.shuffle).isNull()
        assertThat(h.queue.state.value.playIndex).isEqualTo(broken.playIndex.coerceIn(0, 2))
        assertThat(h.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
    }

    @Test
    fun `release stops and prevents further starts`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1)))
        f.queue.release()

        assertThat(f.playerFake.stopCount).isAtLeast(1)
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
        f.queue.jumpToEntry(0)
        assertThat(f.playerFake.played).hasSize(1)
        f.queue.setQueue(listOf(entry(2)))
        assertThat(f.playerFake.played).hasSize(1)
    }

    @Test
    fun `start prepares the expected next entry for gapless handoff`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3)))

        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L)
        assertThat(f.playerFake.prepared.single()?.entry?.queueItemId).isEqualTo(2L)
    }

    @Test
    fun `auto advance adopts the prepared next without replaying it`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3)))
        val playedBefore = f.playerFake.played.size

        f.queue.onEngineAutoAdvanced(entry(2))

        assertThat(f.playerFake.played).hasSize(playedBefore)
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(2L)
        assertThat(f.queue.state.value.playIndex).isEqualTo(1)
        val finishes = f.bus.emitted.finishes()
        assertThat(finishes.single().entry.queueItemId).isEqualTo(1L)
        val changed = f.bus.emitted.trackChanges().last()
        assertThat(changed.previous?.queueItemId).isEqualTo(1L)
        assertThat(changed.current?.queueItemId).isEqualTo(2L)
        assertThat(changed.cause).isEqualTo(TrackChangeCause.AUTO_ADVANCE)
        assertThat(f.playerFake.prepared.last()?.entry?.queueItemId).isEqualTo(3L)
    }

    @Test
    fun `auto advance to an unexpected entry resyncs to the queue current`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))

        f.queue.onEngineAutoAdvanced(entry(9))

        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L, 1L).inOrder()
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
        assertThat(f.queue.state.value.playIndex).isEqualTo(0)
        assertThat(f.playerFake.stopCount).isAtLeast(1)
    }

    @Test
    fun `auto advance wraps to the first entry under repeat all`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        f.queue.setRepeatMode(RepeatMode.ALL)
        f.queue.skipNext(false)

        f.queue.onEngineAutoAdvanced(entry(1))

        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
        assertThat(f.queue.state.value.playIndex).isEqualTo(0)
        assertThat(f.playerFake.played).hasSize(2)
        assertThat(f.bus.emitted.finishes().last().entry.queueItemId).isEqualTo(2L)
        assertThat(f.playerFake.prepared.last()?.entry?.queueItemId).isEqualTo(2L)
    }

    @Test
    fun `repeat one prepares no next so the engine can end naturally`() {
        val f = newFixture()
        f.queue.setRepeatMode(RepeatMode.ONE)
        f.queue.setQueue(listOf(entry(1), entry(2)))

        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L)
        assertThat(f.playerFake.prepared.single()).isNull()
    }

    @Test
    fun `queue end under repeat off prepares no next and natural finish still stops`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))
        f.queue.skipNext(false)
        assertThat(f.playerFake.prepared.last()).isNull()

        f.queue.onEngineFinishedCurrent()

        assertThat(f.bus.emitted.finishes().single().entry.queueItemId).isEqualTo(2L)
        assertThat(f.bus.emitted.trackChanges().last().current).isNull()
        assertThat(f.playerFake.stopCount).isEqualTo(1)
    }

    @Test
    fun `removing the prepared next entry refreshes the preparation to the new next`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2), entry(3)))
        assertThat(f.playerFake.prepared.last()?.entry?.queueItemId).isEqualTo(2L)

        f.queue.removeEntry(2L)

        assertThat(f.playerFake.prepared.last()?.entry?.queueItemId).isEqualTo(3L)
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
        assertThat(f.playerFake.played).hasSize(1)
    }

    @Test
    fun `clearing the queue stops playback and leaves nothing playing`() {
        val f = newFixture()
        f.queue.setQueue(listOf(entry(1), entry(2)))

        f.queue.clearQueue(keepCurrent = false)

        assertThat(f.playerFake.stopCount).isEqualTo(1)
        assertThat(f.queue.state.value.currentEntry).isNull()
        assertThat(f.queue.state.value.entries).isEmpty()
    }

    @Test
    fun `pending preparation is dropped when the current entry changes`() {
        val f = newFixture(
            resolver = object : StreamResolver {
                override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream? {
                    if (entry.queueItemId == 1L) return ResolvedStream(entry, "stream://qa/1")
                    awaitCancellation()
                }
            },
        )
        f.queue.setQueue(listOf(entry(1), entry(2)))

        assertThat(f.playerFake.played.map { it.entry.queueItemId }).containsExactly(1L)
        assertThat(f.playerFake.prepared).isEmpty()

        f.queue.skipNext(true)

        assertThat(f.playerFake.prepared).isEmpty()
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(2L)
    }
}
