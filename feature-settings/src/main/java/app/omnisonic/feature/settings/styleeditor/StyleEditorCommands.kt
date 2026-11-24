package app.omnisonic.feature.settings.styleeditor

import app.omnisonic.core.data.style.StyleIdGenerator
import app.omnisonic.core.data.style.StyleMergeResult
import app.omnisonic.core.data.style.StyleMerger
import app.omnisonic.core.data.style.StyleMutationCoordinator
import app.omnisonic.core.data.style.StyleMutationResult
import app.omnisonic.core.data.style.StyleOrigin
import app.omnisonic.core.data.style.StyleRawResult
import app.omnisonic.core.data.style.StyleReadResult
import app.omnisonic.core.data.style.StyleRepository
import app.omnisonic.core.data.style.StyleRevision
import app.omnisonic.core.data.style.StyleTextResult
import app.omnisonic.core.data.style.styleRevisionOf
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleIssue

/**
 * What the editor is editing right now: the style id it opened and the revision
 * it last observed (`docs/omnisonic/style/style-editor.md` §1). The seam never
 * retries a transform against a revision it did not see.
 */
data class StyleEditTarget(
    val id: String,
    val revision: StyleRevision,
)

/**
 * App-generated names for app-created copies (D-116 Q8). Names are unique
 * against the list the caller passes, compared case-insensitively so two styles
 * cannot differ only in letter case. TODO 5b WP03 reuses this for the Styles
 * duplicate command.
 */
class StyleCopyNaming {

    fun nameFor(sourceName: String, taken: Collection<String>): String {
        val stem = sourceName.trim().ifBlank { DEFAULT_STEM }
        val used = taken.map { it.trim().lowercase() }.toSet()
        val first = "$stem copy"
        if (first.lowercase() !in used) return first
        var index = 2
        while ("$stem copy $index".lowercase() in used) {
            index += 1
        }
        return "$stem copy $index"
    }

    private companion object {
        const val DEFAULT_STEM = "Style"
    }
}

/**
 * The committed-edit seam the editor screens call (TODO 5b WP01, consumed by
 * TODO 5c). For a user style it issues exactly one
 * [StyleMutationCoordinator.update] and records one session entry when it lands.
 *
 * For a built-in it performs the D-116 Q6 auto-copy: a pristine fresh-id copy
 * whose D-82 derivation base is the built-in is persisted and activated through
 * one `create`, the prior session history is cleared, and the mutation is then
 * applied as the first undoable `update` on the copy. The built-in asset itself
 * is never written. Results are returned as the coordinator produced them: a
 * copy that was saved but not switched is `Partial` (saved inactive) and no
 * history is recorded for any failure.
 */
