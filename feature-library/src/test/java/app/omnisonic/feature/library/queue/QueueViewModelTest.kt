package app.omnisonic.feature.library.queue

import app.omnisonic.feature.library.FakePlaybackController
import app.omnisonic.feature.library.entry
import app.omnisonic.playback.api.controller.PlayerCoreState
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
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

    @Test
    fun `remove captures the entry indices signature and display state`() {
        val controller = controllerWith(1, 2, 3, playIndex = 1)
        val viewModel = QueueViewModel(controller)

        viewModel.remove(2)
        controller.applyRemoval(2)

        assertThat(controller.removed).containsExactly(2L)
        val pending = requireNotNull(viewModel.state.value.pendingUndo)
        assertThat(pending.queueItemId).isEqualTo(2L)
        assertThat(pending.title).isEqualTo("Track 2")
        assertThat(pending.artistName).isEqualTo("Artist 2")

        viewModel.undoRemove()

        val inserted = controller.inserted.single()
        assertThat(inserted.first.queueItemId).isEqualTo(2L)
        assertThat(inserted.second).isEqualTo(1)
        assertThat(inserted.third).isEqualTo(1)
        assertThat(viewModel.state.value.pendingUndo).isNull()
    }

    @Test
    fun `undo dispatches once and a second undo is a no-op`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)
        viewModel.remove(2)
        controller.applyRemoval(2)

        viewModel.undoRemove()
        viewModel.undoRemove()

        assertThat(controller.inserted).hasSize(1)
    }

    @Test
    fun `undo drops a stale action without inserting`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)
        viewModel.remove(2)
        controller.applyRemoval(2)
        controller.queue.value = controller.queue.value.copy(
            entries = listOf(entry(1), entry(3), entry(4)),
        )

        viewModel.undoRemove()

        assertThat(controller.inserted).isEmpty()
        assertThat(viewModel.state.value.pendingUndo).isNull()
    }

    @Test
    fun `move clear and shuffle invalidate the pending undo`() {
        val moved = controllerWith(1, 2, 3)
        val movedViewModel = QueueViewModel(moved)
        movedViewModel.remove(2)
        moved.applyRemoval(2)
        movedViewModel.moveDown(1)
        assertThat(movedViewModel.state.value.pendingUndo).isNull()

        val cleared = controllerWith(1, 2, 3)
        val clearedViewModel = QueueViewModel(cleared)
        clearedViewModel.remove(2)
        cleared.applyRemoval(2)
        clearedViewModel.clearQueueExceptCurrent()
        assertThat(clearedViewModel.state.value.pendingUndo).isNull()

        val shuffled = controllerWith(1, 2, 3)
        val shuffledViewModel = QueueViewModel(shuffled)
        shuffledViewModel.remove(2)
        shuffled.applyRemoval(2)
        shuffled.queue.value = shuffled.queue.value.copy(
            shuffle = ShuffleState(order = listOf(1, 0), seed = 12),
        )
        assertThat(shuffledViewModel.state.value.pendingUndo).isNull()
    }

    @Test
    fun `another removal replaces the pending undo`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)
        viewModel.remove(2)
        controller.applyRemoval(2)

        viewModel.remove(3)
        controller.applyRemoval(3)

        assertThat(viewModel.state.value.pendingUndo?.queueItemId).isEqualTo(3L)
        viewModel.undoRemove()
        assertThat(controller.inserted.single().first.queueItemId).isEqualTo(3L)
    }

    @Test
    fun `jump play pause seek and track advance keep the undo valid`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)
        viewModel.remove(2)
        controller.applyRemoval(2)

        viewModel.jumpTo(0)
        controller.player.value = PlayerCoreState(isPlaying = true, positionMs = 1_000)
        controller.player.value = PlayerCoreState(isPlaying = false, positionMs = 1_000)
        controller.player.value = PlayerCoreState(isPlaying = true, positionMs = 42_000)
        controller.queue.value = controller.queue.value.copy(playIndex = 1, positionMs = 42_000)

        assertThat(viewModel.state.value.pendingUndo?.queueItemId).isEqualTo(2L)
        viewModel.undoRemove()
        assertThat(controller.inserted).hasSize(1)
    }

    @Test
    fun `shuffled removal keeps a valid undo`() {
        val controller = FakePlaybackController().apply {
            queue.value = QueueState(
                entries = listOf(entry(1), entry(2), entry(3)),
                shuffle = ShuffleState(order = listOf(2, 0, 1), seed = 4),
            )
        }
        val viewModel = QueueViewModel(controller)

        viewModel.remove(1)
        controller.applyRemoval(1)

        assertThat(viewModel.state.value.pendingUndo?.queueItemId).isEqualTo(1L)
        viewModel.undoRemove()
        val inserted = controller.inserted.single()
        assertThat(inserted.first.queueItemId).isEqualTo(1L)
        assertThat(inserted.second).isEqualTo(0)
        assertThat(inserted.third).isEqualTo(1)
    }

    @Test
    fun `isPlaying maps from the controller player state`() {
        val controller = controllerWith(1, 2)
        val viewModel = QueueViewModel(controller)
        controller.player.value = PlayerCoreState(isPlaying = true)

        assertThat(viewModel.state.value.isPlaying).isTrue()

        controller.player.value = PlayerCoreState(isPlaying = false)

        assertThat(viewModel.state.value.isPlaying).isFalse()
    }

    @Test
    fun `a removal after moved rows still exposes its own undo`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)
        viewModel.remove(1)
        controller.applyRemoval(1)
        viewModel.moveDown(2)
        controller.applyUnshuffledMove(2, 1)

        viewModel.remove(2)
        controller.applyRemoval(2)

        assertThat(viewModel.state.value.pendingUndo?.queueItemId).isEqualTo(2L)
        viewModel.undoRemove()
        assertThat(controller.inserted).hasSize(1)
    }

    @Test
    fun `a removal after an undo still exposes its own undo`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)
        viewModel.remove(1)
        controller.applyRemoval(1)
        viewModel.undoRemove()
        controller.applyInsertion(entry(1), 0)

        viewModel.remove(2)
        controller.applyRemoval(2)

        assertThat(viewModel.state.value.pendingUndo?.queueItemId).isEqualTo(2L)
        viewModel.undoRemove()
        assertThat(controller.inserted.last().first.queueItemId).isEqualTo(2L)
    }

    @Test
    fun `a removal after an invalidated undo still exposes its own undo`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)
        viewModel.remove(1)
        controller.applyRemoval(1)
        viewModel.clearQueueExceptCurrent()
        controller.queue.value = controller.queue.value.copy(entries = listOf(entry(2)))

        viewModel.remove(2)
        controller.applyRemoval(2)

        assertThat(viewModel.state.value.pendingUndo?.queueItemId).isEqualTo(2L)
        viewModel.undoRemove()
        assertThat(controller.inserted).hasSize(1)
    }

    @Test
    fun `natural timeout drops the pending action without inserting`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)
        viewModel.remove(2)
        controller.applyRemoval(2)

        viewModel.undoExpired(2)

        assertThat(viewModel.state.value.pendingUndo).isNull()
        assertThat(controller.inserted).isEmpty()
        viewModel.undoRemove()
        assertThat(controller.inserted).isEmpty()
    }

    @Test
    fun `a stale timeout never clears a newer removal`() {
        val controller = controllerWith(1, 2, 3)
        val viewModel = QueueViewModel(controller)
        viewModel.remove(2)
        controller.applyRemoval(2)
        viewModel.remove(3)
        controller.applyRemoval(3)

        viewModel.undoExpired(2)

        assertThat(viewModel.state.value.pendingUndo?.queueItemId).isEqualTo(3L)
        viewModel.undoExpired(3)
        assertThat(viewModel.state.value.pendingUndo).isNull()
        assertThat(controller.inserted).isEmpty()
    }

    private fun FakePlaybackController.applyRemoval(queueItemId: Long) {
        val current = queue.value
        val entriesIndex = current.entries.indexOfFirst { it.queueItemId == queueItemId }
        if (entriesIndex < 0) return
        val orderPos = current.effectiveOrder().indexOf(entriesIndex)
        val entries = current.entries.filterNot { it.queueItemId == queueItemId }
        val shuffle = current.shuffle?.let { state ->
            val order = state.order
                .filterNot { it == entriesIndex }
                .map { if (it > entriesIndex) it - 1 else it }
            ShuffleState(order = order, seed = state.seed)
        }
        val playIndex = when {
            entries.isEmpty() -> 0
            orderPos < current.playIndex -> (current.playIndex - 1).coerceAtMost(entries.lastIndex)
            else -> current.playIndex.coerceAtMost(entries.lastIndex)
        }
        queue.value = current.copy(entries = entries, playIndex = playIndex, shuffle = shuffle)
    }

    private fun FakePlaybackController.applyInsertion(entry: PlaybackQueueEntry, entriesIndex: Int) {
        val current = queue.value
        val entries = current.entries.toMutableList().apply {
            add(entriesIndex.coerceIn(0, size), entry)
        }
        queue.value = current.copy(entries = entries)
    }

    private fun FakePlaybackController.applyUnshuffledMove(queueItemId: Long, toEntriesIndex: Int) {
        val current = queue.value
        val from = current.entries.indexOfFirst { it.queueItemId == queueItemId }
        if (from < 0) return
        val currentId = current.currentEntry?.queueItemId
        val entries = current.entries.toMutableList()
        val moved = entries.removeAt(from)
        entries.add(toEntriesIndex.coerceIn(0, entries.size), moved)
        queue.value = current.copy(
            entries = entries,
            playIndex = entries.indexOfFirst { it.queueItemId == currentId }.coerceAtLeast(0),
        )
    }
}
