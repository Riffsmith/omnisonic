package app.omnisonic.feature.settings.styleeditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.style.StyleMutationCoordinator
import app.omnisonic.core.data.style.StyleMutationResult
import app.omnisonic.core.data.style.StyleOrigin
import app.omnisonic.core.data.style.StyleListEntry
import app.omnisonic.core.data.style.StyleProfileState
import app.omnisonic.core.data.style.StyleProfileStore
import app.omnisonic.core.data.style.StyleRawResult
import app.omnisonic.core.data.style.StyleReadResult
import app.omnisonic.core.data.style.StyleRepository
import app.omnisonic.core.data.style.styleRevisionOf
import app.omnisonic.style.DefaultStyleTokens
import app.omnisonic.style.ObservableStyleTokenSource
import app.omnisonic.style.StyleTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether a Styles row is a usable style or a damaged user file (D-97/F-41). */
enum class StyleEditorEntryKind { VALID, DAMAGED }

/**
 * One row of the Styles list: the id the coordinator takes, the name the user
 * sees and whether the row is a shipped built-in or a user style. The list is
 * read through [StyleRepository.listing], so built-ins and custom styles arrive
 * in repository order, a damaged user file is surfaced instead of skipped, and
 * no row holds its own preview (`docs/omnisonic/style/style-editor.md` §6, §8,
 * D-116 Q7/Q9, D-97). [modifiedAt] is a user file's last modified time and null
 * for a built-in; [damageDetail] is path-qualified and only set on a damaged
 * row, which is never pickable as a style.
 */
data class StyleEditorStyleEntry(
    val id: String,
    val name: String,
    val origin: StyleOrigin = StyleOrigin.USER,
    val kind: StyleEditorEntryKind = StyleEditorEntryKind.VALID,
    val modifiedAt: Long? = null,
    val damageDetail: String? = null,
    val damagePath: String? = null,
)

/**
 * Why a pick did not apply. Typed, so the screen can tell a refused candidate
 * from a pointer write that failed and never has to render a failed switch as
 * applied (`style-editor.md` §1.2).
 */
sealed interface StyleEditorSwitchFailure {

    val styleId: String

    val message: String

    /** [StyleMutationResult.Rejected]: the candidate could not be applied. */
    data class Rejected(
        override val styleId: String,
        override val message: String,
    ) : StyleEditorSwitchFailure

    /** [StyleMutationResult.Failed]: the pointer write did not happen. */
    data class Failed(
        override val styleId: String,
        override val message: String,
    ) : StyleEditorSwitchFailure
}

/**
 * Which lifecycle command a typed failure belongs to: duplicate/rename
 * (D-116 Q7/Q8, WP03) and delete/recovery (D-97, WP04).
 */
enum class StyleEditorCommandKind {
    DUPLICATE,
    RENAME,
    DELETE,
    RECOVERY_DELETE,
    RECOVERY_RESET,
    RECOVERY_EXPORT,
}

/** What the confirmation dialog in front of a permanent action will do. */
enum class StyleEditorConfirmAction { DELETE, RECOVERY_DELETE, RESET_TO_DEFAULT }

/** The permanent action awaiting confirmation; the dialog names it explicitly. */
data class StyleEditorConfirmTarget(
    val styleId: String,
    val styleName: String,
    val action: StyleEditorConfirmAction,
)

/** The raw support export of a damaged user file, byte-for-byte as stored. */
data class StyleEditorRawExport(
    val styleId: String,
    val fileName: String,
    val bytes: ByteArray,
)

/**
 * Why the resolved default is the style the app is painting although the user
 * selected something else (or deleted what was selected).
 */
enum class StyleEditorFallbackReason { DAMAGED_SELECTION, DELETED_SELECTION }

/**
 * A duplicate or rename that did not land, with the coordinator's own detail.
 * The screen renders it as "not applied", never as a silent success.
 */
data class StyleEditorCommandFailure(
    val kind: StyleEditorCommandKind,
    val styleId: String,
    val message: String,
)

