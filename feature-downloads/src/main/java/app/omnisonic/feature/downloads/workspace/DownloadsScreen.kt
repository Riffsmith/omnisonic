package app.omnisonic.feature.downloads.workspace

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.downloads.AutomaticRuleKind
import app.omnisonic.core.data.downloads.DownloadDestination
import app.omnisonic.core.data.downloads.DownloadLifecycleRules
import app.omnisonic.feature.downloads.R
import app.omnisonic.style.OmnisonicTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun DownloadsRoute(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DownloadsScreen(
        state = state,
        onBack = onBack,
        onSelectTab = viewModel::selectTab,
        onPauseAll = viewModel::pauseAll,
        onResumeAll = viewModel::resumeAll,
        onRetryAll = viewModel::retryAll,
        onPauseItem = viewModel::pauseItem,
        onResumeItem = viewModel::resumeItem,
        onRetryItem = viewModel::retryItem,
        onCancelItem = viewModel::cancelItem,
        onDeleteOffline = viewModel::deleteOffline,
        onSetPinned = viewModel::setOfflinePermanent,
        onAddRule = viewModel::addRule,
        onRemoveRule = viewModel::removeRule,
        onLoadRuleCandidates = viewModel::loadRuleCandidates,
        onSetPreferredDestination = viewModel::setPreferredDestination,
        onDismissNotice = viewModel::dismissNotice,
        onReload = viewModel::reload,
        modifier = modifier,
    )
}

/**
 * One `downloads` destination with Queue / Offline / Automatic rules tabs
 * (task 9a/9b). Destructive actions are explicit buttons followed by a
 * confirmation dialog; there is no gesture-only path. Global pause/resume only
 * sends the gate command — sticky item pauses stay item commands, and the
 * persisted gate is rendered from settings rather than derived from row status.
 */
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    onBack: () -> Unit,
    onSelectTab: (DownloadsTab) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onRetryAll: () -> Unit,
    onPauseItem: (Long) -> Unit,
    onResumeItem: (Long) -> Unit,
    onRetryItem: (Long) -> Unit,
    onCancelItem: (Long) -> Unit,
    onDeleteOffline: (Long) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onAddRule: (Long, AutomaticRuleKind, String) -> Unit,
    onRemoveRule: (Long) -> Unit,
    onLoadRuleCandidates: () -> Unit,
    onSetPreferredDestination: (DownloadDestination) -> Unit,
    onDismissNotice: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    val snackbar = remember { SnackbarHostState() }
    var confirmation by remember { mutableStateOf<DownloadsConfirmation?>(null) }
    var picker by remember { mutableStateOf<RulePicker?>(null) }
    val resolvedNotice = state.notice?.let { stringResource(it.text()) }
    LaunchedEffect(state.notice, resolvedNotice) {
        if (state.notice != null && resolvedNotice != null) {
            snackbar.showSnackbar(resolvedNotice)
            onDismissNotice()
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                DownloadsHeader(
                    state = state,
                    onBack = onBack,
                    onPauseAll = onPauseAll,
                    onResumeAll = onResumeAll,
                    onRetryAll = onRetryAll,
                )
                TabRow(selectedTabIndex = state.tab.ordinal) {
                    DownloadsTab.entries.forEach { tab ->
                        Tab(
                            selected = state.tab == tab,
                            onClick = { onSelectTab(tab) },
                            text = { Text(stringResource(tab.label())) },
                        )
                    }
                }
                when {
                    state.loading -> DownloadsLoading()
                    state.failed -> DownloadsFailed(onReload)
                    state.tab == DownloadsTab.QUEUE -> QueueTab(
                        state = state,
                        onPauseItem = onPauseItem,
                        onResumeItem = onResumeItem,
                        onRetryItem = onRetryItem,
                        onCancelRequested = { row, title ->
                            confirmation = DownloadsConfirmation.CancelQueue(row.id, title)
                        },
                    )
                    state.tab == DownloadsTab.OFFLINE -> OfflineTab(
                        state = state,
                        onSetPinned = onSetPinned,
                        onDeleteRequested = { row, title ->
                            confirmation = DownloadsConfirmation.DeleteOffline(row.id, title)
                        },
                    )
                    else -> RulesTab(
                        state = state,
                        onRemoveRequested = { row, title ->
                            confirmation = DownloadsConfirmation.RemoveRule(row.id, title)
                        },
                        onAddFavorites = { providerId ->
                            onAddRule(providerId, AutomaticRuleKind.FAVORITES, "")
                        },
                        onPickTarget = { providerId, kind ->
                            picker = RulePicker(providerId, kind)
                            onLoadRuleCandidates()
                        },
                        onSetPreferredDestination = onSetPreferredDestination,
                    )
                }
            }
        }
    }

    confirmation?.let { pending ->
        DownloadsConfirmationDialog(
            confirmation = pending,
            onDismiss = { confirmation = null },
            onConfirm = {
                when (pending) {
                    is DownloadsConfirmation.CancelQueue -> onCancelItem(pending.rowId)
                    is DownloadsConfirmation.DeleteOffline -> onDeleteOffline(pending.rowId)
                    is DownloadsConfirmation.RemoveRule -> onRemoveRule(pending.rowId)
                }
                confirmation = null
            },
        )
    }

    picker?.let { active ->
        RuleTargetPicker(
            picker = active,
            state = state.candidates,
            providerName = state.providerNames[active.providerId],
            onDismiss = { picker = null },
            onRetry = onLoadRuleCandidates,
            onPick = { target ->
                onAddRule(active.providerId, active.kind, target.externalId)
                picker = null
            },
        )
    }
}