class StyleEditorCommands(
    private val repository: StyleRepository,
    private val coordinator: StyleMutationCoordinator,
    private val session: StyleEditorSession,
    private val naming: StyleCopyNaming = StyleCopyNaming(),
    private val newId: StyleIdGenerator = StyleIdGenerator.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun commitEdit(
        target: StyleEditTarget,
        transform: (StyleDocument) -> StyleDocument,
    ): StyleMutationResult {
        val loaded = repository.read(target.id) as? StyleReadResult.Loaded
        if (loaded == null || loaded.origin == StyleOrigin.USER) {
            return commitOnUserStyle(target, transform)
        }
        return commitOnBuiltIn(loaded.document, target.revision, transform)
    }

    private suspend fun commitOnUserStyle(
        target: StyleEditTarget,
        transform: (StyleDocument) -> StyleDocument,
    ): StyleMutationResult {
        val result = coordinator.update(target.id, target.revision, transform)
        if (result is StyleMutationResult.Saved) session.record()
        return result
    }

    private suspend fun commitOnBuiltIn(
        source: StyleDocument,
        expectedRevision: StyleRevision,
        transform: (StyleDocument) -> StyleDocument,
    ): StyleMutationResult {
        val revision = styleRevisionOf(source)
            ?: return StyleMutationResult.Failed(
                source.id,
                "the built-in style cannot be normalized; nothing was written",
            )
        if (revision != expectedRevision) {
            return StyleMutationResult.StaleStyle(source.id, expectedRevision, revision)
        }
        val listed = repository.list()
        val copyName = naming.nameFor(source.name, listed.map { it.name })
        val copyId = allocateId(source.id)
            ?: return StyleMutationResult.Failed(
                source.id,
                "a fresh style id could not be allocated for the copy",
            )
        val pristine = source.copy(id = copyId, name = copyName)
        val created = when (val result = coordinator.create(pristine, source.id, activate = true)) {
            is StyleMutationResult.Saved -> result
            // Partial (saved inactive), Failed, Rejected and StaleStyle keep their meaning.
            else -> return result
        }
        session.clear()
        val updated = coordinator.update(created.id, created.revision, transform)
        if (updated is StyleMutationResult.Saved) session.record()
        return updated
    }

    /**
     * D-116 Q7 (TODO 5b WP03): copies a built-in or user style into a fresh id
     * carrying the full resolved document — the source's stored text merged over
     * its built-in base (D-72) — with the source's D-82 derivation base and an
     * app-generated unique name (Q8). It performs one [StyleMutationCoordinator.create]
     * and does not switch, so the active style and preview do not move. The
     * coordinator's typed result is returned unchanged.
     */
    suspend fun duplicate(id: String): StyleMutationResult {        val source = when (val read = repository.read(id)) {
            is StyleReadResult.Loaded -> read
            is StyleReadResult.NotFound -> return StyleMutationResult.NotFound(id)
            is StyleReadResult.Rejected -> return StyleMutationResult.Rejected(id, read.errors)
            is StyleReadResult.Unreadable -> return StyleMutationResult.Failed(id, read.detail)
            is StyleReadResult.InvalidId -> return invalidId(id)
        }
        val baseId = coordinator.derivationBaseFor(id)
            ?: return StyleMutationResult.Failed(
                id,
                "the derivation base of '$id' could not be determined; nothing was written",
            )
        val base = when (val read = repository.read(baseId)) {
            is StyleReadResult.Loaded -> read.document
            else -> return StyleMutationResult.Failed(
                id,
                "the derivation base '$baseId' is unavailable; nothing was written",
            )
        }
        val overlay = when (val text = repository.readText(id)) {
            is StyleTextResult.Loaded -> text.text
            is StyleTextResult.Rejected -> return StyleMutationResult.Rejected(id, text.errors)
            is StyleTextResult.Unreadable -> return StyleMutationResult.Failed(id, text.detail)
            is StyleTextResult.NotFound ->
                return StyleMutationResult.Failed(id, "the style file does not exist")
            is StyleTextResult.InvalidId -> return invalidId(id)
        }
        val resolved = when (val merged = StyleMerger.merge(base, overlay)) {
            is StyleMergeResult.Merged -> merged.document
            is StyleMergeResult.Rejected -> return StyleMutationResult.Rejected(id, merged.errors)
        }
        val copyId = allocateId(id)
            ?: return StyleMutationResult.Failed(
                id,
                "a fresh style id could not be allocated for the copy",
            )
        val taken = repository.list().map { it.name }
        val copyName = naming.nameFor(source.document.name, taken)
        return coordinator.create(
            resolved.copy(id = copyId, name = copyName),
            derivationBaseId = baseId,
            activate = false,
        )
    }

    /**
     * D-116 Q8 (TODO 5b WP03): renames a user style through the coordinator with
     * the revision the screen last observed. Names stay unique case-insensitively
     * against [StyleRepository.list] excluding the style itself; blank and taken
     * names are refused with a typed `name` issue and never silently disambiguated.
     * A built-in is refused by the coordinator's existing rule, not by a second one.
     */
    suspend fun rename(target: StyleEditTarget, name: String): StyleMutationResult {
        val trimmed = name.trim()
        val current = repository.read(target.id)
        if (current is StyleReadResult.Loaded && current.origin == StyleOrigin.USER) {
            if (trimmed.isEmpty()) {
                return StyleMutationResult.Rejected(
                    target.id,
                    listOf(StyleIssue(NAME_PATH, "enter a name for the style")),
                )
            }
            val taken = repository.list().any { summary ->
                summary.id != target.id && summary.name.trim().equals(trimmed, ignoreCase = true)
            }
            if (taken) {
                return StyleMutationResult.Rejected(
                    target.id,
                    listOf(StyleIssue(NAME_PATH, "another style is already called '$trimmed'")),
                )
            }
        }
        return coordinator.rename(target.id, target.revision, trimmed)
    }

    /**
     * TODO 5b WP04 ordinary delete: exactly one
     * [StyleMutationCoordinator.delete] and the coordinator's typed result
     * unchanged. A built-in id is refused by the coordinator's own rule; the
     * screen simply offers no affordance for one.
     */
    suspend fun delete(id: String): StyleMutationResult = coordinator.delete(id)

    /**
     * D-97/F-41 recovery commands for a damaged user file. They are distinct
     * from the validated commands above: each delegates to its coordinator
     * recovery command exactly once and returns the typed result unchanged, so a
     * damaged document can never be routed through ordinary validation.
     */
    suspend fun deleteDamaged(id: String): StyleMutationResult = coordinator.deleteDamaged(id)

    suspend fun resetDamagedToDefault(id: String): StyleMutationResult =
        coordinator.resetDamagedToDefault(id)

    /** The raw, non-decoding support export of a damaged user file. */
    suspend fun exportRaw(id: String): StyleRawResult = coordinator.exportRaw(id)

    private fun invalidId(id: String): StyleMutationResult = StyleMutationResult.Rejected(
        id,
        listOf(StyleIssue("id", "invalid style id '$id'")),
    )

    private fun allocateId(baseId: String): String? {
        val now = clock()
        repeat(ID_ATTEMPTS) { attempt ->
            val candidate = newId.next(baseId, now, attempt)
            if (repository.read(candidate) is StyleReadResult.NotFound) return candidate
        }
        return null
    }

    private companion object {
        const val ID_ATTEMPTS = 8
        const val NAME_PATH = "name"
    }
}
