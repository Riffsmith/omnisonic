package app.omnisonic.feature.settings.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.sync.OutboxPendingProvider
import app.omnisonic.feature.settings.R
import app.omnisonic.style.OmnisonicTheme

/**
 * Pending user-data changes that have not reached the server yet: the total,
 * one row per provider with its newest error, and a manual retry. The run gate
 * still decides whether a retry actually sends anything.
 */
@Composable
fun PendingSyncSection(viewModel: PendingSyncViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PendingSyncContent(state = state, onRetry = viewModel::retryNow)
}

@Composable
internal fun PendingSyncContent(
    state: PendingSyncUiState,
    onRetry: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs),
    ) {
        Text(
            text = stringResource(R.string.settings_group_pending_sync),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = tokens.spacing.sm),
        )
        if (state.totalPending == 0) {
            Text(
                text = stringResource(R.string.pending_sync_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text(
            text = pluralStringResource(
                R.plurals.pending_sync_count,
                state.totalPending,
                state.totalPending,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        state.providers.forEach { provider ->
            PendingProviderRow(provider)
        }
        TextButton(onClick = onRetry, enabled = !state.draining) {
            Text(
                stringResource(
                    if (state.draining) R.string.pending_sync_retrying else R.string.pending_sync_retry,
                ),
            )
        }
    }
}

@Composable
private fun PendingProviderRow(provider: OutboxPendingProvider) {
    val tokens = OmnisonicTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs)) {
        Text(
            text = provider.providerName
                ?: stringResource(R.string.pending_sync_unknown_provider, provider.providerId),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = pluralStringResource(
                R.plurals.pending_sync_provider_count,
                provider.pendingCount,
                provider.pendingCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (provider.failingCount > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.pending_sync_provider_failed,
                    provider.failingCount,
                    provider.failingCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        provider.lastError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = stringResource(R.string.pending_sync_last_error, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
            )
        }
    }
}