@Composable
private fun DownloadsHeader(
    state: DownloadsUiState,
    onBack: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onRetryAll: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = tokens.layout.listRowMinHeight)
            .padding(horizontal = tokens.spacing.sm),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.downloads_back))
        }
        Text(
            text = stringResource(R.string.downloads_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = tokens.spacing.xs),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.failedCount > 0) {
            TextButton(onClick = onRetryAll, enabled = !state.busyGlobal) {
                Text(stringResource(R.string.downloads_retry_all))
            }
        }
        TextButton(
            onClick = if (state.globallyPaused) onResumeAll else onPauseAll,
            enabled = !state.busyGlobal,
        ) {
            Text(
                stringResource(
                    if (state.globallyPaused) {
                        R.string.downloads_resume_all
                    } else {
                        R.string.downloads_pause_all
                    },
                ),
            )
        }
    }
}

@Composable
private fun QueueTab(
    state: DownloadsUiState,
    onPauseItem: (Long) -> Unit,
    onResumeItem: (Long) -> Unit,
    onRetryItem: (Long) -> Unit,
    onCancelRequested: (DownloadQueueRowUi, String) -> Unit,
) {
    if (state.queue.isEmpty()) {
        DownloadsEmpty(
            title = stringResource(R.string.downloads_queue_empty_title),
            body = stringResource(R.string.downloads_queue_empty_body),
        )
        return
    }
    val tokens = OmnisonicTheme.tokens
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = tokens.layout.pagePadding,
            vertical = tokens.spacing.sm,
        ),
    ) {
        items(state.queue, key = { it.id }) { row ->
            QueueRow(
                row = row,
                busy = row.id in state.busyRowIds,
                onPause = { onPauseItem(row.id) },
                onResume = { onResumeItem(row.id) },
                onRetry = { onRetryItem(row.id) },
                onCancel = { title -> onCancelRequested(row, title) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun QueueRow(
    row: DownloadQueueRowUi,
    busy: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: (String) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val title = row.displayTitle()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.providerName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (row.actions.canPause) {
                IconButton(onClick = onPause, enabled = !busy) {
                    Icon(
                        Icons.Filled.Pause,
                        stringResource(R.string.downloads_pause_item, title),
                    )
                }
            }
            if (row.actions.canResume) {
                IconButton(onClick = onResume, enabled = !busy) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        stringResource(R.string.downloads_resume_item, title),
                    )
                }
            }
            if (row.actions.canRetry) {
                IconButton(onClick = onRetry, enabled = !busy) {
                    Icon(
                        Icons.Filled.Refresh,
                        stringResource(R.string.downloads_retry_item, title),
                    )
                }
            }
            if (row.actions.canCancel) {
                IconButton(onClick = { onCancel(title) }, enabled = !busy) {
                    Icon(
                        Icons.Filled.Close,
                        stringResource(R.string.downloads_cancel_item, title),
                    )
                }
            }
        }
        Text(
            text = row.statusText(),
            style = MaterialTheme.typography.bodySmall,
            color = if (row.status == DownloadStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (row.status == DownloadStatus.ACTIVE) {
            if (row.indeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { row.progressPercent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.downloads_progress_percent,
                        row.progressPercent.coerceIn(0, 100),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        row.lastError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OfflineTab(
    state: DownloadsUiState,
    onSetPinned: (Long, Boolean) -> Unit,
    onDeleteRequested: (DownloadOfflineRowUi, String) -> Unit,
) {
    if (state.offline.isEmpty()) {
        DownloadsEmpty(
            title = stringResource(R.string.downloads_offline_empty_title),
            body = stringResource(R.string.downloads_offline_empty_body),
        )
        return
    }
    val context = LocalContext.current
    val tokens = OmnisonicTheme.tokens
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = tokens.layout.pagePadding,
            vertical = tokens.spacing.sm,
        ),
    ) {
        items(state.offline, key = { it.id }) { row ->
            val title = row.displayTitle()
            val size = row.sizeBytes?.takeIf { it >= 0L }
                ?.let { Formatter.formatShortFileSize(context, it) }
            val date = row.downloadedAtMs?.let {
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
            }
            val meta = listOfNotNull(size, date).joinToString(" · ")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = tokens.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        row.providerName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(
                        onClick = { onDeleteRequested(row, title) },
                        enabled = row.id !in state.busyRowIds,
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            stringResource(R.string.downloads_offline_delete, title),
                        )
                    }
                }
                val permanence = stringResource(
                    if (row.permanent) {
                        R.string.downloads_offline_permanent
                    } else {
                        R.string.downloads_offline_rolling
                    },
                )
                val automatic = if (row.automatic) {
                    " · " + stringResource(R.string.downloads_offline_automatic)
                } else {
                    ""
                }
                val variant = row.bitrateKbps?.let {
                    stringResource(R.string.downloads_offline_variant, it)
                } ?: stringResource(R.string.downloads_offline_variant_auto)
                Text(
                    text = permanence + automatic + " · " + variant,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { onSetPinned(row.id, !row.permanent) },
                    enabled = row.id !in state.busyRowIds,
                ) {
                    Text(
                        stringResource(
                            if (row.permanent) {
                                R.string.downloads_offline_unpin
                            } else {
                                R.string.downloads_offline_pin
                            },
                        ),
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun RulesTab(
    state: DownloadsUiState,
    onRemoveRequested: (DownloadRuleRowUi, String) -> Unit,
    onAddFavorites: (Long) -> Unit,
    onPickTarget: (Long, AutomaticRuleKind) -> Unit,
    onSetPreferredDestination: (DownloadDestination) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = tokens.layout.pagePadding,
            vertical = tokens.spacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.downloads_default_action),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onSetPreferredDestination(DownloadDestination.OFFLINE) },
                    enabled = state.preferredDestination != DownloadDestination.OFFLINE,
                ) {
                    Text(stringResource(R.string.downloads_default_offline))
                }
                TextButton(
                    onClick = { onSetPreferredDestination(DownloadDestination.EXPORT) },
                    enabled = state.preferredDestination != DownloadDestination.EXPORT,
                ) {
                    Text(stringResource(R.string.downloads_default_export))
                }
            }
            HorizontalDivider()
        }
        if (state.rules.isEmpty()) {
            item {
                DownloadsMessage(
                    title = stringResource(R.string.downloads_rules_empty_title),
                    body = stringResource(R.string.downloads_rules_empty_body),
                )
            }
        } else {
            items(state.rules, key = { it.id }) { rule ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rule.displayTitle(),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOfNotNull(rule.providerName, rule.kindLabel())
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val title = rule.displayTitle()
                    IconButton(
                        onClick = { onRemoveRequested(rule, title) },
                        enabled = rule.id !in state.busyRowIds,
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            stringResource(R.string.downloads_rule_remove, title),
                        )
                    }
                }
                HorizontalDivider()
            }
        }
        state.providerNames.forEach { (providerId, name) ->
            item(key = "provider-$providerId") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = tokens.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs),
                ) {
                    Text(
                        text = stringResource(R.string.downloads_provider_section, name),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val favoritesExists = state.rules.any {
                        it.providerId == providerId && it.kind == AutomaticRuleKind.FAVORITES
                    }
                    Button(
                        onClick = { onAddFavorites(providerId) },
                        enabled = !favoritesExists && !state.busyGlobal,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(
                            text = stringResource(
                                if (favoritesExists) {
                                    R.string.downloads_rule_existing
                                } else {
                                    R.string.downloads_rule_add_favorites
                                },
                            ),
                        )
                    }
                    TextButton(onClick = { onPickTarget(providerId, AutomaticRuleKind.PLAYLIST) }) {
                        Text(stringResource(R.string.downloads_rule_add_playlist))
                    }
                    TextButton(onClick = { onPickTarget(providerId, AutomaticRuleKind.ALBUM) }) {
                        Text(stringResource(R.string.downloads_rule_add_album))
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsConfirmationDialog(
    confirmation: DownloadsConfirmation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (title, body, confirm, dismiss) = when (confirmation) {
        is DownloadsConfirmation.CancelQueue -> ConfirmationCopy(
            title = stringResource(R.string.downloads_cancel_title),
            body = stringResource(R.string.downloads_cancel_body, confirmation.title),
            confirm = stringResource(R.string.downloads_cancel_confirm),
            dismiss = stringResource(R.string.downloads_keep),
        )
        is DownloadsConfirmation.DeleteOffline -> ConfirmationCopy(
            title = stringResource(R.string.downloads_offline_delete_title),
            body = stringResource(R.string.downloads_offline_delete_body, confirmation.title),
            confirm = stringResource(R.string.downloads_offline_delete_confirm),
            dismiss = stringResource(R.string.downloads_rule_cancel),
        )
        is DownloadsConfirmation.RemoveRule -> ConfirmationCopy(
            title = stringResource(R.string.downloads_rule_remove_title),
            body = stringResource(R.string.downloads_rule_remove_body),
            confirm = stringResource(R.string.downloads_rule_remove_confirm),
            dismiss = stringResource(R.string.downloads_rule_cancel),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismiss) }
        },
    )
}

@Composable
private fun RuleTargetPicker(
    picker: RulePicker,
    state: DownloadsRuleCandidatesState,
    providerName: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPick: (DownloadRuleCandidateUi) -> Unit,
) {
    val options = when (picker.kind) {
        AutomaticRuleKind.PLAYLIST -> state.candidates?.playlists.orEmpty()
        AutomaticRuleKind.ALBUM -> state.candidates?.albums.orEmpty()
        AutomaticRuleKind.FAVORITES -> emptyList()
    }.filter { it.providerId == picker.providerId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (picker.kind == AutomaticRuleKind.PLAYLIST) {
                        R.string.downloads_rule_pick_playlist
                    } else {
                        R.string.downloads_rule_pick_album
                    },
                ),
            )
        },
        text = {
            Column {
                providerName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    state.loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(OmnisonicTheme.tokens.spacing.md),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    state.failed -> Column {
                        Text(stringResource(R.string.downloads_rule_candidates_failed))
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.downloads_rule_candidates_retry))
                        }
                    }
                    options.isEmpty() -> Text(
                        stringResource(R.string.downloads_rule_candidates_empty),
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = PICKER_MAX_HEIGHT)) {
                        items(options, key = { "${it.providerId}:${it.externalId}" }) { target ->
                            Text(
                                text = target.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(target) }
                                    .padding(vertical = OmnisonicTheme.tokens.spacing.sm),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.downloads_rule_cancel))
            }
        },
    )
}

