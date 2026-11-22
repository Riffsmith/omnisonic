package app.omnisonic.feature.settings.transcode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.feature.settings.R
import app.omnisonic.style.OmnisonicTheme

/**
 * The D-60 VPN/metered-as-cellular toggles in the shell settings' playback
 * group (Phase 8 task 6). Each row is a Switch — the button twin of the
 * toggle, with the row title as its accessibility description.
 */
@Composable
fun TranscodeSettingsSection(viewModel: TranscodeSettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TranscodeSettingsContent(
        state = state,
        onVpnAsCellular = viewModel::setVpnAsCellular,
        onMeteredAsCellular = viewModel::setMeteredAsCellular,
    )
}

@Composable
internal fun TranscodeSettingsContent(
    state: TranscodeSettingsUiState,
    onVpnAsCellular: (Boolean) -> Unit,
    onMeteredAsCellular: (Boolean) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs),
    ) {
        TranscodeSwitchRow(
            title = stringResource(R.string.settings_transcode_vpn_as_cellular),
            summary = stringResource(R.string.settings_transcode_vpn_as_cellular_summary),
            checked = state.vpnAsCellular,
            onCheckedChange = onVpnAsCellular,
        )
        TranscodeSwitchRow(
            title = stringResource(R.string.settings_transcode_metered_as_cellular),
            summary = stringResource(R.string.settings_transcode_metered_as_cellular_summary),
            checked = state.meteredAsCellular,
            onCheckedChange = onMeteredAsCellular,
        )
    }
}

@Composable
private fun TranscodeSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
