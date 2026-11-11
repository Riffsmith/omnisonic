package app.omnisonic.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.feature.library.R
import app.omnisonic.feature.library.queue.QueueRow
import app.omnisonic.feature.library.queue.QueueUiState
import app.omnisonic.feature.library.queue.QueueViewModel
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.provider.api.CoverImageKey
import app.omnisonic.style.GestureTwin
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.gestureTwins

@Composable
fun QueueRoute(
    viewModel: QueueViewModel,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    QueueScreen(
        state = state,
        onJump = viewModel::jumpTo,
        onRemove = viewModel::remove,
        onMoveUp = viewModel::moveUp,
        onMoveDown = viewModel::moveDown,
        onClearRemaining = viewModel::clearQueueExceptCurrent,
        onBack = onBack,
        onOpenLibrary = onOpenLibrary,
    )
}

/**
 * Queue screen (UI_SPEC.md §3.9): header with the "Playing N of M" summary and
 * a clear-remaining action, then the queue in playback order with the current
 * row marked. Rows jump on tap, swipe-to-remove (twin: the row's remove
 * button), reorder through the row overflow (drag-reorder waits for the Phase
 * 9 row-action work), and the clear action keeps only the playing entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    state: QueueUiState,
    onJump: (Int) -> Unit,
    onRemove: (Long) -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    onClearRemaining: () -> Unit,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = tokens.layout.listRowMinHeight)
                    .padding(horizontal = tokens.spacing.sm),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.queue_back),
                    )
                }
                Text(
                    text = state.currentPosition?.let { position ->
                        stringResource(R.string.queue_playing_of, position, state.total)
                    } ?: stringResource(R.string.queue_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = tokens.spacing.sm),
                )
                TextButton(
                    onClick = onClearRemaining,
                    enabled = state.rows.size > 1,
                ) {
                    Text(stringResource(R.string.queue_clear_remaining))
                }
            }
            if (state.isEmpty) {
                QueueEmpty(onOpenLibrary = onOpenLibrary)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = tokens.layout.pagePadding,
                        vertical = tokens.spacing.sm,
                    ),
                ) {
                    items(state.rows, key = { row -> row.entry.queueItemId }) { row ->
                        QueueRowItem(
                            row = row,
                            canReorder = state.canReorder,
                            canMoveUp = state.canReorder &&
                                state.rows.firstOrNull()?.entry?.queueItemId != row.entry.queueItemId,
                            canMoveDown = state.canReorder && !row.isLast(state.rows),
                            onJump = onJump,
                            onRemove = onRemove,
                            onMoveUp = onMoveUp,
                            onMoveDown = onMoveDown,
                        )
                    }
                }
            }
        }
    }
}


private fun QueueRow.isLast(rows: List<QueueRow>): Boolean =
    rows.lastOrNull()?.entry?.queueItemId == entry.queueItemId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueRowItem(
    row: QueueRow,
    canReorder: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onJump: (Int) -> Unit,
    onRemove: (Long) -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val title = row.entry.title
    val removeLabel = stringResource(R.string.queue_remove, title)
    val moveUpLabel = stringResource(R.string.queue_move_up, title)
    val moveDownLabel = stringResource(R.string.queue_move_down, title)
    val twins = remember(removeLabel) {
        listOf(GestureTwin(GESTURE_QUEUE_REMOVE, removeLabel, removeLabel))
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onRemove(row.entry.queueItemId)
            false
        },
    )
    var menuOpen by remember { mutableStateOf(false) }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = tokens.layout.pagePadding),
                )
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = tokens.layout.listRowMinHeight)
                .background(MaterialTheme.colorScheme.background)
                .gestureTwins(twins) { gestureId ->
                    if (gestureId == GESTURE_QUEUE_REMOVE) onRemove(row.entry.queueItemId)
                }
                .clickable { onJump(row.orderIndex) }
                .padding(vertical = tokens.spacing.xs),
        ) {
            if (row.isCurrent) {
                Icon(
                    imageVector = Icons.Filled.Equalizer,
                    contentDescription = stringResource(R.string.queue_current_marker),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(tokens.imagery.uiIconSize),
                )
            } else {
                Spacer(Modifier.size(tokens.imagery.uiIconSize))
            }
            Spacer(Modifier.width(tokens.spacing.sm))
            CoverImage(
                cover = row.entry.coverImageKey(),
                contentDescription = null,
                modifier = Modifier.size(tokens.imagery.listThumbSize),
            )
            Spacer(Modifier.width(tokens.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.entry.artistName?.let { artist ->
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { onRemove(row.entry.queueItemId) }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = removeLabel,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.queue_row_actions, title),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(moveUpLabel) },
                        enabled = canReorder && canMoveUp,
                        onClick = {
                            menuOpen = false
                            onMoveUp(row.entry.queueItemId)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(moveDownLabel) },
                        enabled = canReorder && canMoveDown,
                        onClick = {
                            menuOpen = false
                            onMoveDown(row.entry.queueItemId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueEmpty(onOpenLibrary: () -> Unit) {
    val tokens = OmnisonicTheme.tokens
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.md),
            modifier = Modifier.padding(horizontal = tokens.layout.pagePadding),
        ) {
            Text(
                text = stringResource(R.string.queue_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.queue_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenLibrary) {
                Text(stringResource(R.string.queue_open_library))
            }
        }
    }
}

private fun PlaybackQueueEntry.coverImageKey(): CoverImageKey? =
    coverImageId?.let { CoverImageKey(providerId, it) }

private const val GESTURE_QUEUE_REMOVE = "queue-remove"
