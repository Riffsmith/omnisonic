package app.omnisonic.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.feature.settings.R
import app.omnisonic.feature.settings.downloads.DownloadSettingsSection
import app.omnisonic.feature.settings.downloads.DownloadSettingsViewModel
import app.omnisonic.feature.settings.history.HistorySettingsSection
import app.omnisonic.feature.settings.history.HistorySettingsViewModel
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.ShellNavigationResolver
import app.omnisonic.feature.settings.shell.ShellPreferences
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.feature.settings.sync.LibrarySyncManagerSection
import app.omnisonic.feature.settings.sync.LibrarySyncManagerViewModel
import app.omnisonic.feature.settings.sync.PendingSyncSection
import app.omnisonic.feature.settings.sync.PendingSyncViewModel
import app.omnisonic.style.NavigationLayout
import app.omnisonic.style.OmnisonicTheme

/**
 * Minimal shell settings (UI_SPEC.md §3.7): appearance (nav layout per
 * orientation, tab visibility/order), providers group, about row. Everything
 * else lands with its phase.
 */
@Composable
fun ShellSettingsScreen(
    appVersion: String,
    onOpenProviders: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    downloadSettingsViewModel: DownloadSettingsViewModel? = null,
    syncViewModel: LibrarySyncManagerViewModel,
    pendingSyncViewModel: PendingSyncViewModel,
    viewModel: ShellSettingsViewModel = hiltViewModel(),
    historySettingsViewModel: HistorySettingsViewModel,
    onShareLogs: () -> Unit = {},
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val current = prefs
    if (current == null) {
        LoadingPane()
        return
    }
    ShellSettingsContent(
        prefs = current,
        appVersion = appVersion,
        onSetTabVisible = viewModel::setTabVisible,
        onMoveTab = viewModel::moveTab,
        onSetNavOverride = viewModel::setNavOverride,
        onOpenProviders = onOpenProviders,
        syncViewModel = syncViewModel,
        pendingSyncViewModel = pendingSyncViewModel,
        historySettingsViewModel = historySettingsViewModel,
        onOpenDownloads = onOpenDownloads,
        downloadSettingsViewModel = downloadSettingsViewModel,
        onShareLogs = onShareLogs,
        onSetNetworkLogging = viewModel::setNetworkLogging,
    )
}