/** In-list empty copy; unlike [DownloadsEmpty] it does not claim the viewport. */
@Composable
private fun DownloadsMessage(title: String, body: String) {
    val tokens = OmnisonicTheme.tokens
    Column(
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.xxs),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.md),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadsEmpty(title: String, body: String) {
    val tokens = OmnisonicTheme.tokens
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            modifier = Modifier.padding(horizontal = tokens.layout.pagePadding),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadsLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun DownloadsFailed(onReload: () -> Unit) {
    val tokens = OmnisonicTheme.tokens
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
        ) {
            Text(stringResource(R.string.downloads_failed))
            TextButton(onClick = onReload) {
                Text(stringResource(R.string.downloads_rule_candidates_retry))
            }
        }
    }
}

private fun DownloadsTab.label(): Int = when (this) {
    DownloadsTab.QUEUE -> R.string.downloads_tab_queue
    DownloadsTab.OFFLINE -> R.string.downloads_tab_offline
    DownloadsTab.RULES -> R.string.downloads_tab_rules
}

private fun DownloadsNotice.text(): Int = when (this) {
    DownloadsNotice.OFFLINE_DELETED -> R.string.downloads_notice_offline_deleted
    DownloadsNotice.OFFLINE_DELETE_FAILED -> R.string.downloads_notice_offline_delete_failed
    DownloadsNotice.OWNERSHIP_FAILED -> R.string.downloads_notice_ownership_failed
    DownloadsNotice.RULE_ADDED -> R.string.downloads_notice_rule_added
    DownloadsNotice.RULE_REMOVED -> R.string.downloads_notice_rule_removed
    DownloadsNotice.RULE_FAILED -> R.string.downloads_notice_rule_failed
}