/**
 * The Styles sub-screen state (D-116 Q9): the valid styles in repository order,
 * the style actually applied (the resolved profile, so a fallback is marked
 * truthfully), one preview token set for that active style — never one per row —
 * plus the in-flight pick and the last typed failure. WP03 adds the in-flight
 * duplicate/rename, its typed failure and the rename dialog's id and draft.
 * WP04 adds damaged rows, the confirmation target of a permanent action, the
 * raw export payload and the fallback notice.
 */
data class StyleEditorStylesState(
    val entries: List<StyleEditorStyleEntry> = emptyList(),
    val activeId: String? = null,
    val activeStyleName: String? = null,
    val previewTokens: StyleTokens = DefaultStyleTokens,
    val pendingStyleId: String? = null,
    val failure: StyleEditorSwitchFailure? = null,
    val pendingCommandId: String? = null,
    val commandFailure: StyleEditorCommandFailure? = null,
    val renameTargetId: String? = null,
    val renameDraft: String = "",
    val fallbackReason: StyleEditorFallbackReason? = null,
    val confirmTarget: StyleEditorConfirmTarget? = null,
    val rawExport: StyleEditorRawExport? = null,
)

/**
 * Hub state for the active style: its name and the live preview tokens read
 * from the same profile-backed source the app theme follows, so the preview
 * repaints with the store (D-79/D-80). Scoped to the `styleEditor` back stack
 * entry by the shell, so the hub and every area sub-screen share one session
 * (D-114 Q1 back chain).
 */
data class StyleEditorHubState(
    val activeStyleName: String? = null,
    val previewTokens: StyleTokens = DefaultStyleTokens,
    val undoAvailable: Boolean = false,
    val areas: List<StyleEditorArea> = EditorAreas,
)

private val EditorAreas: List<StyleEditorArea> = StyleEditorArea.entries.toList()

