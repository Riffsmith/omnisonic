package app.omnisonic.feature.settings.styleeditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.style.StyleOrigin
import app.omnisonic.feature.settings.R
import app.omnisonic.style.OmnisonicTheme

/**
 * The Styles sub-screen (D-116 Q9, TODO 5b WP02–WP04): every usable style once,
 * by repository order, with the active entry marked, a pick action per row that
 * applies live through the 5a0 coordinator, one duplicate action per row, rename
 * and delete only on user styles, and exactly one preview card for the active
 * style. Damaged user files are surfaced as "needs attention" rows with only the
 * D-97 safe actions — export the raw file, reset to default, delete — and are
 * never pickable. Every action goes through `StyleEditorViewModel` → the
 * committed command seam; the UI performs no repository write and never renders
 * a failed command as applied (`style-editor.md` §6–§8).
 */
@Composable
fun StyleEditorStylesRoute(
    viewModel: StyleEditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.stylesState.collectAsStateWithLifecycle()
    StyleEditorStylesScreen(
        state = state,
        onPick = viewModel::pick,
        onDuplicate = viewModel::duplicate,
        onBeginRename = viewModel::beginRename,
        onRenameDraftChange = viewModel::updateRenameDraft,
        onConfirmRename = viewModel::confirmRename,
        onCancelRename = viewModel::cancelRename,
        onBeginDelete = viewModel::beginDelete,
        onResetDamaged = viewModel::beginResetDamaged,
        onExportRaw = viewModel::exportRaw,
        onDismissRawExport = viewModel::dismissRawExport,
        onConfirmLifecycle = viewModel::confirm,
        onCancelConfirm = viewModel::cancelConfirm,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun StyleEditorStylesScreen(
    state: StyleEditorStylesState,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    onDuplicate: (String) -> Unit = {},
    onBeginRename: (String) -> Unit = {},
    onRenameDraftChange: (String) -> Unit = {},
    onConfirmRename: () -> Unit = {},
    onCancelRename: () -> Unit = {},
    onBeginDelete: (String) -> Unit = {},
    onResetDamaged: (String) -> Unit = {},
    onExportRaw: (String) -> Unit = {},
    onDismissRawExport: () -> Unit = {},
    onConfirmLifecycle: () -> Unit = {},
    onCancelConfirm: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    val spacing = tokens.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.provider_setup_back),
                )
            }
            Text(
                text = stringResource(R.string.style_editor_area_styles),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }
        ActiveStyleCard(state = state)
        if (state.entries.isEmpty()) {
            Text(
                text = stringResource(R.string.style_editor_styles_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }
        state.entries.forEach { entry ->
            when (entry.kind) {
                StyleEditorEntryKind.VALID -> StyleRow(
                    entry = entry,
                    active = entry.id == state.activeId,
                    pending = entry.id == state.pendingStyleId,
                    commandPending = entry.id == state.pendingCommandId,
                    onPick = onPick,
                    onDuplicate = onDuplicate,
                    onRename = onBeginRename,
                    onDelete = onBeginDelete,
                )

                StyleEditorEntryKind.DAMAGED -> DamagedRow(
                    entry = entry,
                    commandPending = entry.id == state.pendingCommandId,
                    onExportRaw = onExportRaw,
                    onResetDamaged = onResetDamaged,
                    onDelete = onBeginDelete,
                )
            }
        }
        state.rawExport?.let { export ->
            RawExportCard(export = export, onDismiss = onDismissRawExport)
        }
        state.failure?.let { failure ->
            SwitchFailure(failure = failure, entries = state.entries)
        }
        state.commandFailure?.let { failure ->
            when (failure.kind) {
                StyleEditorCommandKind.DUPLICATE ->
                    DuplicateFailure(failure = failure, entries = state.entries)

                StyleEditorCommandKind.DELETE,
                StyleEditorCommandKind.RECOVERY_DELETE,
                -> RemoveFailure(
                    failure = failure,
                    entries = state.entries,
                    messageRes = R.string.style_editor_styles_delete_failure,
                )

                StyleEditorCommandKind.RECOVERY_RESET -> RemoveFailure(
                    failure = failure,
                    entries = state.entries,
                    messageRes = R.string.style_editor_styles_reset_failure,
                )

                StyleEditorCommandKind.RECOVERY_EXPORT -> RemoveFailure(
                    failure = failure,
                    entries = state.entries,
                    messageRes = R.string.style_editor_styles_export_failure,
                )

                StyleEditorCommandKind.RENAME -> Unit
            }
        }
    }
    state.confirmTarget?.let { target ->
        ConfirmLifecycleDialog(
            target = target,
            failure = state.commandFailure,
            working = state.pendingCommandId != null,
            onConfirm = onConfirmLifecycle,
            onCancel = onCancelConfirm,
        )
    }
    if (state.renameTargetId != null) {
        RenameDialog(
            state = state,
            onRenameDraftChange = onRenameDraftChange,
            onConfirm = onConfirmRename,
            onCancel = onCancelRename,
        )
    }
}