@Composable
private fun DownloadQueueRowUi.displayTitle(): String =
    title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.downloads_untitled)

@Composable
private fun DownloadOfflineRowUi.displayTitle(): String =
    title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.downloads_untitled)

@Composable
private fun DownloadRuleRowUi.displayTitle(): String = targetLabel ?: when (kind) {
    AutomaticRuleKind.FAVORITES -> stringResource(R.string.downloads_rule_kind_favorites)
    AutomaticRuleKind.PLAYLIST -> targetExternalId
    AutomaticRuleKind.ALBUM -> targetExternalId
}

@Composable
private fun DownloadRuleRowUi.kindLabel(): String = when (kind) {
    AutomaticRuleKind.FAVORITES -> stringResource(R.string.downloads_rule_kind_favorites)
    AutomaticRuleKind.PLAYLIST -> stringResource(R.string.downloads_rule_kind_playlist)
    AutomaticRuleKind.ALBUM -> stringResource(R.string.downloads_rule_kind_album)
}

@Composable
private fun DownloadQueueRowUi.statusText(): String {
    val base = when (status) {
        DownloadStatus.PENDING -> stringResource(R.string.downloads_status_pending)
        DownloadStatus.PAUSED -> stringResource(R.string.downloads_status_paused)
        DownloadStatus.USER_PAUSED -> stringResource(R.string.downloads_status_user_paused)
        DownloadStatus.ACTIVE -> stringResource(R.string.downloads_status_active)
        DownloadStatus.FAILED -> if (parked) {
            stringResource(
                R.string.downloads_attempt_parked,
                DownloadLifecycleRules.MAX_COUNTED_FAILURES,
            )
        } else {
            stringResource(R.string.downloads_status_failed)
        }
        null -> stringResource(R.string.downloads_status_unknown)
    }
    val attempts = if (errorCount in 1 until DownloadLifecycleRules.MAX_COUNTED_FAILURES && !parked) {
        stringResource(
            R.string.downloads_attempt_count,
            errorCount,
            DownloadLifecycleRules.MAX_COUNTED_FAILURES,
        )
    } else {
        null
    }
    return listOfNotNull(base, attempts).joinToString(" · ")
}

private sealed interface DownloadsConfirmation {
    val rowId: Long
    val title: String

    data class CancelQueue(override val rowId: Long, override val title: String) : DownloadsConfirmation
    data class DeleteOffline(override val rowId: Long, override val title: String) : DownloadsConfirmation
    data class RemoveRule(override val rowId: Long, override val title: String) : DownloadsConfirmation
}

private data class ConfirmationCopy(
    val title: String,
    val body: String,
    val confirm: String,
    val dismiss: String,
)

private data class RulePicker(
    val providerId: Long,
    val kind: AutomaticRuleKind,
)

private val PICKER_MAX_HEIGHT = 320.dp
