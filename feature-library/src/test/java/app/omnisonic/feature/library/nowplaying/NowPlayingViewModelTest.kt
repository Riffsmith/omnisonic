package app.omnisonic.feature.library.nowplaying

import app.omnisonic.feature.library.FakePlaybackController
import app.omnisonic.feature.library.entry
import app.omnisonic.playback.api.controller.PlayerCoreState
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.queue.ShuffleState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `maps the playing entry from player and queue state`() {
        val controller = FakePlaybackController()
        controller.player.value = PlayerCoreState(
            current = entry(1, title = "Night Ride"),
            isPlaying = true,
            positionMs = 30_000,
            durationMs = 200_000,
        )
        controller.queue.value = QueueState(
            entries = listOf(entry(1)),
            shuffle = ShuffleState(order = listOf(0), seed = 7),
            repeatMode = RepeatMode.ALL,
        )

        val state = NowPlayingViewModel(controller).state.value

        assertThat(state.isEmpty).isFalse()
        assertThat(state.entry?.title).isEqualTo("Night Ride")
        assertThat(state.entry?.artistName).isEqualTo("Artist 1")
        assertThat(state.entry?.cover?.imageId).isEqualTo("cover-1")
        assertThat(state.isPlaying).isTrue()
        assertThat(state.positionMs).isEqualTo(30_000L)
        assertThat(state.durationMs).isEqualTo(200_000L)
        assertThat(state.shuffleEnabled).isTrue()
        assertThat(state.repeatMode).isEqualTo(RepeatMode.ALL)
        assertThat(state.progress).isWithin(0.001f).of(0.15f)
    }

    @Test
    fun `empty state when nothing is queued`() {
        val state = NowPlayingViewModel(FakePlaybackController()).state.value

        assertThat(state.isEmpty).isTrue()
        assertThat(state.progress).isEqualTo(0f)
    }

    @Test
    fun `queue changes flow into the state`() {
        val controller = FakePlaybackController()
        val viewModel = NowPlayingViewModel(controller)
        assertThat(viewModel.state.value.isEmpty).isTrue()

        controller.player.value = PlayerCoreState(current = entry(2), isPlaying = false)
        controller.queue.value = QueueState(entries = listOf(entry(2)))

        assertThat(viewModel.state.value.entry?.queueItemId).isEqualTo(2L)
        assertThat(viewModel.state.value.isPlaying).isFalse()
    }

    @Test
    fun `toggle play pause dispatches the controller toggle`() {
        val controller = FakePlaybackController()
        val viewModel = NowPlayingViewModel(controller)

        viewModel.togglePlayPause()

        assertThat(controller.playOrPauseCalls).hasSize(1)
    }

    @Test
    fun `transport buttons dispatch next and previous`() {
        val controller = FakePlaybackController()
        val viewModel = NowPlayingViewModel(controller)

        viewModel.next()
        viewModel.previous()

        assertThat(controller.nextCalls).hasSize(1)
        assertThat(controller.previousCalls).hasSize(1)
    }

    @Test
    fun `shuffle toggles against the current queue state`() {
        val controller = FakePlaybackController()
        val viewModel = NowPlayingViewModel(controller)

        viewModel.toggleShuffle()
        assertThat(controller.shuffleCalls).containsExactly(true)

        controller.queue.value = QueueState(shuffle = ShuffleState(order = listOf(0), seed = 1))
        viewModel.toggleShuffle()
        assertThat(controller.shuffleCalls).containsExactly(true, false).inOrder()
    }

    @Test
    fun `repeat cycles off all one off`() {
        val controller = FakePlaybackController()
        val viewModel = NowPlayingViewModel(controller)

        viewModel.cycleRepeat()
        controller.queue.value = QueueState(repeatMode = RepeatMode.ALL)
        viewModel.cycleRepeat()
        controller.queue.value = QueueState(repeatMode = RepeatMode.ONE)
        viewModel.cycleRepeat()

        assertThat(controller.repeatCalls)
            .containsExactly(RepeatMode.ALL, RepeatMode.ONE, RepeatMode.OFF)
            .inOrder()
    }

    @Test
    fun `seek clamps to the known duration`() {
        val controller = FakePlaybackController()
        controller.player.value = PlayerCoreState(
            current = entry(1),
            durationMs = 200_000,
        )
        val viewModel = NowPlayingViewModel(controller)

        viewModel.seekTo(300_000)
        viewModel.seekTo(-40)

        assertThat(controller.seeks).containsExactly(200_000L, 0L).inOrder()
    }

    @Test
    fun `seek without a duration only floors at zero`() {
        val controller = FakePlaybackController()
        controller.player.value = PlayerCoreState(current = entry(1, durationMs = null))
        val viewModel = NowPlayingViewModel(controller)

        viewModel.seekTo(9_999)

        assertThat(controller.seeks).containsExactly(9_999L)
    }
}
