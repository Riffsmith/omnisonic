package app.omnisonic.feature.library.queue

import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState

/** One queue row in playback order; [orderIndex] feeds jump commands. */
data class QueueRow(
    val entry: PlaybackQueueEntry,
    val orderIndex: Int,
    val isCurrent: Boolean,
)

/** Display copy for the snackbar of a removable row's undo (ui/queue-ux-improvements.md). */
data class PendingUndoUi(
    val queueItemId: Long,
    val title: String,
    val artistName: String?,
)

/**
 * Queue view state (UI_SPEC.md §3.9). Rows follow the effective playback order
 * (shuffle order when enabled), so the current marker and "Playing N of M"
 * always match what plays next. [isPlaying] gates the current row's animated
 * marker; [pendingUndo] is display-only — the undo action lives on the
 * view model.
 */
data class QueueUiState(
    val rows: List<QueueRow> = emptyList(),
    val currentOrderIndex: Int? = null,
    val shuffled: Boolean = false,
    val isPlaying: Boolean = false,
    val pendingUndo: PendingUndoUi? = null,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    val total: Int get() = rows.size

    val currentPosition: Int? get() = currentOrderIndex?.plus(1)

    /** Reordering addresses the stable entries list, which is the shown order only unshuffled. */
    val canReorder: Boolean get() = !shuffled
}

internal fun QueueState.toQueueUiState(isPlaying: Boolean = false): QueueUiState {
    val currentId = currentEntry?.queueItemId
    val rows = effectiveOrder().mapIndexedNotNull { orderIndex, entriesIndex ->
        entries.getOrNull(entriesIndex)?.let { entry ->
            QueueRow(entry = entry, orderIndex = orderIndex, isCurrent = entry.queueItemId == currentId)
        }
    }
    val current = playIndex.takeIf { it in rows.indices }
    return QueueUiState(
        rows = rows,
        currentOrderIndex = current,
        shuffled = shuffle != null,
        isPlaying = isPlaying,
    )
}