@Composable
private fun ShellSettingsContent(
    prefs: ShellPreferences,
    appVersion: String,
    onSetTabVisible: (List<MainTab>, MainTab, Boolean) -> Unit,
    onMoveTab: (List<MainTab>, MainTab, Int) -> Unit,
    onSetNavOverride: (Boolean, NavigationLayout?) -> Unit,
    onOpenProviders: () -> Unit,
    syncViewModel: LibrarySyncManagerViewModel,
    pendingSyncViewModel: PendingSyncViewModel,
    historySettingsViewModel: HistorySettingsViewModel,
    onOpenDownloads: () -> Unit,
    downloadSettingsViewModel: DownloadSettingsViewModel?,
    onShareLogs: () -> Unit,
    onSetNetworkLogging: (Boolean) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val spacing = tokens.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        SectionHeader(stringResource(R.string.settings_group_appearance))
        NavLayoutSetting(
            title = stringResource(R.string.settings_nav_portrait),
            isLandscape = false,
            prefs = prefs,
            onSetNavOverride = onSetNavOverride,
        )
        NavLayoutSetting(
            title = stringResource(R.string.settings_nav_landscape),
            isLandscape = true,
            prefs = prefs,
            onSetNavOverride = onSetNavOverride,
        )
        Text(
            text = stringResource(R.string.settings_group_tabs),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.sm),
        )
        MainTab.entries.forEach { tab ->
            val visible = tab in prefs.visibleTabs
            val orderIndex = prefs.visibleTabs.indexOf(tab)
            TabRow(
                tab = tab,
                visible = visible,
                canHide = prefs.visibleTabs.size > 1,
                canMoveUp = visible && orderIndex > 0,
                canMoveDown = visible && orderIndex < prefs.visibleTabs.lastIndex,
                onSetVisible = { onSetTabVisible(prefs.visibleTabs, tab, it) },
                onMoveUp = { onMoveTab(prefs.visibleTabs, tab, -1) },
                onMoveDown = { onMoveTab(prefs.visibleTabs, tab, +1) },
            )
        }
        SectionHeader(stringResource(R.string.settings_group_providers))
        TextButton(onClick = onOpenProviders) {
            Text(stringResource(R.string.settings_manage_providers))
        }
        LibrarySyncManagerSection(viewModel = syncViewModel)
        PendingSyncSection(viewModel = pendingSyncViewModel)
        if (downloadSettingsViewModel != null) {
            DownloadSettingsSection(
                viewModel = downloadSettingsViewModel,
                onOpenDownloads = onOpenDownloads,
            )
        } else {
            SectionHeader(stringResource(R.string.settings_group_downloads))
            TextButton(onClick = onOpenDownloads) {
                Text(stringResource(R.string.settings_open_downloads))
            }
        }
        HistorySettingsSection(viewModel = historySettingsViewModel)
        SectionHeader(stringResource(R.string.settings_group_playback))
        NotificationSettingsRow()
        SectionHeader(stringResource(R.string.settings_group_about))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.settings_version), style = MaterialTheme.typography.bodyMedium)
            Text(appVersion, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onShareLogs) {
            Text(stringResource(R.string.settings_share_logs))
        }
        NetworkLoggingRow(
            enabled = prefs.networkLoggingEnabled,
            onSetEnabled = onSetNetworkLogging,
        )
    }
}

/**
 * Opt-in HTTP logging. The summary states the privacy consequence next to the
 * switch, because the log file is shareable from the row above.
 */
@Composable
private fun NetworkLoggingRow(
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_network_logging),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.settings_network_logging_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onSetEnabled)
    }
}

/**
 * Opens the system notification screen instead of holding an in-app switch:
 * media-session notifications are exempt from `POST_NOTIFICATIONS`, so the
 * platform screen is the one place that actually hides them.
 */
@Composable
private fun NotificationSettingsRow() {
    val context = LocalContext.current
    TextButton(
        onClick = {
            context.startActivity(NotificationSettingsIntent.create(context.packageName))
        },
    ) {
        Text(stringResource(R.string.settings_notification_settings))
    }
}

@Composable
private fun NavLayoutSetting(
    title: String,
    isLandscape: Boolean,
    prefs: ShellPreferences,
    onSetNavOverride: (Boolean, NavigationLayout?) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val resolved = ShellNavigationResolver.resolve(
        tokens.layout,
        prefs.portraitNavOverride,
        prefs.landscapeNavOverride,
        isLandscape,
    )
    Column(modifier = Modifier.padding(vertical = tokens.spacing.xxs)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
            FilterChip(
                selected = resolved == NavigationLayout.BOTTOM_BAR,
                onClick = { onSetNavOverride(isLandscape, NavigationLayout.BOTTOM_BAR) },
                label = { Text(stringResource(R.string.settings_nav_bottom_bar)) },
            )
            FilterChip(
                selected = resolved == NavigationLayout.RAIL,
                onClick = { onSetNavOverride(isLandscape, NavigationLayout.RAIL) },
                label = { Text(stringResource(R.string.settings_nav_rail)) },
            )
        }
    }
}

@Composable
private fun TabRow(
    tab: MainTab,
    visible: Boolean,
    canHide: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSetVisible: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = visible,
            onCheckedChange = onSetVisible,
            enabled = canHide || !visible,
        )
        Text(tab.label(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.settings_tab_move_up))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.settings_tab_move_down))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    val tokens = OmnisonicTheme.tokens
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = tokens.spacing.md, bottom = tokens.spacing.xxs),
    )
    HorizontalDivider()
}

@Composable
private fun LoadingPane() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}
