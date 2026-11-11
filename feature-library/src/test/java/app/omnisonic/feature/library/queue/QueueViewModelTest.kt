package app.omnisonic.feature.library.queue

import app.omnisonic.feature.library.FakePlaybackController
import app.omnisonic.feature.library.entry
import app.omnisonic.playback.api.queue.QueueState
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
class QueueViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun controllerWith(vararg ids: Long, playIndex: Int = 0): FakePlaybackController =
        FakePlaybackController().apply {
            queue.value = QueueState(
                entries = ids.map { entry(it) },
                playIndex = playIndex,
            )
        }

    @Test
    fun `rows follow the queue order and mark the current entry`() {
        val state = QueueViewModel(controllerWith(1, 2, 3, playIndex = 1)).state.value

        assertThat(state.rows.map { it.entry.queueItemId }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(state.rows.map { it.orderIndex }).containsExactly(0, 1, 2).inOrder()
        assertThat(state.rows.filter { it.isCurrent }.map { it.entry.queueItemId }).containsExactly(2L)
        assertThat(state.currentOrderIndex).isEqualTo(1)
        assertThat(state.currentPosition).isEqualTo(2)
        assertThat(state.total).isEqualTo(3)
        assertThat(state.canReorder).isTrue()
    }

    @Test
    fun `shuffle order drives the rows and disables reordering`() {
        val controller = FakePlaybackController().apply {
            queue.value = QueueState(
                entries = listOf(entry(1), entry(2), entry(3)),
                playIndex = 0,
                shuffle = ShuffleState(order = listOf(2, 0, 1), seed = 4),
            )
        }

        val state = QueueViewModel(controller).state.value

        assertThat(state.rows.map { it.entry.queueItemId }).containsExactly(3L, 1L, 2L).inOrder()
        assertThat(state.rows.first().isCurrent).isTrue()
        assertThat(state.shuffled).isTrue()
        assertThat(state.canReorder).isFalse()
    }

    @Test
    fun `empty queue maps to the empty state`() {
        val state = QueueViewModel(FakePlaybackController()).state.value

        assertThat(state.isEmpty).isTrue()
        assertThat(state.currentPosition).isNull()
    }

    @Test
    fun `jump dispatches the playback order index`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)

        viewModel.jumpTo(2)

        assertThat(controller.jumps).containsExactly(2)
    }

    @Test
    fun `remove dispatches the queue item id`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)

        viewModel.remove(2)

        assertThat(controller.removed).containsExactly(2L)
    }

    @Test
    fun `move up and down translate shown positions to entries indices`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)

        viewModel.moveDown(1)
        viewModel.moveUp(3)

        assertThat(controller.moves).containsExactly(1L to 1, 3L to 1).inOrder()
    }

    @Test
    fun `moves at the ends are ignored`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)

        viewModel.moveUp(1)
        viewModel.moveDown(3)

        assertThat(controller.moves).isEmpty()
    }

    @Test
    fun `reordering is refused while shuffled`() {
        val controller = FakePlaybackController().apply {
            queue.value = QueueState(
                entries = listOf(entry(1), entry(2), entry(3)),
                shuffle = ShuffleState(order = listOf(0, 1, 2), seed = 9),
            )
        }
        val viewModel = QueueViewModel(controller)

        viewModel.moveDown(1)

        assertThat(controller.moves).isEmpty()
    }

    @Test
    fun `clear remaining dispatches the clear command`() {
        val controller = controllerWith(1, 2)
        val viewModel = QueueViewModel(controller)

        viewModel.clearQueueExceptCurrent()

        assertThat(controller.clearCalls).hasSize(1)
    }
}