@HiltViewModel
class StyleEditorViewModel @Inject constructor(
    private val profileStore: StyleProfileStore,
    tokenSource: ObservableStyleTokenSource,
    private val repository: StyleRepository,
    private val coordinator: StyleMutationCoordinator,
) : ViewModel() {

    private val session = StyleEditorSession()

    private val commands = StyleEditorCommands(repository, coordinator, session)

    private val pendingStyleId = MutableStateFlow<String?>(null)

    private val switchFailure = MutableStateFlow<StyleEditorSwitchFailure?>(null)

    private val pendingCommandId = MutableStateFlow<String?>(null)

    private val commandFailure = MutableStateFlow<StyleEditorCommandFailure?>(null)

    private val renameTargetId = MutableStateFlow<String?>(null)

    private val renameDraft = MutableStateFlow("")

    private val confirmTarget = MutableStateFlow<StyleEditorConfirmTarget?>(null)

    private val rawExport = MutableStateFlow<StyleEditorRawExport?>(null)

    /** Set when a delete removed the style the store was pointing at. */
    private val deletedActiveStyle = MutableStateFlow(false)

    val hubState: StateFlow<StyleEditorHubState> = combine(
        profileStore.activeProfile,
        tokenSource.tokenFlow,
        session.undoCount,
    ) { profile, tokens, undoCount ->
        StyleEditorHubState(
            activeStyleName = (profile as? StyleProfileState.Resolved)?.document?.name,
            previewTokens = tokens,
            undoAvailable = undoCount > 0,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StyleEditorHubState())

    /**
     * The Styles list is read-only: entries come from [StyleRepository.listing], the
     * active style from the resolved profile, and [repository]'s change flow
     * re-lists after a write or delete (for example a WP01 auto-copy). The single
     * [StyleEditorStylesState.previewTokens] follows the active profile, so the
     * screen owns exactly one preview card.
     */
    private val pickState = combine(
        pendingStyleId,
        switchFailure,
        profileStore.activeId,
    ) { pending, failure, pointer ->
        PickState(pending, failure, pointer)
    }

    private val lifecycleState = combine(
        pendingCommandId,
        commandFailure,
        renameTargetId,
        renameDraft,
        combine(confirmTarget, rawExport, deletedActiveStyle) { target, export, deleted ->
            DialogState(target, export, deleted)
        },
    ) { pending, failure, target, draft, dialog ->
        LifecycleState(
            pendingId = pending,
            failure = failure,
            renameTargetId = target,
            renameDraft = draft,
            confirmTarget = dialog.confirmTarget,
            rawExport = dialog.rawExport,
            deletedActiveStyle = dialog.deletedActiveStyle,
        )
    }

    val stylesState: StateFlow<StyleEditorStylesState> = combine(
        profileStore.activeProfile,
        tokenSource.tokenFlow,
        repository.changes,
        pickState,
        lifecycleState,
    ) { profile, tokens, _, pick, lifecycle ->
        val active = profile as? StyleProfileState.Resolved
        val entries = repository.listing().map { entry ->
            when (entry) {
                is StyleListEntry.Valid -> StyleEditorStyleEntry(
                    id = entry.id,
                    name = entry.name,
                    origin = entry.origin,
                    kind = StyleEditorEntryKind.VALID,
                    modifiedAt = entry.modifiedAt,
                )

                is StyleListEntry.Damaged -> StyleEditorStyleEntry(
                    id = entry.id,
                    name = entry.id,
                    origin = StyleOrigin.USER,
                    kind = StyleEditorEntryKind.DAMAGED,
                    modifiedAt = entry.modifiedAt,
                    damageDetail = entry.detail,
                    damagePath = entry.path,
                )
            }
        }
        val damagedIds = entries
            .filter { it.kind == StyleEditorEntryKind.DAMAGED }
            .map { it.id }
            .toSet()
        val fallbackReason = when {
            pick.activePointer != null && pick.activePointer in damagedIds ->
                StyleEditorFallbackReason.DAMAGED_SELECTION

            lifecycle.deletedActiveStyle -> StyleEditorFallbackReason.DELETED_SELECTION

            else -> null
        }
        StyleEditorStylesState(
            entries = entries,
            activeId = active?.document?.id,
            activeStyleName = active?.document?.name,
            previewTokens = tokens,
            pendingStyleId = pick.pendingId,
            failure = pick.failure,
            pendingCommandId = lifecycle.pendingId,
            commandFailure = lifecycle.failure,
            renameTargetId = lifecycle.renameTargetId,
            renameDraft = lifecycle.renameDraft,
            fallbackReason = fallbackReason,
            confirmTarget = lifecycle.confirmTarget,
            rawExport = lifecycle.rawExport,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StyleEditorStylesState())

    private data class PickState(
        val pendingId: String?,
        val failure: StyleEditorSwitchFailure?,
        val activePointer: String?,
    )

    private data class DialogState(
        val confirmTarget: StyleEditorConfirmTarget?,
        val rawExport: StyleEditorRawExport?,
        val deletedActiveStyle: Boolean,
    )

    private data class LifecycleState(
        val pendingId: String?,
        val failure: StyleEditorCommandFailure?,
        val renameTargetId: String?,
        val renameDraft: String,
        val confirmTarget: StyleEditorConfirmTarget?,
        val rawExport: StyleEditorRawExport?,
        val deletedActiveStyle: Boolean,
    )

    /**
     * Applies [id] through the one mutation boundary (D-108/D-114 Q5). A success
     * repaints the preview and the app theme through the existing profile flow —
     * no restart, no manual refresh. A refusal or pointer failure is kept as a
     * typed failure and the active marker stays where the store still points.
     */
    fun pick(id: String) {
        if (pendingStyleId.value != null) return
        // A damaged file never becomes a pickable style (D-97/F-41); an id that
        // is not listed still reaches the coordinator so its refusal stays typed.
        val entry = stylesState.value.entries.firstOrNull { it.id == id }
        if (entry?.kind == StyleEditorEntryKind.DAMAGED) return
        pendingStyleId.value = id
        switchFailure.value = null
        viewModelScope.launch {
            val result = coordinator.switch(id)
            switchFailure.value = switchFailure(id, result)
            if (result is StyleMutationResult.Switched) deletedActiveStyle.value = false
            pendingStyleId.value = null
        }
    }

    /**
     * D-97: opens the delete confirmation for a user style. A built-in row has no
     * affordance and no command; the confirmation copy states the deletion is
     * permanent.
     */
    fun beginDelete(id: String) {
        val entry = stylesState.value.entries.firstOrNull { it.id == id } ?: return
        if (entry.kind == StyleEditorEntryKind.VALID && entry.origin != StyleOrigin.USER) return
        commandFailure.value = null
        rawExport.value = null
        confirmTarget.value = StyleEditorConfirmTarget(
            styleId = id,
            styleName = entry.name,
            action = if (entry.kind == StyleEditorEntryKind.DAMAGED) {
                StyleEditorConfirmAction.RECOVERY_DELETE
            } else {
                StyleEditorConfirmAction.DELETE
            },
        )
    }

    /** D-97: reset-to-default confirmation for a damaged row only. */
    fun beginResetDamaged(id: String) {
        val entry = stylesState.value.entries.firstOrNull { it.id == id } ?: return
        if (entry.kind != StyleEditorEntryKind.DAMAGED) return
        commandFailure.value = null
        rawExport.value = null
        confirmTarget.value = StyleEditorConfirmTarget(
            styleId = id,
            styleName = entry.name,
            action = StyleEditorConfirmAction.RESET_TO_DEFAULT,
        )
    }

    fun cancelConfirm() {
        confirmTarget.value = null
        commandFailure.value = null
    }

    /**
     * Runs the confirmed permanent action through the committed command seam:
     * exactly one ordinary delete, recovery delete or reset-to-default. A
     * failure keeps the dialog open with the typed reason and changes nothing it
     * should not; only a success closes it. Deleting the active style clears the
     * pointer, the resolver falls back to the default and the notice says so.
     */
    fun confirm() {
        val target = confirmTarget.value ?: return
        if (pendingCommandId.value != null) return
        val wasActive = stylesState.value.activeId == target.styleId
        pendingCommandId.value = target.styleId
        commandFailure.value = null
        viewModelScope.launch {
            val kind = when (target.action) {
                StyleEditorConfirmAction.DELETE -> StyleEditorCommandKind.DELETE
                StyleEditorConfirmAction.RECOVERY_DELETE -> StyleEditorCommandKind.RECOVERY_DELETE
                StyleEditorConfirmAction.RESET_TO_DEFAULT -> StyleEditorCommandKind.RECOVERY_RESET
            }
            val result = when (target.action) {
                StyleEditorConfirmAction.DELETE -> commands.delete(target.styleId)
                StyleEditorConfirmAction.RECOVERY_DELETE -> commands.deleteDamaged(target.styleId)
                StyleEditorConfirmAction.RESET_TO_DEFAULT ->
                    commands.resetDamagedToDefault(target.styleId)
            }
            val failure = result.commandFailure(kind, target.styleId)
            commandFailure.value = failure
            if (result is StyleMutationResult.Deleted && wasActive) {
                deletedActiveStyle.value = true
            }
            if (failure == null) confirmTarget.value = null
            pendingCommandId.value = null
        }
    }

    /**
     * D-97/F-41 support export: hands the damaged file's exact stored bytes to
     * the caller. No decoding, migration or normalization happens, and the file
     * is not modified.
     */
    fun exportRaw(id: String) {
        if (pendingCommandId.value != null) return
        val entry = stylesState.value.entries.firstOrNull { it.id == id } ?: return
        if (entry.kind != StyleEditorEntryKind.DAMAGED) return
        pendingCommandId.value = id
        commandFailure.value = null
        viewModelScope.launch {
            when (val raw = commands.exportRaw(id)) {
                is StyleRawResult.Loaded -> rawExport.value =
                    StyleEditorRawExport(id, "$id${StyleRepository.FILE_SUFFIX}", raw.bytes)

                is StyleRawResult.NotFound -> commandFailure.value = StyleEditorCommandFailure(
                    StyleEditorCommandKind.RECOVERY_EXPORT,
                    id,
                    "the style file does not exist",
                )

                is StyleRawResult.Unreadable -> commandFailure.value = StyleEditorCommandFailure(
                    StyleEditorCommandKind.RECOVERY_EXPORT,
                    id,
                    raw.detail,
                )

                is StyleRawResult.InvalidId -> commandFailure.value = StyleEditorCommandFailure(
                    StyleEditorCommandKind.RECOVERY_EXPORT,
                    id,
                    "invalid style id '$id'",
                )
            }
            pendingCommandId.value = null
        }
    }

    fun dismissRawExport() {
        rawExport.value = null
    }

    /**
     * D-116 Q7: copies [id] into a fresh style through the command seam without
     * switching. The list re-lists from `repository.changes`; a typed failure
     * leaves the active style and preview exactly where they were.
     */
    fun duplicate(id: String) {
        if (pendingCommandId.value != null) return
        pendingCommandId.value = id
        commandFailure.value = null
        viewModelScope.launch {
            val result = commands.duplicate(id)
            commandFailure.value = result.commandFailure(StyleEditorCommandKind.DUPLICATE, id)
            pendingCommandId.value = null
        }
    }

    /** Opens the rename dialog for a user style only; a built-in has no rename affordance. */
    fun beginRename(id: String) {
        val entry = stylesState.value.entries.firstOrNull { it.id == id } ?: return
        if (entry.origin != StyleOrigin.USER) return
        commandFailure.value = null
        renameDraft.value = entry.name
        renameTargetId.value = id
    }

    fun updateRenameDraft(name: String) {
        renameDraft.value = name
    }

    fun cancelRename() {
        renameTargetId.value = null
        renameDraft.value = ""
        commandFailure.value = null
    }

    /**
     * D-116 Q8: renames with the revision read when the command is confirmed.
     * A refusal keeps the dialog open with the typed reason and changes no name.
     */
    fun confirmRename() {
        val id = renameTargetId.value ?: return
        if (pendingCommandId.value != null) return
        pendingCommandId.value = id
        commandFailure.value = null
        viewModelScope.launch {
            val document = (repository.read(id) as? StyleReadResult.Loaded)?.document
            val revision = document?.let(::styleRevisionOf)
            val result = if (revision == null) {
                StyleMutationResult.Failed(id, "the style could not be read")
            } else {
                commands.rename(StyleEditTarget(id, revision), renameDraft.value)
            }
            val failure = result.commandFailure(StyleEditorCommandKind.RENAME, id)
            commandFailure.value = failure
            if (failure == null) {
                renameTargetId.value = null
                renameDraft.value = ""
            }
            pendingCommandId.value = null
        }
    }

    fun undo() {
        session.undo()
    }

    private fun StyleMutationResult.commandFailure(
        kind: StyleEditorCommandKind,
        id: String,
    ): StyleEditorCommandFailure? = when (this) {
        is StyleMutationResult.Saved,
        is StyleMutationResult.Deleted,
        is StyleMutationResult.Switched,
        -> null

        is StyleMutationResult.Rejected ->
            StyleEditorCommandFailure(kind, id, firstMessage)

        is StyleMutationResult.Failed ->
            StyleEditorCommandFailure(kind, id, detail)

        is StyleMutationResult.Partial ->
            StyleEditorCommandFailure(kind, id, detail)

        is StyleMutationResult.NotFound ->
            StyleEditorCommandFailure(kind, id, "the style is no longer available")

        is StyleMutationResult.StaleStyle ->
            StyleEditorCommandFailure(kind, id, "the style changed before the command was applied")
    }

    private fun switchFailure(
        id: String,
        result: StyleMutationResult,
    ): StyleEditorSwitchFailure? = when (result) {
        is StyleMutationResult.Switched -> null
        is StyleMutationResult.Rejected -> StyleEditorSwitchFailure.Rejected(id, result.firstMessage)
        is StyleMutationResult.Failed -> StyleEditorSwitchFailure.Failed(id, result.detail)
        is StyleMutationResult.Partial -> StyleEditorSwitchFailure.Failed(id, result.detail)
        is StyleMutationResult.NotFound ->
            StyleEditorSwitchFailure.Failed(id, "the style is no longer available")
        is StyleMutationResult.StaleStyle ->
            StyleEditorSwitchFailure.Failed(id, "the style changed while it was being applied")
        is StyleMutationResult.Saved,
        is StyleMutationResult.Deleted,
        -> StyleEditorSwitchFailure.Failed(id, "the switch ended without applying the style")
    }
}