/**
 * The one preview card (Q9). It reads the state's single preview token set, so
 * it follows the active style and never multiplies per row; its description
 * names the active style for TalkBack. When the resolved default stands in for a
 * damaged selection or a deleted active style, the card says so (WP04) instead
 * of presenting the default as an ordinary choice.
 */
@Composable
private fun ActiveStyleCard(
    state: StyleEditorStylesState,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    val spacing = tokens.spacing
    val name = state.activeStyleName.orEmpty()
    val description = stringResource(R.string.style_editor_preview_a11y, name)
    Surface(
        shape = RoundedCornerShape(tokens.shapes.cornerScale.medium),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.sm)
            .semantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(tokens.shapes.cornerScale.medium),
                color = tokens.colors.primary,
                modifier = Modifier.size(48.dp),
            ) {}
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.style_editor_active_style),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = name, style = MaterialTheme.typography.bodyLarge)
                state.fallbackReason?.let { reason ->
                    Text(
                        text = stringResource(
                            when (reason) {
                                StyleEditorFallbackReason.DAMAGED_SELECTION ->
                                    R.string.style_editor_styles_fallback_damaged

                                StyleEditorFallbackReason.DELETED_SELECTION ->
                                    R.string.style_editor_styles_fallback_deleted
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StyleRow(
    entry: StyleEditorStyleEntry,
    active: Boolean,
    pending: Boolean,
    commandPending: Boolean,
    onPick: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    val spacing = tokens.spacing
    val pickDescription = if (active) {
        stringResource(R.string.style_editor_styles_apply_active_a11y, entry.name)
    } else {
        stringResource(R.string.style_editor_styles_apply_a11y, entry.name)
    }
    val duplicateDescription =
        stringResource(R.string.style_editor_styles_duplicate_a11y, entry.name)
    val renameDescription =
        stringResource(R.string.style_editor_styles_rename_a11y, entry.name)
    val deleteDescription =
        stringResource(R.string.style_editor_styles_delete_a11y, entry.name)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = pickDescription
                    selected = active
                }
                .clickable { onPick(entry.id) }
                .padding(vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
                when {
                    active -> Text(
                        text = stringResource(R.string.style_editor_styles_active),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    pending -> Text(
                        text = stringResource(R.string.style_editor_styles_applying),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    commandPending -> Text(
                        text = stringResource(R.string.style_editor_styles_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (active) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            TextButton(
                onClick = { onDuplicate(entry.id) },
                modifier = Modifier.semantics {
                    role = Role.Button
                    contentDescription = duplicateDescription
                },
            ) {
                Text(stringResource(R.string.style_editor_styles_duplicate))
            }
            if (entry.origin == StyleOrigin.USER) {
                TextButton(
                    onClick = { onRename(entry.id) },
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = renameDescription
                    },
                ) {
                    Text(stringResource(R.string.style_editor_styles_rename))
                }
                TextButton(
                    onClick = { onDelete(entry.id) },
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = deleteDescription
                    },
                ) {
                    Text(stringResource(R.string.style_editor_styles_delete))
                }
            }
        }
    }
}

/**
 * A damaged user file (D-97/F-41): labelled "Needs attention", with the
 * path-qualified reason, no pick action, and only the safe recovery actions.
 */
@Composable
private fun DamagedRow(
    entry: StyleEditorStyleEntry,
    commandPending: Boolean,
    onExportRaw: (String) -> Unit,
    onResetDamaged: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    val spacing = tokens.spacing
    val exportDescription =
        stringResource(R.string.style_editor_styles_export_a11y, entry.name)
    val resetDescription =
        stringResource(R.string.style_editor_styles_reset_a11y, entry.name)
    val deleteDescription =
        stringResource(R.string.style_editor_styles_delete_a11y, entry.name)
    val damagedDescription =
        stringResource(R.string.style_editor_styles_damaged_a11y, entry.name)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = damagedDescription
            },
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.style_editor_styles_needs_attention),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        entry.damageDetail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.style_editor_styles_damaged_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (commandPending) {
            Text(
                text = stringResource(R.string.style_editor_styles_working),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            TextButton(
                onClick = { onExportRaw(entry.id) },
                modifier = Modifier.semantics {
                    role = Role.Button
                    contentDescription = exportDescription
                },
            ) {
                Text(stringResource(R.string.style_editor_styles_export))
            }
            TextButton(
                onClick = { onResetDamaged(entry.id) },
                modifier = Modifier.semantics {
                    role = Role.Button
                    contentDescription = resetDescription
                },
            ) {
                Text(stringResource(R.string.style_editor_styles_reset))
            }
            TextButton(
                onClick = { onDelete(entry.id) },
                modifier = Modifier.semantics {
                    role = Role.Button
                    contentDescription = deleteDescription
                },
            ) {
                Text(stringResource(R.string.style_editor_styles_delete))
            }
        }
    }
}

/** The raw support export, exactly as stored, ready for the caller/share target. */
@Composable
private fun RawExportCard(
    export: StyleEditorRawExport,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = OmnisonicTheme.tokens.spacing
    Column(
        modifier = modifier.padding(vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Text(
            text = stringResource(
                R.string.style_editor_styles_export_ready,
                export.fileName,
                export.bytes.size,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.style_editor_styles_export_ready_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.style_editor_styles_export_dismiss))
        }
    }
}

@Composable
private fun SwitchFailure(
    failure: StyleEditorSwitchFailure,
    entries: List<StyleEditorStyleEntry>,
    modifier: Modifier = Modifier,
) {
    val spacing = OmnisonicTheme.tokens.spacing
    val styleName = entries.firstOrNull { it.id == failure.styleId }?.name ?: failure.styleId
    Column(
        modifier = modifier.padding(top = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Text(
            text = stringResource(
                R.string.style_editor_styles_failure,
                styleName,
                failure.message,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.style_editor_styles_failure_kept),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DuplicateFailure(
    failure: StyleEditorCommandFailure,
    entries: List<StyleEditorStyleEntry>,
    modifier: Modifier = Modifier,
) {
    val spacing = OmnisonicTheme.tokens.spacing
    val styleName = entries.firstOrNull { it.id == failure.styleId }?.name ?: failure.styleId
    Column(
        modifier = modifier.padding(top = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Text(
            text = stringResource(
                R.string.style_editor_styles_duplicate_failure,
                styleName,
                failure.message,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.style_editor_styles_duplicate_failure_kept),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemoveFailure(
    failure: StyleEditorCommandFailure,
    entries: List<StyleEditorStyleEntry>,
    messageRes: Int,
    modifier: Modifier = Modifier,
) {
    val spacing = OmnisonicTheme.tokens.spacing
    val styleName = entries.firstOrNull { it.id == failure.styleId }?.name ?: failure.styleId
    Column(
        modifier = modifier.padding(top = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Text(
            text = stringResource(messageRes, styleName, failure.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * D-97 confirmation in front of a permanent action: explicit permanent-deletion
 * copy for delete, confirm/cancel, and the typed reason in place when the
 * command did not land. It stays open on failure and closes only on success.
 */
@Composable
private fun ConfirmLifecycleDialog(
    target: StyleEditorConfirmTarget,
    failure: StyleEditorCommandFailure?,
    working: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = OmnisonicTheme.tokens.spacing
    val isDelete = target.action != StyleEditorConfirmAction.RESET_TO_DEFAULT
    val title = stringResource(
        if (isDelete) R.string.style_editor_styles_delete_title else R.string.style_editor_styles_reset_title,
        target.styleName,
    )
    val body = stringResource(
        if (isDelete) R.string.style_editor_styles_delete_body else R.string.style_editor_styles_reset_body,
    )
    val confirmLabel = stringResource(
        if (isDelete) R.string.style_editor_styles_delete_confirm else R.string.style_editor_styles_reset_confirm,
    )
    val relevantFailure = failure?.takeIf { it.styleId == target.styleId }
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = modifier,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(body)
                if (working) {
                    Text(
                        text = stringResource(R.string.style_editor_styles_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (relevantFailure != null) {
                    Text(
                        text = relevantFailure.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.style_editor_styles_delete_cancel))
            }
        },
    )
}

/**
 * D-116 Q8 rename dialog: seeded with the current name, confirm/cancel, and a
 * refusal shown in place with the coordinator's reason. The dialog stays open
 * on a refusal and closes only when the rename committed.
 */
@Composable
private fun RenameDialog(
    state: StyleEditorStylesState,
    onRenameDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = OmnisonicTheme.tokens.spacing
    val targetName = state.entries.firstOrNull { it.id == state.renameTargetId }?.name.orEmpty()
    val failure = state.commandFailure?.takeIf { it.kind == StyleEditorCommandKind.RENAME }
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = modifier,
        title = { Text(stringResource(R.string.style_editor_styles_rename_title, targetName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                OutlinedTextField(
                    value = state.renameDraft,
                    onValueChange = onRenameDraftChange,
                    label = { Text(stringResource(R.string.style_editor_styles_rename_field)) },
                    singleLine = true,
                )
                if (state.pendingCommandId != null) {
                    Text(
                        text = stringResource(R.string.style_editor_styles_rename_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (failure != null) {
                    Text(
                        text = stringResource(
                            R.string.style_editor_styles_rename_failure,
                            targetName,
                            failure.message,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.style_editor_styles_rename_failure_kept),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.style_editor_styles_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.style_editor_styles_rename_cancel))
            }
        },
    )
}
