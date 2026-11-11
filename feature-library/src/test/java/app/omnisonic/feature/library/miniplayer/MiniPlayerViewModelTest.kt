package app.omnisonic.feature.library.miniplayer

import app.omnisonic.feature.library.FakePlaybackController
import app.omnisonic.feature.library.entry
import app.omnisonic.playback.api.controller.PlayerCoreState
import app.omnisonic.playback.api.queue.QueueState
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
class MiniPlayerViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `maps the playing entry and transport flag`() {
        val controller = FakePlaybackController()
        controller.player.value = PlayerCoreState(
            current = entry(1, title = "Night Ride"),
            isPlaying = true,
        )

        val state = MiniPlayerViewModel(controller).state.value

        assertThat(state.isVisible).isTrue()
        assertThat(state.entry?.title).isEqualTo("Night Ride")
        assertThat(state.entry?.artistName).isEqualTo("Artist 1")
        assertThat(state.entry?.cover?.imageId).isEqualTo("cover-1")
        assertThat(state.isPlaying).isTrue()
    }

    @Test
    fun `falls back to the queue pointer when the engine has no current entry`() {
        val controller = FakePlaybackController()
        controller.queue.value = QueueState(entries = listOf(entry(3, title = "Encore")))

        val state = MiniPlayerViewModel(controller).state.value

        assertThat(state.entry?.title).isEqualTo("Encore")
        assertThat(state.isPlaying).isFalse()
    }

    @Test
    fun `hidden state when nothing is queued`() {
        val state = MiniPlayerViewModel(FakePlaybackController()).state.value

        assertThat(state.isVisible).isFalse()
        assertThat(state.entry).isNull()
    }

    @Test
    fun `playback changes flow into the bar state`() {
        val controller = FakePlaybackController()
        val viewModel = MiniPlayerViewModel(controller)
        assertThat(viewModel.state.value.isVisible).isFalse()

        controller.player.value = PlayerCoreState(current = entry(2), isPlaying = true)
        controller.queue.value = QueueState(entries = listOf(entry(2)))

        assertThat(viewModel.state.value.isVisible).isTrue()
        assertThat(viewModel.state.value.entry?.queueItemId).isEqualTo(2L)
        assertThat(viewModel.state.value.isPlaying).isTrue()
    }

    @Test
    fun `transport commands dispatch through the controller`() {
        val controller = FakePlaybackController()
        val viewModel = MiniPlayerViewModel(controller)

        viewModel.togglePlayPause()
        viewModel.next()

        assertThat(controller.playOrPauseCalls).hasSize(1)
        assertThat(controller.nextCalls).hasSize(1)
    }
}
