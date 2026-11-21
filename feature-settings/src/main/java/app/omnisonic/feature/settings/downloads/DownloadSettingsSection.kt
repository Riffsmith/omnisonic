package app.omnisonic.feature.settings.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.downloads.DownloadSettingsRules
import app.omnisonic.core.data.downloads.OfflineRootChangeFailure
import app.omnisonic.core.data.downloads.OfflineRootSelection
import app.omnisonic.feature.settings.R
import app.omnisonic.style.OmnisonicTheme

/**
 * Downloads settings block (task 9d): parallel worker count, the global
 * Wi-Fi-only gate, storage-root choice, and the confirmed verified move. The
 * section never starts a move itself; the dialog's confirm button is the only
 * path to [DownloadSettingsViewModel.confirmRootChange].
 */
@Composable
fun DownloadSettingsSection(
    viewModel: DownloadSettingsViewModel,
    onOpenDownloads: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DownloadSettingsContent(
        state = state,
        onWorkerCount = viewModel::setWorkerCount,
        onWifiOnly = viewModel::setWifiOnly,
        onSelectRoot = { selection -> viewModel.requestRootChange(selection) },
        onConfirmMove = viewModel::confirmRootChange,
        onCancelMove = viewModel::cancelRootChange,
        onDismissMove = viewModel::dismissMoveResult,
        onOpenDownloads = onOpenDownloads,
    )
}

@Composable
internal fun DownloadSettingsContent(
    state: DownloadSettingsUiState,
    onWorkerCount: (Int) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onSelectRoot: (OfflineRootSelection) -> Unit,
    onConfirmMove: () -> Unit,
    onCancelMove: () -> Unit,
    onDismissMove: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs),
    ) {
        Text(
            text = stringResource(R.string.settings_group_downloads),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = tokens.spacing.sm),
        )
        Text(
            text = stringResource(R.string.settings_downloads_workers),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(
                R.string.settings_downloads_workers_summary,
                DownloadSettingsRules.MIN_WORKER_COUNT,
                DownloadSettingsRules.MAX_WORKER_COUNT,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
            for (count in DownloadSettingsRules.MIN_WORKER_COUNT..DownloadSettingsRules.MAX_WORKER_COUNT) {
                FilterChip(
                    selected = state.workerCount == count,
                    onClick = { onWorkerCount(count) },
                    label = { Text(count.toString()) },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = tokens.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_downloads_wifi),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_downloads_wifi_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.wifiOnly, onCheckedChange = onWifiOnly)
        }
        Text(
            text = stringResource(R.string.settings_downloads_storage),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilterChip(
                selected = state.selection == OfflineRootSelection.Auto,
                onClick = { onSelectRoot(OfflineRootSelection.Auto) },
                label = { Text(stringResource(R.string.settings_downloads_storage_auto)) },
            )
            state.roots.forEach { root ->
                FilterChip(
                    selected = state.selection == OfflineRootSelection.Volume(root.volumeId),
                    onClick = { onSelectRoot(OfflineRootSelection.Volume(root.volumeId)) },
                    label = { Text(stringResource(root.label())) },
                )
            }
        }
        if (state.rootUnavailable) {
            Text(
                text = stringResource(R.string.settings_downloads_storage_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when (val move = state.move) {
            DownloadRootMoveUi.Idle -> Unit
            is DownloadRootMoveUi.Confirming -> Unit
            DownloadRootMoveUi.Running -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(tokens.imagery.uiIconSize))
                Text(
                    text = stringResource(R.string.settings_downloads_move_running),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is DownloadRootMoveUi.Done -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.settings_downloads_move_done, move.movedFiles),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissMove) {
                    Text(stringResource(R.string.settings_downloads_dismiss))
                }
            }
            is DownloadRootMoveUi.Failed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            ) {
                Text(
                    text = stringResource(
                        R.string.settings_downloads_move_failed_reason,
                        stringResource(move.reason.text()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissMove) {
                    Text(stringResource(R.string.settings_downloads_dismiss))
                }
            }
        }
        TextButton(onClick = onOpenDownloads) {
            Text(stringResource(R.string.settings_open_downloads))
        }
    }

    val confirming = state.move as? DownloadRootMoveUi.Confirming
    if (confirming != null) {
        AlertDialog(
            onDismissRequest = onCancelMove,
            title = {
                Text(
                    stringResource(
                        R.string.settings_downloads_move_title,
                        confirming.target.label(state),
                    ),
                )
            },
            text = { Text(stringResource(R.string.settings_downloads_move_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmMove) {
                    Text(stringResource(R.string.settings_downloads_move_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelMove) {
                    Text(stringResource(R.string.settings_downloads_cancel))
                }
            },
        )
    }
}

@Composable
private fun DownloadRootOptionUi.label(): Int =
    if (internal) {
        R.string.settings_downloads_storage_internal
    } else {
        R.string.settings_downloads_storage_external
    }

@Composable
private fun OfflineRootSelection.label(state: DownloadSettingsUiState): String = when (this) {
    OfflineRootSelection.Auto -> stringResource(R.string.settings_downloads_storage_auto)
    is OfflineRootSelection.Volume -> {
        val root = state.roots.firstOrNull { it.volumeId == volumeId }
        when {
            root == null -> volumeId
            root.internal -> stringResource(R.string.settings_downloads_storage_internal)
            else -> stringResource(R.string.settings_downloads_storage_external)
        }
    }
}

private fun OfflineRootChangeFailure.text(): Int = when (this) {
    OfflineRootChangeFailure.MOVE_IN_PROGRESS -> R.string.settings_downloads_root_move_in_progress
    OfflineRootChangeFailure.TARGET_UNAVAILABLE -> R.string.settings_downloads_root_target_unavailable
    OfflineRootChangeFailure.SOURCE_UNAVAILABLE -> R.string.settings_downloads_root_source_unavailable
    OfflineRootChangeFailure.NOT_WRITABLE -> R.string.settings_downloads_root_not_writable
    OfflineRootChangeFailure.LOW_SPACE -> R.string.settings_downloads_root_low_space
    OfflineRootChangeFailure.DESTINATION_CONFLICT -> R.string.settings_downloads_root_conflict
    OfflineRootChangeFailure.INVALID_TREE -> R.string.settings_downloads_root_invalid
    OfflineRootChangeFailure.COPY_FAILED -> R.string.settings_downloads_root_copy_failed
    OfflineRootChangeFailure.COMMIT_FAILED -> R.string.settings_downloads_root_commit_failed
}
