package app.omnisonic.playback.controller

import app.omnisonic.playback.api.events.PauseReason
import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.events.TrackChangeCause
import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.OmniPlayer
import app.omnisonic.playback.api.player.OmniPlayerListener
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.renderer.PlaybackRenderer
import app.omnisonic.playback.api.settings.NotificationAction
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.api.stream.StreamResolver
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.omnisonic.playback.api.equalizer.PeqConfig
import app.omnisonic.playback.events.PlaybackEventBusImpl
import app.omnisonic.playback.focus.AudioFocusHelper
import app.omnisonic.playback.focus.AudioFocusOwner
import app.omnisonic.playback.queue.QueuePersistenceCoordinator
import app.omnisonic.playback.queue.QueuePlayerImpl
import app.omnisonic.playback.queue.QueueSnapshotStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class FakePlayer : OmniPlayer {
    var playerListener: OmniPlayerListener? = null

    val played = mutableListOf<PlaybackQueueEntry>()
    val prepared = mutableListOf<ResolvedStream?>()
    val seeks = mutableListOf<Long>()
    val duckLevels = mutableListOf<Float>()
    var pauseCount = 0
    var resumeCount = 0
    var fadePauseCount = 0
    var fadeResumeCount = 0
    var stopCount = 0
    var released = false
    var positionMs = 0L

    override fun exposedPlayer(): Player = error("not used in controller tests")
    override fun play(stream: ResolvedStream, entry: PlaybackQueueEntry) {
        played += entry
        positionMs = stream.startPositionMs
    }

    override fun prepareNext(stream: ResolvedStream?) {
        prepared += stream
    }

    override fun pause() {
        pauseCount += 1
    }

    override fun resume() {
        resumeCount += 1
    }

    override fun stop() {
        stopCount += 1
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        this.positionMs = positionMs
    }

    override fun seekToStart() {
        seeks += 0L
    }

    override fun setPlaybackSpeed(scale: Float) = Unit
    override fun setPlaybackPitch(pitch: Float) = Unit
    override fun setReplayGainLinear(multiplier: Float) = Unit
    override fun setDuckLinear(multiplier: Float) {
        duckLevels += multiplier
    }

    override fun setMono(enabled: Boolean) = Unit
    override fun setPeq(config: PeqConfig?) = Unit
    override fun fadePause() {
        fadePauseCount += 1
    }

    override fun fadeResume() {
        fadeResumeCount += 1
    }

    override fun applyEngineConfig(config: EngineConfig) = Unit
    override fun setStreamResolver(resolver: StreamResolver) = Unit
    override fun setListener(listener: OmniPlayerListener?) {
        playerListener = listener
    }

    override fun release() {
        released = true
    }
}

private class FakeFocus : AudioFocusOwner {
    var granted = true
    var requestCount = 0
    var abandonCount = 0
    var releaseCount = 0
    var callbacks: AudioFocusHelper.Callbacks? = null

    override fun requestFocus(): Boolean {
        requestCount += 1
        return granted
    }

    override fun abandonFocus() {
        abandonCount += 1
    }

    override fun release() {
        releaseCount += 1
    }
}

private class FakeRenderer(private val player: OmniPlayer) : PlaybackRenderer {
    override val rendererId = "local"
    override val isLocal = true
    var deactivations = 0

    override suspend fun activate(): OmniPlayer = player
    override fun deactivate() {
        deactivations += 1
        player.release()
    }

    override fun isActive() = deactivations == 0
}

private class FakeResolver : StreamResolver {
    override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream? =
        ResolvedStream(entry = entry, uri = "stream://qa/${entry.queueItemId}", requiresNetwork = false)
}

private class RecordingStore : QueueSnapshotStore {
    val saved = mutableListOf<QueueState>()
    var stored: QueueState? = null

    override suspend fun save(state: QueueState) {
        saved += state
    }

    override suspend fun restore(): QueueState? = stored

