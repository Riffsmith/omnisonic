package app.omnisonic.feature.library.ui

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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.db.RecentPlayRow
import app.omnisonic.feature.library.R
import app.omnisonic.feature.library.browse.BrowseUiState
import app.omnisonic.feature.library.history.HistoryViewModel
import app.omnisonic.provider.api.CoverImageKey
import app.omnisonic.style.OmnisonicTheme
import java.text.NumberFormat

private const val LOAD_MORE_KEY = "load-more"

/**
 * Recent-plays destination: header (title + play count), then the paged
 * newest-first list with its load-more footer, plus loading/empty/error
 * states. Each row carries the D-7 history controls in an overflow menu.
 */
@Composable
fun HistoryRoute(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pausedProviders by viewModel.pausedProviders.collectAsStateWithLifecycle()
    HistoryScreen(
        state = state,
        pausedProviders = pausedProviders,
        onRetry = viewModel::load,
        onLoadMore = viewModel::loadMore,
        onScrollInProgress = viewModel::setScrolling,
        onRemovePlay = viewModel::removePlay,
        onEditPlayCount = viewModel::editPlayCount,
        onWipeProvider = viewModel::wipeProvider,
        onSetRecordingPaused = viewModel::setRecordingPaused,
        modifier = modifier,
    )
}

@Composable
fun HistoryScreen(
    state: BrowseUiState<RecentPlayRow>,
    pausedProviders: Set<Long>,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onScrollInProgress: ((Boolean) -> Unit)? = null,
    onRemovePlay: (eventId: Long) -> Unit,
    onEditPlayCount: (providerId: Long, externalId: String, count: Int) -> Unit,
    onWipeProvider: (providerId: Long) -> Unit,
    onSetRecordingPaused: (providerId: Long, paused: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    Column(modifier = modifier.fillMaxSize()) {
        val loaded = state as? BrowseUiState.Content<RecentPlayRow>
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.layout.pagePadding, vertical = tokens.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            loaded?.totalCount?.let { count ->
                Spacer(Modifier.width(tokens.spacing.sm))
                Text(
                    text = NumberFormat.getIntegerInstance().format(count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = tokens.spacing.xxs),
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (state) {
                BrowseUiState.Loading -> TrackPlaceholders()
                BrowseUiState.Empty -> DetailMessageBody(stringResource(R.string.history_empty))
                BrowseUiState.Error -> DetailErrorBody(onRetry)
                is BrowseUiState.Content -> HistoryListBody(
                    content = state,
                    pausedProviders = pausedProviders,
                    onLoadMore = onLoadMore,
                    onScrollInProgress = onScrollInProgress,
                    onRemovePlay = onRemovePlay,
                    onEditPlayCount = onEditPlayCount,
                    onWipeProvider = onWipeProvider,
                    onSetRecordingPaused = onSetRecordingPaused,
                )
            }
        }
    }
}

@Composable
private fun HistoryListBody(
    content: BrowseUiState.Content<RecentPlayRow>,
    pausedProviders: Set<Long>,
    onLoadMore: () -> Unit,
    onScrollInProgress: ((Boolean) -> Unit)?,
    onRemovePlay: (eventId: Long) -> Unit,
    onEditPlayCount: (providerId: Long, externalId: String, count: Int) -> Unit,
    onWipeProvider: (providerId: Long) -> Unit,
    onSetRecordingPaused: (providerId: Long, paused: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    val listState = rememberLazyListState()
    ScrollGateReporter(listState, onScrollInProgress)
    var menuForEvent by remember { mutableStateOf<Long?>(null) }
    var confirmingRemove by remember { mutableStateOf<RecentPlayRow?>(null) }
    var confirmingWipe by remember { mutableStateOf<RecentPlayRow?>(null) }
    var editingRow by remember { mutableStateOf<RecentPlayRow?>(null) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = tokens.layout.pagePadding,
            vertical = tokens.spacing.md,
        ),
    ) {
        items(content.items, key = { it.eventId }) { row ->
            HistoryRow(
                row = row,
                paused = row.providerId in pausedProviders,
                menuExpanded = menuForEvent == row.eventId,
                onMenuChange = { open -> menuForEvent = if (open) row.eventId else null },
                onRemove = { confirmingRemove = row },
                onEdit = { editingRow = row },
                onWipe = { confirmingWipe = row },
                onToggleRecording = { onSetRecordingPaused(row.providerId, row.providerId !in pausedProviders) },
            )
        }
        if (content.hasMore) {
            item(key = LOAD_MORE_KEY) {
                LoadMoreFooter(
                    appending = content.appending,
                    appendFailed = content.appendFailed,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
    confirmingRemove?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmingRemove = null },
            title = { Text(stringResource(R.string.history_remove_play_title)) },
            text = { Text(stringResource(R.string.history_remove_play_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = null
                    onRemovePlay(row.eventId)
                }) {
                    Text(stringResource(R.string.history_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemove = null }) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }
    confirmingWipe?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmingWipe = null },
            title = { Text(stringResource(R.string.history_clear_provider_title)) },
            text = { Text(stringResource(R.string.history_clear_provider_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingWipe = null
                    onWipeProvider(row.providerId)
                }) {
                    Text(stringResource(R.string.history_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingWipe = null }) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }
    editingRow?.let { row ->
        var countText by remember { mutableStateOf(row.playCount.toString()) }
        AlertDialog(
            onDismissRequest = { editingRow = null },
            title = { Text(stringResource(R.string.history_edit_play_count_title)) },
            text = {
                Box(Modifier.width(240.dp)) {
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { countText = it.filter(Char::isDigit) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingRow = null
                    countText.toIntOrNull()?.let { onEditPlayCount(row.providerId, row.externalId, it) }
                }) {
                    Text(stringResource(R.string.history_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingRow = null }) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }
}

@Composable
private fun HistoryRow(
    row: RecentPlayRow,
    paused: Boolean,
    menuExpanded: Boolean,
    onMenuChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    onWipe: () -> Unit,
    onToggleRecording: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = tokens.layout.listRowMinHeight)
            .padding(vertical = tokens.spacing.sm),
    ) {
        CoverImage(
            cover = row.imagePath?.let { CoverImageKey(row.providerId, it) },
            contentDescription = null,
            modifier = Modifier.size(tokens.imagery.listThumbSize),
        )
        Spacer(Modifier.width(tokens.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.songTitle ?: row.externalId,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = metaLine(
                listOf(
                    row.takeIf { it.skipped }?.let { stringResource(R.string.history_skipped) },
                    row.displayArtist,
                    row.albumTitle,
                ),
            )
            subtitle?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(tokens.spacing.sm))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatPlayedAt(row.playedAtMs),
                style = tokens.typography.caption.toTextStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = formatDuration(row.durationMs),
                style = tokens.typography.caption.toTextStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.End,
            )
        }
        Box {
            IconButton(onClick = { onMenuChange(true) }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(
                        R.string.history_row_actions,
                        row.songTitle ?: row.externalId,
                    ),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { onMenuChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.history_remove_play)) },
                    onClick = { onMenuChange(false); onRemove() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.history_edit_play_count)) },
                    onClick = { onMenuChange(false); onEdit() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.history_clear_provider)) },
                    onClick = { onMenuChange(false); onWipe() },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (paused) {
                                stringResource(R.string.history_resume_recording)
                            } else {
                                stringResource(R.string.history_pause_recording)
                            },
                        )
                    },
                    onClick = { onMenuChange(false); onToggleRecording() },
                )
            }
        }
    }
}
