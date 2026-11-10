package app.omnisonic.feature.settings.sync

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.sync.LibrarySyncLastResult
import app.omnisonic.feature.settings.R
import app.omnisonic.style.OmnisonicTheme

/**
 * Per-provider sync readout and triggers (D-16, UI_SPEC.md §3.7 providers
 * group): last recorded outcome from `sync_state`, the live walk when one is
 * running, "Sync now" (diff) and a confirmed "Full resync".
 */
@Composable
fun LibrarySyncManagerSection(viewModel: LibrarySyncManagerViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    LibrarySyncManagerContent(
        state = state,
        onSyncNow = viewModel::syncNow,
        onRequestFullResync = viewModel::requestFullResync,
        onDismissFullResync = viewModel::dismissFullResync,
        onConfirmFullResync = viewModel::confirmFullResync,
    )
}

@Composable
internal fun LibrarySyncManagerContent(
    state: LibrarySyncManagerUiState,
    onSyncNow: (Long) -> Unit,
    onRequestFullResync: (Long) -> Unit,
    onDismissFullResync: () -> Unit,
    onConfirmFullResync: () -> Unit,
) {
    if (state.entries.isEmpty()) return
    val tokens = OmnisonicTheme.tokens

    Text(
        text = stringResource(R.string.settings_group_library_sync),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = tokens.spacing.sm),
    )
    state.entries.forEach { entry ->
        ProviderSyncRow(
            entry = entry,
            onSyncNow = { onSyncNow(entry.provider.providerId) },
            onFullResync = { onRequestFullResync(entry.provider.providerId) },
        )
    }

    val confirming = state.confirmingProviderId?.let { id ->
        state.entries.firstOrNull { it.provider.providerId == id }
    }
    if (confirming != null) {
        AlertDialog(
            onDismissRequest = onDismissFullResync,
            title = { Text(stringResource(R.string.sync_manager_confirm_title)) },
            text = { Text(stringResource(R.string.sync_manager_confirm_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmFullResync) {
                    Text(stringResource(R.string.sync_manager_confirm_start))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissFullResync) {
                    Text(stringResource(R.string.sync_manager_confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProviderSyncRow(
    entry: LibrarySyncManagerEntry,
    onSyncNow: () -> Unit,
    onFullResync: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val provider = entry.provider
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs),
    ) {
        Text(provider.name, style = MaterialTheme.typography.bodyMedium)
        if (entry.isRunning) {
            val phase = entry.live?.phase
            Text(
                text = phase?.let { stringResource(R.string.sync_manager_running_phase, it.label()) }
                    ?: stringResource(R.string.library_sync_preparing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val progress = entry.live
            if (progress != null && progress.itemsTotal > 0) {
                LinearProgressIndicator(
                    progress = { progress.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.sync_manager_count,
                        progress.itemsDone,
                        progress.itemsTotal,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else {
            Text(
                text = resultLabel(provider.lastResult),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            provider.lastSyncAt?.let { at ->
                Text(
                    text = stringResource(
                        R.string.sync_manager_last_sync,
                        DateUtils.getRelativeTimeSpanString(at),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (provider.lastResult == LibrarySyncLastResult.FAILED) {
                provider.lastMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSyncNow, enabled = !entry.isRunning) {
                Text(stringResource(R.string.sync_manager_sync_now))
            }
            TextButton(onClick = onFullResync, enabled = !entry.isRunning) {
                Text(stringResource(R.string.sync_manager_full_resync))
            }
        }
    }
}

@Composable
private fun resultLabel(result: LibrarySyncLastResult): String = stringResource(
    when (result) {
        LibrarySyncLastResult.NEVER -> R.string.sync_manager_never
        LibrarySyncLastResult.COMPLETED -> R.string.sync_manager_completed
        LibrarySyncLastResult.FAILED -> R.string.sync_manager_failed
    },
)
