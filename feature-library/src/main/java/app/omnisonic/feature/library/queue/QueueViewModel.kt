package app.omnisonic.feature.library.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.playback.api.controller.PlaybackController
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A removal awaiting its undo: the row with its captured stable-entries and
 * effective-order slots, and the structural identity the queue must still show
 * for the undo to be safe — the post-removal entry ids, the shuffle seed and
 * the queue source. Ids only, no pointer data: a jump or a track advance must
 * not invalidate the undo (ui/queue-ux-improvements.md). The shuffle identity
 * is the seed, not the order: a removal itself shortens the shuffled order, so
 * comparing orders would invalidate every shuffled undo, while a toggle or
 * reshuffle always changes the seed.
 */
private data class PendingRemoval(
    val entry: PlaybackQueueEntry,
    val entriesIndex: Int,
    val orderIndex: Int,
    val signature: List<Long>,
    val shuffleSeed: Long?,
    val sourceType: String?,
    val sourceData: String?,
)

/**
 * Queue screen state + edits (UI_SPEC.md §3.9) over [PlaybackController]'s
 * queue command port. Jump addresses playback order; remove/reorder address
 * queue entries. Reordering is refused while shuffled because the shown order
 * is the shuffle order, not the stable entries list.
 *
 * Undo (ui/queue-ux-improvements.md): remove captures [PendingRemoval] before
 * dispatching; undo dispatches the restore only while the live queue still
 * matches the captured signature — any structural divergence (shuffle, move,
 * clear, replacement, another removal's result) drops it silently. Undo never
 * touches transport; restoring into an emptied queue stays idle.
 */
@HiltViewModel
class QueueViewModel @Inject constructor(
    private val controller: PlaybackController,
) : ViewModel() {

    private var pendingRemoval: PendingRemoval? = null
    private val pendingUndoFlow = MutableStateFlow<PendingUndoUi?>(null)

    /** Structural identity observed at the last queue emission (ids + shuffle + source). */
    private var observed: ObservedStructure? = null

    val state: StateFlow<QueueUiState> = combine(
        controller.queueState,
        controller.playerState,
        pendingUndoFlow,
    ) { queue, player, pendingUndo ->
        queue.toQueueUiState(isPlaying = player.isPlaying).copy(pendingUndo = pendingUndo)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        controller.queueState.value.toQueueUiState(),
    )

    // The observer runs independently of the pending state: the latest structure
    // must always be recorded, otherwise the first emission of a later removal
    // looks like an unrelated edit and cancels its own snackbar.
    init {
        viewModelScope.launch {
            controller.queueState.collect { observeQueue(it) }
        }
    }

    fun jumpTo(orderIndex: Int) = controller.jumpToOrderIndex(orderIndex)

    fun remove(queueItemId: Long) {
        val queue = controller.queueState.value
        val entriesIndex = queue.entries.indexOfFirst { it.queueItemId == queueItemId }
        if (entriesIndex < 0) return
        val entry = queue.entries[entriesIndex]
        val orderIndex = queue.effectiveOrder().indexOfFirst { queue.entries.getOrNull(it)?.queueItemId == queueItemId }
        if (orderIndex < 0) return
        val pending = PendingRemoval(
            entry = entry,
            entriesIndex = entriesIndex,
            orderIndex = orderIndex,
            signature = queue.entries.map { it.queueItemId } - queueItemId,
            shuffleSeed = queue.shuffle?.seed,
            sourceType = queue.sourceType,
            sourceData = queue.sourceData,
        )
        pendingRemoval = pending
        pendingUndoFlow.value = PendingUndoUi(
            queueItemId = entry.queueItemId,
            title = entry.title,
            artistName = entry.artistName,
        )
        controller.removeFromQueue(queueItemId)
    }

    fun undoRemove() {
        val pending = pendingRemoval ?: return
        val queue = controller.queueState.value
        val matches = queue.matchesExpected(pending)
        pendingRemoval = null
        pendingUndoFlow.value = null
        if (matches) {
            controller.insertIntoQueue(pending.entry, pending.entriesIndex, pending.orderIndex)
        }
    }

    /**
     * Natural snackbar timeout: drops the pending action silently, and only
     * when the expired snackbar still belongs to the current removal. A stale
     * timeout from an older snackbar must never clear a newer one, and expiry
     * never inserts anything.
     */
    fun undoExpired(queueItemId: Long) {
        val pending = pendingRemoval ?: return
        if (pending.entry.queueItemId != queueItemId) return
        pendingRemoval = null
        pendingUndoFlow.value = null
    }

    fun moveUp(queueItemId: Long) {
        val index = shownIndex(queueItemId) ?: return
        if (!state.value.canReorder || index <= 0) return
        invalidatePending()
        controller.moveInQueue(queueItemId, index - 1)
    }

    fun moveDown(queueItemId: Long) {
        val index = shownIndex(queueItemId) ?: return
        if (!state.value.canReorder || index >= state.value.rows.lastIndex) return
        invalidatePending()
        controller.moveInQueue(queueItemId, index + 1)
    }

    fun clearQueueExceptCurrent() {
        invalidatePending()
        controller.clearQueueExceptCurrent()
    }

    /**
     * Records the latest structural snapshot on every queue emission, then
     * drops a pending undo when that snapshot diverges from what the removal
     * left behind. A jump, play/pause, seek or position tick re-observes the
     * same structure and keeps the undo valid.
     */
    private fun observeQueue(queue: QueueState) {
        val structure = ObservedStructure(
            ids = queue.entries.map { it.queueItemId },
            shuffleSeed = queue.shuffle?.seed,
            sourceType = queue.sourceType,
            sourceData = queue.sourceData,
        )
        val previous = observed
        observed = structure
        val pending = pendingRemoval ?: return
        // The emission that created the pending action can arrive before the
        // removal itself is applied; an unchanged structure is never stale.
        if (previous == null || previous == structure) return
        if (!queue.matchesExpected(pending)) invalidatePending()
    }

    private fun QueueState.matchesExpected(pending: PendingRemoval): Boolean =
        entries.map { it.queueItemId } == pending.signature &&
            shuffle?.seed == pending.shuffleSeed &&
            sourceType == pending.sourceType &&
            sourceData == pending.sourceData

    private fun invalidatePending() {
        pendingRemoval = null
        pendingUndoFlow.value = null
    }

    private fun shownIndex(queueItemId: Long): Int? =
        state.value.rows.indexOfFirst { it.entry.queueItemId == queueItemId }.takeIf { it >= 0 }
}

private data class ObservedStructure(
    val ids: List<Long>,
    val shuffleSeed: Long?,
    val sourceType: String?,
    val sourceData: String?,
)
