package app.omnisonic.feature.settings.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.db.aggregation.AlbumCountRule
import app.omnisonic.core.data.history.HistoryPreferencesStore
import app.omnisonic.feature.settings.R
import app.omnisonic.style.OmnisonicTheme
import kotlin.math.roundToInt

private const val CSV_EXPORT_FILE_NAME = "omnisonic-history.csv"

/**
 * Listen-history settings block in the shell settings screen (Phase 6 task 9):
 * the play-count threshold, the per-provider recording pause, the minimum
 * resume position, the aggregate options and the CSV export entry point. The
 * export goes through the system `CreateDocument` picker, then writes through
 * the same SAF document seam as the backup ZIP.
 */
@Composable
fun HistorySettingsSection(viewModel: HistorySettingsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) viewModel.exportCsv(uri)
    }
    HistorySettingsContent(
        state = state,
        export = export,
        onThresholdChange = viewModel::setPlayThresholdPercent,
        onResumeMinChange = viewModel::setResumeMinPositionMs,
        onProviderPausedChange = viewModel::setProviderRecordingPaused,
        onAlbumCountRuleChange = viewModel::setAlbumCountRule,
        onIncludeSupportingChange = viewModel::setIncludeSupportingArtists,
        onExportCsv = { launcher.launch(CSV_EXPORT_FILE_NAME) },
    )
}

@Composable
internal fun HistorySettingsContent(
    state: HistorySettingsUiState,
    export: HistoryCsvExportState,
    onThresholdChange: (Int) -> Unit,
    onResumeMinChange: (Long) -> Unit,
    onProviderPausedChange: (Long, Boolean) -> Unit,
    onAlbumCountRuleChange: (AlbumCountRule) -> Unit,
    onIncludeSupportingChange: (Boolean) -> Unit,
    onExportCsv: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs),
    ) {
        Text(
            text = stringResource(R.string.settings_group_history),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = tokens.spacing.sm),
        )
        ThresholdRow(
            percent = state.playThresholdPercent,
            onThresholdChange = onThresholdChange,
        )
        ResumeMinRow(
            minPositionMs = state.resumeMinPositionMs,
            onResumeMinChange = onResumeMinChange,
        )
        if (state.providers.isNotEmpty()) {
            Text(
                text = stringResource(R.string.history_recording),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.providers.forEach { provider ->
                ProviderRecordingRow(
                    name = provider.name,
                    recording = provider.id !in state.pausedProviderIds,
                    onPausedChange = { paused -> onProviderPausedChange(provider.id, paused) },
                )
            }
        }
        AlbumRuleRow(
            rule = state.albumCountRule,
            onRuleChange = onAlbumCountRuleChange,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = tokens.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.history_supporting_artists),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.includeSupportingArtists,
                onCheckedChange = onIncludeSupportingChange,
            )
        }
        TextButton(onClick = onExportCsv) {
            Text(stringResource(R.string.history_export_csv))
        }
        when (export) {
            HistoryCsvExportState.Idle -> {}
            HistoryCsvExportState.Running -> Text(
                text = stringResource(R.string.history_export_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is HistoryCsvExportState.Done -> Text(
                text = pluralStringResource(
                    R.plurals.history_export_count,
                    export.eventCount,
                    export.eventCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is HistoryCsvExportState.Failed -> Text(
                text = stringResource(R.string.history_export_failed, export.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ThresholdRow(
    percent: Int,
    onThresholdChange: (Int) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.history_threshold),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.history_threshold_value, percent),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onThresholdChange(it.roundToInt()) },
            valueRange = HistoryPreferencesStore.MIN_PLAY_THRESHOLD_PERCENT.toFloat()..
                HistoryPreferencesStore.MAX_PLAY_THRESHOLD_PERCENT.toFloat(),
            steps = HistoryPreferencesStore.MAX_PLAY_THRESHOLD_PERCENT - 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ResumeMinRow(
    minPositionMs: Long,
    onResumeMinChange: (Long) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val seconds = (minPositionMs / 1_000L).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.history_resume_min),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.history_resume_min_value, seconds),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = seconds.toFloat(),
            onValueChange = { onResumeMinChange(it.roundToInt() * 1_000L) },
            valueRange = 0f..(HistoryPreferencesStore.MAX_RESUME_MIN_POSITION_MS / 1_000L).toFloat(),
            steps = (HistoryPreferencesStore.MAX_RESUME_MIN_POSITION_MS / 5_000L).toInt() - 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProviderRecordingRow(
    name: String,
    recording: Boolean,
    onPausedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = recording,
            onCheckedChange = { enabled -> onPausedChange(!enabled) },
            modifier = Modifier.semantics {
                contentDescription = context.getString(R.string.history_recording_for, name)
            },
        )
    }
}

@Composable
private fun AlbumRuleRow(
    rule: AlbumCountRule,
    onRuleChange: (AlbumCountRule) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs)) {
        Text(
            text = stringResource(R.string.history_album_rule),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
            AlbumCountRule.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == rule,
                    onClick = { onRuleChange(candidate) },
                    label = {
                        Text(
                            stringResource(
                                when (candidate) {
                                    AlbumCountRule.SUM -> R.string.history_rule_sum
                                    AlbumCountRule.AVERAGE -> R.string.history_rule_average
                                    AlbumCountRule.MINIMUM -> R.string.history_rule_minimum
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}