    override suspend fun clear() {
        saved.clear()
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
    val controller: PlaybackControllerImpl,
    val player: FakePlayer,
    val focus: FakeFocus,
    val store: RecordingStore,
    val bus: PlaybackEventBusImpl,
    val queue: QueuePlayerImpl,
    val events: MutableList<PlaybackEvent>,
)

private fun TestScope.newFixture(
    engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    tickIntervalMs: Long = 500L,
): Fixture {
    val scope = backgroundScope
    val bus = PlaybackEventBusImpl()
    val events = mutableListOf<PlaybackEvent>()
    scope.launch { bus.events.collect { events += it } }
    val player = FakePlayer()
    val focus = FakeFocus()
    val queue = QueuePlayerImpl(
        engineScope = engineScope,
        player = player,
        streamResolver = FakeResolver(),
        bus = bus,
    )
    val store = RecordingStore()
    val controller = PlaybackControllerImpl(
        scope = scope,
        renderer = FakeRenderer(player),
        player = player,
        queue = queue,
        events = bus,
        persistence = QueuePersistenceCoordinator(store, scope),
        focusOwnerFactory = { callbacks -> focus.also { it.callbacks = callbacks } },
        tickIntervalMs = tickIntervalMs,
        positionReader = { player.positionMs },
    )
    return Fixture(controller, player, focus, store, bus, queue, events).also { runCurrent() }
}

private fun List<PlaybackEvent>.paused() = filterIsInstance<PlaybackEvent.Paused>()
private fun List<PlaybackEvent>.started() = filterIsInstance<PlaybackEvent.Started>()
private fun List<PlaybackEvent>.resumed() = filterIsInstance<PlaybackEvent.Resumed>()
private fun List<PlaybackEvent>.positions() = filterIsInstance<PlaybackEvent.Position>()

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerImplTest {

    @Test
    fun `play requests focus, starts the queue and emits Started`() = runTest {
        val f = newFixture()

        f.controller.play(listOf(entry(1), entry(2)))
        runCurrent()

        assertThat(f.focus.requestCount).isEqualTo(1)
        assertThat(f.player.played.single().queueItemId).isEqualTo(1L)
        assertThat(f.events.started().single().entry.queueItemId).isEqualTo(1L)
        assertThat(f.controller.playerState.value.current?.queueItemId).isEqualTo(1L)
        assertThat(f.controller.playerState.value.isIdle).isFalse()
    }

    @Test
    fun `play is refused when focus is denied`() = runTest {
        val f = newFixture()
        f.focus.granted = false

        f.controller.play(listOf(entry(1)))

        assertThat(f.player.played).isEmpty()
        assertThat(f.events.started()).isEmpty()
        assertThat(f.controller.playerState.value.current).isNull()
    }

    @Test
    fun `pause emits a user pause and flushes the snapshot`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 200_000)))
        runCurrent()
        f.player.positionMs = 30_000

        f.controller.pause()
        runCurrent()

        assertThat(f.player.fadePauseCount).isEqualTo(1)
        val paused = f.events.paused().single()
        assertThat(paused.reason).isEqualTo(PauseReason.USER)
        assertThat(paused.entry.queueItemId).isEqualTo(1L)
        assertThat(f.store.saved.last().entries.single().queueItemId).isEqualTo(1L)
        assertThat(f.controller.playerState.value.isPlaying).isFalse()
    }

    @Test
    fun `pause while idle is a no-op`() = runTest {
        val f = newFixture()

        f.controller.pause()

        assertThat(f.player.fadePauseCount).isEqualTo(0)
        assertThat(f.events.paused()).isEmpty()
    }

    @Test
    fun `resume after pause requests focus and fades back in`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1)))
        runCurrent()
        f.controller.pause()
        runCurrent()
        val requestsBefore = f.focus.requestCount

        f.controller.resume()
        runCurrent()

        assertThat(f.focus.requestCount).isEqualTo(requestsBefore + 1)
        assertThat(f.player.fadeResumeCount).isEqualTo(1)
        assertThat(f.events.resumed().single().entry.queueItemId).isEqualTo(1L)
    }

    @Test
    fun `playOrPause toggles pause and resume`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1)))
        runCurrent()

        f.controller.playOrPause()
        runCurrent()
        assertThat(f.player.fadePauseCount).isEqualTo(1)

        f.controller.playOrPause()
        runCurrent()
        assertThat(f.player.fadeResumeCount).isEqualTo(1)
    }

    @Test
    fun `ticker emits position events while playing`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 200_000)))
        f.player.playerListener?.isPlayingChanged(true)
        f.player.positionMs = 12_000

        advanceTimeBy(1_000)
        runCurrent()

        assertThat(f.events.positions().map { it.positionMs }).containsExactly(12_000L, 12_000L)
        assertThat(f.controller.playerState.value.positionMs).isEqualTo(12_000L)
        assertThat(f.queue.state.value.positionMs).isEqualTo(12_000L)
    }

    @Test
    fun `ticker stops after pause`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 200_000)))
        runCurrent()
        f.controller.pause()
        runCurrent()
        val positionsBefore = f.events.positions().size

        f.player.positionMs = 90_000
        advanceTimeBy(3_000)
        runCurrent()

        assertThat(f.events.positions()).hasSize(positionsBefore)
    }

    @Test
    fun `a finished queue goes idle and abandons focus`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 200_000)))
        runCurrent()
        f.player.playerListener?.isPlayingChanged(true)

        f.player.playerListener?.onEngineFinished(entry(1, durationMs = 200_000))
        runCurrent()

        assertThat(f.focus.abandonCount).isEqualTo(1)
        assertThat(f.controller.playerState.value.isIdle).isTrue()
        assertThat(f.controller.playerState.value.positionMs).isEqualTo(0L)
        assertThat(f.queue.state.value.currentEntry?.queueItemId).isEqualTo(1L)
    }

    @Test
    fun `focus duck reaches the player and the state`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1)))
        val callbacks = f.focus.callbacks!!

        callbacks.onDuck(0.2f)
        assertThat(f.player.duckLevels.last()).isEqualTo(0.2f)
        assertThat(f.controller.playerState.value.ducked).isTrue()

        callbacks.onUnduck()
        assertThat(f.player.duckLevels.last()).isEqualTo(1f)
        assertThat(f.controller.playerState.value.ducked).isFalse()
    }

    @Test
    fun `focus pause and resume round trip`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1)))
        runCurrent()
        val callbacks = f.focus.callbacks!!
        val savedBefore = f.store.saved.size

        callbacks.onPauseForFocus()
        runCurrent()

        assertThat(f.player.pauseCount).isEqualTo(1)
        assertThat(f.events.paused().single().reason).isEqualTo(PauseReason.FOCUS)
        assertThat(f.store.saved).hasSize(savedBefore)

        callbacks.onResumeAfterFocus()
        runCurrent()

        assertThat(f.player.fadeResumeCount).isEqualTo(1)
        assertThat(f.events.resumed().single().entry.queueItemId).isEqualTo(1L)
    }

    @Test
    fun `stop clears the queue, persists the clear and goes idle`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1), entry(2)))
        runCurrent()

        f.controller.stop()
        runCurrent()

        assertThat(f.queue.state.value.entries).isEmpty()
        assertThat(f.controller.playerState.value.isIdle).isTrue()
        assertThat(f.focus.abandonCount).isEqualTo(1)
        assertThat(f.store.saved.last().entries).isEmpty()
    }

    @Test
    fun `resume after a finished queue replays the pinned slot`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 200_000)))
        runCurrent()
        f.player.playerListener?.onEngineFinished(entry(1, durationMs = 200_000))
        runCurrent()
        val requestsBefore = f.focus.requestCount

        f.controller.resume()
        runCurrent()

        assertThat(f.focus.requestCount).isEqualTo(requestsBefore + 1)
        assertThat(f.player.played).hasSize(2)
        assertThat(f.controller.playerState.value.isIdle).isFalse()
    }

    @Test
    fun `seek clamps to the duration and records the resume point`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 300_000)))
        runCurrent()

        f.controller.seekTo(250_000)
        runCurrent()

        assertThat(f.player.seeks.single()).isEqualTo(250_000L)
        assertThat(f.queue.state.value.positionMs).isEqualTo(250_000L)
        assertThat(f.events.positions().single().positionMs).isEqualTo(250_000L)
    }

    @Test
    fun `seek without a current entry does not reach the player`() = runTest {
        val f = newFixture()

        f.controller.seekTo(5_000)

        assertThat(f.player.seeks).isEmpty()
    }

    @Test
    fun `notification actions map to transport and queue commands`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 200_000), entry(2)))
        runCurrent()
        f.player.positionMs = 100_000

        f.controller.applyAction(NotificationAction.REPEAT)
        assertThat(f.queue.state.value.repeatMode).isEqualTo(RepeatMode.ALL)

        f.controller.applyAction(NotificationAction.SHUFFLE)
        assertThat(f.queue.state.value.shuffle).isNotNull()

        f.controller.applyAction(NotificationAction.SEEK_FORWARD_30)
        assertThat(f.player.seeks.last()).isEqualTo(130_000L)

        f.controller.applyAction(NotificationAction.SEEK_BACK_10)
        assertThat(f.player.seeks.last()).isEqualTo(120_000L)

        f.controller.applyAction(NotificationAction.STOP)
        assertThat(f.controller.playerState.value.isIdle).isTrue()
    }

    @Test
    fun `playWhenReady routes through the transport commands`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1)))
        runCurrent()

        assertThat(f.controller.onPlayWhenReady(false)).isTrue()
        assertThat(f.player.fadePauseCount).isEqualTo(1)

        assertThat(f.controller.onPlayWhenReady(true)).isTrue()
        assertThat(f.player.fadeResumeCount).isEqualTo(1)
    }

    @Test
    fun `restoreQueue applies the snapshot without playing and resume replays it`() = runTest {
        val f = newFixture()
        f.store.stored = QueueState(
            entries = listOf(entry(7, durationMs = 200_000), entry(8)),
            playIndex = 0,
            positionMs = 95_000,
        )

        assertThat(f.controller.restoreQueue()).isTrue()

        assertThat(f.player.played).isEmpty()
        assertThat(f.controller.playerState.value.current?.queueItemId).isEqualTo(7L)
        assertThat(f.controller.playerState.value.positionMs).isEqualTo(95_000L)
        val requestsBefore = f.focus.requestCount

        f.controller.resume()
        runCurrent()

        assertThat(f.focus.requestCount).isEqualTo(requestsBefore + 1)
        assertThat(f.player.played.single().queueItemId).isEqualTo(7L)
        assertThat(f.player.positionMs).isEqualTo(95_000L)
    }

    @Test
    fun `restoreQueue reports nothing when no snapshot exists`() = runTest {
        val f = newFixture()

        assertThat(f.controller.restoreQueue()).isFalse()

        assertThat(f.controller.playerState.value.current).isNull()
    }

    @Test
    fun `restoreQueue applies a snapshot at most once per process`() = runTest {
        val f = newFixture()
        f.store.stored = QueueState(
            entries = listOf(entry(7), entry(8)),
            playIndex = 1,
            positionMs = 12_000,
        )

        assertThat(f.controller.restoreQueue()).isTrue()
        val restores = f.events.filterIsInstance<PlaybackEvent.TrackChanged>()
            .count { it.cause == TrackChangeCause.RESTORE }

        assertThat(f.controller.restoreQueue()).isFalse()

        assertThat(f.events.filterIsInstance<PlaybackEvent.TrackChanged>())
            .hasSize(restores)
        assertThat(f.controller.playerState.value.current?.queueItemId).isEqualTo(8L)
        assertThat(f.player.played).isEmpty()
    }

    @Test
    fun `restoreQueue leaves a queue that play already started untouched`() = runTest {
        val f = newFixture()
        f.store.stored = QueueState(entries = listOf(entry(7)), playIndex = 0, positionMs = 95_000)

        f.controller.play(listOf(entry(1), entry(2)))
        runCurrent()
        val playedBefore = f.player.played.map { it.queueItemId }

        assertThat(f.controller.restoreQueue()).isFalse()

        assertThat(f.controller.queueState.value.entries.map { it.queueItemId })
            .containsExactly(1L, 2L)
        assertThat(f.controller.playerState.value.current?.queueItemId).isEqualTo(1L)
        assertThat(f.player.played.map { it.queueItemId }).isEqualTo(playedBefore)
        assertThat(f.player.played.map { it.queueItemId }).doesNotContain(7L)
    }

    @Test
    fun `release tears down the pieces and ignores later commands`() = runTest {
        val f = newFixture()
        val renderer = f.controller.renderer as FakeRenderer
        f.controller.play(listOf(entry(1)))
        runCurrent()

        f.controller.release()
        f.controller.release()

        assertThat(f.focus.releaseCount).isEqualTo(1)
        assertThat(renderer.deactivations).isEqualTo(1)
        assertThat(f.player.released).isTrue()
        assertThat(f.player.played).hasSize(1)

        f.controller.resume()
        f.controller.applyAction(NotificationAction.STOP)
        assertThat(f.player.played).hasSize(1)
    }

    @Test
    fun `player state follows queue state changes`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 200_000), entry(2)))
        runCurrent()

        f.controller.skipNext()
        runCurrent()

        assertThat(f.controller.playerState.value.current?.queueItemId).isEqualTo(2L)
        assertThat(f.controller.playerState.value.durationMs).isEqualTo(null)
    }

    @Test
    fun `queue jump from an idle restored queue starts a session`() = runTest {
        val f = newFixture()
        f.store.stored = QueueState(entries = listOf(entry(1), entry(2, durationMs = 100_000)), playIndex = 1)
        f.controller.restoreQueue()
        val requestsBefore = f.focus.requestCount

        f.controller.jumpToOrderIndex(0)

        assertThat(f.focus.requestCount).isEqualTo(requestsBefore + 1)
        assertThat(f.player.played.single().queueItemId).isEqualTo(1L)
        assertThat(f.controller.playerState.value.isIdle).isFalse()
    }

    @Test
    fun `queue edits remove, move and clear through the command port`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1), entry(2), entry(3)))
        runCurrent()

        f.controller.removeFromQueue(2)
        runCurrent()
        assertThat(f.queue.state.value.entries.map { it.queueItemId }).containsExactly(1L, 3L).inOrder()

        f.controller.moveInQueue(3, 0)
        runCurrent()
        assertThat(f.queue.state.value.entries.map { it.queueItemId }).containsExactly(3L, 1L).inOrder()

        f.controller.clearQueueExceptCurrent()
        runCurrent()
        val state = f.queue.state.value
        assertThat(state.entries.map { it.queueItemId }).containsExactly(state.currentEntry?.queueItemId)
    }

    @Test
    fun `ticker crossing the scrobble threshold emits one point per playthrough`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 200_000)))
        runCurrent()
        f.player.playerListener?.isPlayingChanged(true)
        f.player.positionMs = 190_000

        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        val points = f.events.filterIsInstance<PlaybackEvent.ScrobblePoint>()
        assertThat(points.map { it.entry.queueItemId to it.positionMs }).containsExactly(1L to 190_000L)
    }

    @Test
    fun `playing below the scrobble threshold emits no point`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 200_000)))
        runCurrent()
        f.player.playerListener?.isPlayingChanged(true)
        f.player.positionMs = 100_000

        advanceTimeBy(2_000)
        runCurrent()

        assertThat(f.events.filterIsInstance<PlaybackEvent.ScrobblePoint>()).isEmpty()
    }

    @Test
    fun `skips and jumps re-arm the scrobble marker`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 200_000), entry(2, durationMs = 200_000)))
        runCurrent()
        f.player.playerListener?.isPlayingChanged(true)
        f.player.positionMs = 191_000
        advanceTimeBy(1_000)
        runCurrent()

        f.controller.skipNext()
        runCurrent()
        f.player.positionMs = 192_000
        advanceTimeBy(1_000)
        runCurrent()

        f.controller.jumpToOrderIndex(0)
        runCurrent()
        f.player.positionMs = 193_000
        advanceTimeBy(1_000)
        runCurrent()

        assertThat(f.events.filterIsInstance<PlaybackEvent.ScrobblePoint>().map { it.entry.queueItemId })
            .containsExactly(1L, 2L, 1L)
            .inOrder()
    }

    @Test
    fun `focus pause and resume do not re-emit the point`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 200_000)))
        runCurrent()
        f.player.playerListener?.isPlayingChanged(true)
        f.player.positionMs = 191_000
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(f.events.filterIsInstance<PlaybackEvent.ScrobblePoint>()).hasSize(1)

        val callbacks = f.focus.callbacks!!
        callbacks.onPauseForFocus()
        runCurrent()
        callbacks.onResumeAfterFocus()
        runCurrent()
        f.player.positionMs = 196_000
        advanceTimeBy(2_000)
        runCurrent()

        assertThat(f.events.filterIsInstance<PlaybackEvent.ScrobblePoint>()).hasSize(1)
    }

    @Test
    fun `a natural finish below the threshold emits a full-play point`() = runTest {
        val f = newFixture()
        f.controller.play(listOf(entry(1, durationMs = 200_000)))
        runCurrent()
        f.player.playerListener?.isPlayingChanged(true)
        f.player.positionMs = 100_000
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(f.events.filterIsInstance<PlaybackEvent.ScrobblePoint>()).isEmpty()

        f.player.playerListener?.onEngineFinished(entry(1, durationMs = 200_000))
        runCurrent()

        val points = f.events.filterIsInstance<PlaybackEvent.ScrobblePoint>()
        assertThat(points.single().entry.queueItemId).isEqualTo(1L)
        assertThat(points.single().positionMs).isEqualTo(200_000L)
    }
}
