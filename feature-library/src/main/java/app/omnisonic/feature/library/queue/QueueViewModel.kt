package app.omnisonic.feature.library.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.playback.api.controller.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Queue screen state + edits (UI_SPEC.md §3.9) over [PlaybackController]'s
 * queue command port. Jump addresses playback order; remove/reorder address
 * queue entries. Reordering is refused while shuffled because the shown order
 * is the shuffle order, not the stable entries list.
 */
@HiltViewModel
class QueueViewModel @Inject constructor(
    private val controller: PlaybackController,
) : ViewModel() {

    val state: StateFlow<QueueUiState> = controller.queueState
        .map { it.toQueueUiState() }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            controller.queueState.value.toQueueUiState(),
        )

    fun jumpTo(orderIndex: Int) = controller.jumpToOrderIndex(orderIndex)

    fun remove(queueItemId: Long) = controller.removeFromQueue(queueItemId)

    fun moveUp(queueItemId: Long) {
        val index = shownIndex(queueItemId) ?: return
        if (!state.value.canReorder || index <= 0) return
        controller.moveInQueue(queueItemId, index - 1)
    }

    fun moveDown(queueItemId: Long) {
        val index = shownIndex(queueItemId) ?: return
        if (!state.value.canReorder || index >= state.value.rows.lastIndex) return
        controller.moveInQueue(queueItemId, index + 1)
    }

    fun clearQueueExceptCurrent() = controller.clearQueueExceptCurrent()

    private fun shownIndex(queueItemId: Long): Int? =
        state.value.rows.indexOfFirst { it.entry.queueItemId == queueItemId }.takeIf { it >= 0 }
}
