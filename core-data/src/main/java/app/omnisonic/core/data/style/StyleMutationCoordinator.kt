package app.omnisonic.core.data.style

import app.omnisonic.core.data.style.StyleMutationResult.Deleted
import app.omnisonic.core.data.style.StyleMutationResult.Failed
import app.omnisonic.core.data.style.StyleMutationResult.NotFound
import app.omnisonic.core.data.style.StyleMutationResult.Partial
import app.omnisonic.core.data.style.StyleMutationResult.Rejected
import app.omnisonic.core.data.style.StyleMutationResult.Saved
import app.omnisonic.core.data.style.StyleMutationResult.StaleStyle
import app.omnisonic.core.data.style.StyleMutationResult.Switched
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleIssue
import app.omnisonic.core.model.style.StyleWriteResult
import app.omnisonic.core.model.style.StyleWriter
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The revision of a committed style: the SHA-256 of its canonical text
 * (`StyleWriter`). Two documents with the same revision have the same
 * committed bytes, and an external write changes the revision, so a command
 * captured against an earlier state can be refused instead of applied to the
 * wrong document (`docs/omnisonic/style/style-editor.md` §Session core, D-108).
 */
@JvmInline
value class StyleRevision(val value: String) {
    override fun toString(): String = value
}

/**
 * The revision [document] commits as: the SHA-256 of its canonical text, the
 * same value a command result carries. An editor compares the revision it last
 * observed with this instead of inventing a second normalization; `null` means
 * the document cannot be normalized, so it would not commit either.
 */
fun styleRevisionOf(document: StyleDocument): StyleRevision? =
    when (val written = StyleWriter.write(document)) {
        is StyleWriteResult.Written -> StyleRevision(sha256Hex(written.text))
        is StyleWriteResult.Failed -> null
    }

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

/** One reset target; screen equals section where the two coincide in v1 (D-103). */
sealed interface StyleResetScope {

    data class Field(val path: String) : StyleResetScope

    data class Section(val section: StyleSection) : StyleResetScope

    data object All : StyleResetScope
}

/** Injectable id source for duplicates and copies; [attempt] is the collision retry. */
fun interface StyleIdGenerator {

    fun next(baseId: String, nowMs: Long, attempt: Int): String

    companion object {

        val Default: StyleIdGenerator = StyleIdGenerator { baseId, nowMs, attempt ->
            val stem = stem(baseId)
            if (attempt == 0) "$stem-${nowMs.toString(36)}" else "$stem-${nowMs.toString(36)}-$attempt"
        }

        private fun stem(baseId: String): String {
            val cleaned = baseId.map { character ->
                if (character.isLetterOrDigit() || character == '.' || character == '_' || character == '-') {
                    character
                } else {
                    '-'
                }
            }.joinToString("").take(96).trim('.', '-', '_')
            return cleaned.ifBlank { "style" }
        }
    }
}

/**
 * One typed outcome for every style mutation. `StaleStyle` means the command
 * carried an older revision than the committed document; `Failed` means the
 * commit did not happen; `Partial` means the style file was committed but a
 * companion step (derivation entry or active pointer) did not, and names which
 * part is true. Nothing but [Saved]/[Deleted]/[Switched] is a success shape, so
 * a caller can never push an Undo entry for a write that did not land.
 */
sealed interface StyleMutationResult {

    data class Saved(
        val id: String,
        val document: StyleDocument,
        val revision: StyleRevision,
        val derivationBaseId: String?,
        val activated: Boolean = false,
        val savedAsCopy: Boolean = false,
        val unchanged: Boolean = false,
    ) : StyleMutationResult

    data class Deleted(val id: String) : StyleMutationResult

    data class Switched(val id: String) : StyleMutationResult

    data class StaleStyle(
        val id: String,
        val expectedRevision: StyleRevision,
        val actualRevision: StyleRevision?,
    ) : StyleMutationResult

    data class NotFound(val id: String) : StyleMutationResult

    data class Rejected(val id: String, val errors: List<StyleIssue>) : StyleMutationResult {
        val firstMessage: String get() = errors.first().message
    }

    data class Failed(val id: String, val detail: String) : StyleMutationResult

    data class Partial(
        val id: String,
        val detail: String,
        val committed: StyleDocument? = null,
        val revision: StyleRevision? = null,
    ) : StyleMutationResult
}

/**
 * The single ordered boundary for every style write and active-pointer change
 * (`docs/omnisonic/style/style-loader.md` §6, D-108).
 *
 * One [Mutex] covers every command, so a save, reset, rename, duplicate,
 * delete, switch, import write and restore write cannot interleave. An update's
 * transform runs against the document read inside the lock, never a snapshot
 * captured before it, and every command against an existing style carries the
 * expected [StyleRevision]: a mismatch returns [StyleMutationResult.StaleStyle]
 * and changes nothing. Built-in assets are never written; a user file with a
 * built-in id is the shadow the repository already defines.
 *
 * Companion steps are ordered after the file commit and reported truthfully:
 * the derivation entry is recorded on create/import/restore and cleared on
 * delete, and activation happens only after the document is written. A
 * DataStore/pointer failure never looks like a success. A delete snapshots the
 * active id through the store's mutation-only read before it deletes anything
 * and aborts with [StyleMutationResult.Failed] when ownership cannot be
 * determined, so a pointer read failure can never return a success shape after
 * the file is gone (D-113).
 */
class StyleMutationCoordinator(
    private val repository: StyleRepository,
    private val profileStore: StyleProfileStore,
    private val derivations: StyleDerivationStore,
    private val logger: OmnisonicLogger,
    private val defaultStyleId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: StyleIdGenerator = StyleIdGenerator.Default,
) {

    private val operationMutex = Mutex()

    suspend fun create(
        document: StyleDocument,
        derivationBaseId: String = defaultStyleId,
        activate: Boolean = false,
    ): StyleMutationResult = operationMutex.withLock {
        when (val existing = repository.read(document.id)) {
            is StyleReadResult.NotFound -> Unit
            is StyleReadResult.InvalidId -> return@withLock invalidId(document.id)
            is StyleReadResult.Loaded -> return@withLock Rejected(
                document.id,
                listOf(StyleIssue("id", "style id '${document.id}' is already in use")),
            )

            is StyleReadResult.Rejected -> return@withLock Rejected(document.id, existing.errors)
            is StyleReadResult.Unreadable -> return@withLock Rejected(
                document.id,
                listOf(StyleIssue("id", "style id '${document.id}' is not free: ${existing.detail}")),
            )
        }
        if (baseDocument(derivationBaseId) == null) {
            return@withLock unavailableBase(document.id, derivationBaseId)
        }
        when (val written = write(document)) {
            is Write.Rejected -> Rejected(document.id, written.errors)
            is Write.Failed -> Failed(document.id, written.detail)
            is Write.Saved -> {
                val revision = revisionOf(written.text)
                var detail: String? = null
                if (!derivations.set(document.id, derivationBaseId)) {
                    detail = "the style was saved but its derivation base could not be recorded"
                }
                var activated = false
                if (detail == null && activate) {
                    val failure = switchLocked(document.id)
                    if (failure == null) {
                        activated = true
                    } else {
                        detail = failure
                    }
                }
                if (detail != null) {
                    Partial(document.id, detail, document, revision)
                } else {
                    Saved(
                        id = document.id,
                        document = document,
                        revision = revision,
                        derivationBaseId = derivationBaseId,
                        activated = activated,
                    )
                }
            }
        }
    }

    /**
     * Applies [transform] to the latest committed document inside the lock.
     * [expectedRevision] is the revision the caller last observed; an external
     * write/restore that moved it returns [StyleMutationResult.StaleStyle].
     */
    suspend fun update(
        id: String,
        expectedRevision: StyleRevision,
        transform: (StyleDocument) -> StyleDocument,
    ): StyleMutationResult = operationMutex.withLock {
        val current = when (val target = target(id, expectedRevision)) {
            is Target.User -> target
            is Target.Refused -> return@withLock target.result
        }
        val next = transform(current.document)
        if (next.id != id) {
            return@withLock Rejected(
                id,
                listOf(StyleIssue("id", "an edit cannot change the style id from '$id' to '${next.id}'")),
            )
        }
        commit(id, next, derivations.baseOf(id))
    }

    suspend fun rename(
        id: String,
        expectedRevision: StyleRevision,
        name: String,
    ): StyleMutationResult = update(id, expectedRevision) { document -> document.copy(name = name) }

    /**
     * Copies [id] into a fresh id and records the copy's derivation base: the
     * source's own entry, or the source built-in, or the default built-in when
     * a user style has no usable entry (logged once, never silently wrong).
     */
    suspend fun duplicate(
        id: String,
        activate: Boolean = false,
    ): StyleMutationResult = operationMutex.withLock {
        val source = when (val read = repository.read(id)) {
            is StyleReadResult.Loaded -> read
            is StyleReadResult.NotFound -> return@withLock NotFound(id)
            is StyleReadResult.Rejected -> return@withLock Rejected(id, read.errors)
            is StyleReadResult.Unreadable -> return@withLock Failed(id, read.detail)
            is StyleReadResult.InvalidId -> return@withLock invalidId(id)
        }
        val baseId = derivationBaseFor(source)
        if (baseDocument(baseId) == null) {
            return@withLock unavailableBase(id, baseId)
        }
        val freshId = allocateId(id) ?: return@withLock Failed(
            id,
            "a fresh style id could not be allocated for the copy",
        )
        val copy = source.document.copy(id = freshId)
        when (val written = write(copy)) {
            is Write.Rejected -> Rejected(freshId, written.errors)
            is Write.Failed -> Failed(freshId, written.detail)
            is Write.Saved -> {
                val revision = revisionOf(written.text)
                if (!derivations.set(freshId, baseId)) {
                    return@withLock Partial(
                        freshId,
                        "the copy was saved but its derivation base could not be recorded",
                        copy,
                        revision,
                    )
                }
                var activated = false
                if (activate) {
                    val failure = switchLocked(freshId)
                        ?: run {
                            activated = true
                            null
                        }
                    if (failure != null) {
                        return@withLock Partial(freshId, failure, copy, revision)
                    }
                }
                Saved(
                    id = freshId,
                    document = copy,
                    revision = revision,
                    derivationBaseId = baseId,
                    activated = activated,
                    savedAsCopy = true,
                )
            }
        }
    }

    /**
     * The derivation base a copy of [id] records: the source's own entry, the
     * source built-in itself, or the default built-in when a user style has no
     * usable entry (logged once, never silently wrong). Read-only: the editor's
     * duplicate command asks this instead of restating the D-82 fallback rule,
     * so there is exactly one such rule. `null` means the source cannot be read.
     */
    fun derivationBaseFor(id: String): String? =
        when (val read = repository.read(id)) {
            is StyleReadResult.Loaded -> derivationBaseFor(read)
            is StyleReadResult.NotFound,
            is StyleReadResult.Rejected,
            is StyleReadResult.Unreadable,
            is StyleReadResult.InvalidId,
            -> null
        }

    private fun derivationBaseFor(source: StyleReadResult.Loaded): String {
        val recorded = derivations.baseOf(source.document.id)
        if (source.origin == StyleOrigin.USER && recorded == null) {
            logger.warn(
                TAG,
                "style '${source.document.id}' has no derivation base; the default '$defaultStyleId' is used",
            )
        }
        return recorded
            ?: if (source.origin == StyleOrigin.BUILT_IN) source.document.id else defaultStyleId
    }

    /** Restores one field, one section or the whole content from the derived base. */
    suspend fun reset(
        id: String,
        expectedRevision: StyleRevision,
        scope: StyleResetScope,
    ): StyleMutationResult = operationMutex.withLock {
        val current = when (val target = target(id, expectedRevision)) {
            is Target.User -> target
            is Target.Refused -> return@withLock target.result
        }
        val recorded = derivations.baseOf(id)
        if (recorded == null) {
            logger.warn(
                TAG,
                "style '$id' has no derivation base; the default '$defaultStyleId' is used for the reset",
            )
        }
        val baseId = recorded ?: defaultStyleId
        val base = baseDocument(baseId) ?: return@withLock unavailableBase(id, baseId)
        val next = when (scope) {
            is StyleResetScope.Field -> when (val reset = StyleMerger.resetField(current.document, base, scope.path)) {
                is StyleResetResult.Reset -> reset.document
                is StyleResetResult.UnknownPath -> return@withLock Rejected(
                    id,
                    listOf(StyleIssue(scope.path, "the derivation base does not carry this path")),
                )

                is StyleResetResult.Rejected -> return@withLock Rejected(id, reset.errors)
            }

            is StyleResetScope.Section ->
                StyleMerger.resetSection(current.document, base, scope.section)

            is StyleResetScope.All -> StyleMerger.resetAll(current.document, base)
        }
        commit(id, next, baseId)
    }

    suspend fun delete(id: String): StyleMutationResult = operationMutex.withLock {
        when (val read = repository.read(id)) {
            is StyleReadResult.Loaded -> if (read.origin == StyleOrigin.BUILT_IN) {
                return@withLock Rejected(
                    id,
                    listOf(StyleIssue("id", "built-in style '$id' cannot be deleted")),
                )
            }

            is StyleReadResult.Rejected -> return@withLock Rejected(id, read.errors)
            is StyleReadResult.Unreadable -> return@withLock Failed(id, read.detail)
            is StyleReadResult.InvalidId -> return@withLock invalidId(id)
            is StyleReadResult.NotFound -> Unit
        }
        removeStyleFile(id)
    }

    /**
     * D-97/F-41 recovery delete: removes a damaged user file through the same
     * pointer snapshot and derivation cleanup as [delete], without decoding the
     * file first. It is deliberately separate from the ordinary validated
     * delete, which keeps refusing a damaged document; a valid style is refused
     * here so the recovery path can never stand in for the ordinary one.
     * Deletion stays confirm-and-permanent: no trash store is involved.
     */
    suspend fun deleteDamaged(id: String): StyleMutationResult = operationMutex.withLock {
        damagedTarget(id)?.let { refusal -> return@withLock refusal }
        removeStyleFile(id)
    }

    /**
     * D-97/F-41 recovery reset: replaces a damaged user file with the resolved
     * default built-in's content under the damaged style's own id and records
     * the default as its D-82 derivation base, in one committed write. A valid
     * style is refused (the ordinary reset owns it); the damaged bytes are not
     * kept — export them raw first if they are needed.
     */
    suspend fun resetDamagedToDefault(id: String): StyleMutationResult =
        operationMutex.withLock {
            damagedTarget(id)?.let { refusal -> return@withLock refusal }
            val base = baseDocument(defaultStyleId)
                ?: return@withLock unavailableBase(id, defaultStyleId)
            val replacement = base.copy(id = id)
            when (val written = write(replacement)) {
                is Write.Rejected -> Rejected(id, written.errors)
                is Write.Failed -> Failed(id, written.detail)
                is Write.Saved -> {
                    val revision = revisionOf(written.text)
                    if (!derivations.set(id, defaultStyleId)) {
                        Partial(
                            id,
                            "the style was replaced but its derivation base could not be recorded",
                            replacement,
                            revision,
                        )
                    } else {
                        Saved(
                            id = id,
                            document = replacement,
                            revision = revision,
                            derivationBaseId = defaultStyleId,
                        )
                    }
                }
            }
        }

    /**
     * D-97/F-41 raw support export: the exact stored bytes, with no migration,
     * decoding or normalization. It runs under the same operation mutex as every
     * other command so an export cannot read a file mid-commit.
     */
    suspend fun exportRaw(id: String): StyleRawResult = operationMutex.withLock {
        repository.readRaw(id)
    }

    /**
     * `null` means [id] is a damaged user file (readable or not, decodable or
     * not); anything else is the typed refusal or absence the recovery command
     * must return unchanged.
     */
    private fun damagedTarget(id: String): StyleMutationResult? =
        when (val read = repository.read(id)) {
            is StyleReadResult.Loaded -> Rejected(
                id,
                listOf(StyleIssue("id", "style '$id' is valid; use the ordinary command")),
            )

            is StyleReadResult.Rejected,
            is StyleReadResult.Unreadable,
            -> null

            is StyleReadResult.InvalidId -> invalidId(id)
            is StyleReadResult.NotFound -> NotFound(id)
        }

    /**
     * The one file-and-pointer removal shared by [delete] and [deleteDamaged]:
     * snapshot the active id before deletion (D-113), delete the file and clear
     * the pointer when it pointed at it, then clear the derivation entry.
     */
    private suspend fun removeStyleFile(id: String): StyleMutationResult {
        val activeAtStart = when (val snapshot = profileStore.snapshotActiveId()) {
            is ActiveIdSnapshot.Unreadable -> return Failed(
                id,
                "the active style pointer could not be read; nothing was deleted: ${snapshot.detail}",
            )

            is ActiveIdSnapshot.Known -> snapshot.id
        }
        val outcome = try {
            profileStore.delete(id, activeAtStart)
        } catch (failure: IOException) {
            return deletePointerFailure(id, pointerDetail(failure))
        } catch (failure: SecurityException) {
            return deletePointerFailure(id, pointerDetail(failure))
        }
        return when (outcome) {
            is StyleDeleteOutcome.Deleted -> {
                if (!derivations.clear(id)) {
                    Partial(id, "the style was deleted but its derivation entry could not be cleared")
                } else {
                    Deleted(id)
                }
            }

            is StyleDeleteOutcome.NotFound -> {
                if (!derivations.clear(id)) {
                    Partial(id, "no style file existed but its derivation entry could not be cleared")
                } else {
                    NotFound(id)
                }
            }

            is StyleDeleteOutcome.Unwritable -> Failed(id, outcome.detail)
            is StyleDeleteOutcome.InvalidId -> invalidId(id)
        }
    }

    suspend fun switch(id: String): StyleMutationResult = operationMutex.withLock {
        try {
            when (val result = profileStore.switch(id)) {
                is StyleSwitchResult.Switched -> Switched(result.id)
                is StyleSwitchResult.Rejected -> Rejected(result.id, result.errors)
            }
        } catch (failure: IOException) {
            Failed(id, pointerDetail(failure))
        } catch (failure: SecurityException) {
            Failed(id, pointerDetail(failure))
        }
    }

    /**
     * One import write: D-83 collision resolution, the derivation entry, and no
     * activation. A free id is preserved; an identical normalized document is a
     * no-op; a different document on an occupied id (including a built-in id) is
     * saved under a fresh id, so a built-in is never shadowed.
     */
    suspend fun importWrite(
        document: StyleDocument,
        derivationBaseId: String = defaultStyleId,
    ): StyleMutationResult = writeImported(document, derivationBaseId, activate = false)

    /**
     * One restore write: the same collision resolution and derivation entry as
     * [importWrite], then activation only when asked. The caller runs this after
     * its own data restore (D-86); a pointer failure returns [StyleMutationResult.Partial]
     * with the committed document, i.e. saved inactive.
     */
    suspend fun restoreWrite(
        document: StyleDocument,
        derivationBaseId: String = defaultStyleId,
        activate: Boolean,
    ): StyleMutationResult = writeImported(document, derivationBaseId, activate)

    private suspend fun writeImported(
        document: StyleDocument,
        derivationBaseId: String,
        activate: Boolean,
    ): StyleMutationResult = operationMutex.withLock {
        if (baseDocument(derivationBaseId) == null) {
            return@withLock unavailableBase(document.id, derivationBaseId)
        }
        val existing = repository.read(document.id)
        val targetId = when (existing) {
            is StyleReadResult.NotFound -> document.id
            is StyleReadResult.InvalidId -> return@withLock invalidId(document.id)
            is StyleReadResult.Loaded -> {
                val existingText = canonicalText(existing.document)
                    ?: return@withLock Failed(document.id, "the stored document cannot be normalized")
                val incomingText = canonicalText(document)
                    ?: return@withLock Rejected(
                        document.id,
                        listOf(StyleIssue("id", "the imported document cannot be normalized")),
                    )
                if (existingText == incomingText) {
                    return@withLock Saved(
                        id = existing.document.id,
                        document = existing.document,
                        revision = revisionOf(existingText),
                        derivationBaseId = derivations.baseOf(existing.document.id),
                        unchanged = true,
                    )
                }
                allocateId(document.id) ?: return@withLock Failed(
                    document.id,
                    "a fresh style id could not be allocated for the imported document",
                )
            }

            is StyleReadResult.Rejected -> allocateId(document.id) ?: return@withLock Failed(
                document.id,
                "a fresh style id could not be allocated for the imported document",
            )

            is StyleReadResult.Unreadable -> allocateId(document.id) ?: return@withLock Failed(
                document.id,
                "a fresh style id could not be allocated for the imported document",
            )
        }
        val savedAsCopy = targetId != document.id
        val imported = if (savedAsCopy) document.copy(id = targetId) else document
        when (val written = write(imported)) {
            is Write.Rejected -> Rejected(targetId, written.errors)
            is Write.Failed -> Failed(targetId, written.detail)
            is Write.Saved -> {
                val revision = revisionOf(written.text)
                if (!derivations.set(targetId, derivationBaseId)) {
                    return@withLock Partial(
                        targetId,
                        "the style was saved but its derivation base could not be recorded",
                        imported,
                        revision,
                    )
                }
                var activated = false
                if (activate) {
                    val failure = switchLocked(targetId)
                    if (failure != null) {
                        return@withLock Partial(targetId, failure, imported, revision)
                    }
                    activated = true
                }
                Saved(
                    id = targetId,
                    document = imported,
                    revision = revision,
                    derivationBaseId = derivationBaseId,
                    activated = activated,
                    savedAsCopy = savedAsCopy,
                )
            }
        }
    }

    private sealed interface Target {

        data class User(val document: StyleDocument) : Target

        data class Refused(val result: StyleMutationResult) : Target
    }

    private fun target(id: String, expectedRevision: StyleRevision): Target {
        val read = repository.read(id)
        val loaded = when (read) {
            is StyleReadResult.Loaded -> read
            is StyleReadResult.NotFound -> return Target.Refused(NotFound(id))
            is StyleReadResult.Rejected -> return Target.Refused(Rejected(id, read.errors))
            is StyleReadResult.Unreadable -> return Target.Refused(Failed(id, read.detail))
            is StyleReadResult.InvalidId -> return Target.Refused(invalidId(id))
        }
        if (loaded.origin == StyleOrigin.BUILT_IN) {
            return Target.Refused(
                Rejected(id, listOf(StyleIssue("id", "built-in style '$id' cannot be modified"))),
            )
        }
        val actual = revisionOf(loaded.document)
            ?: return Target.Refused(Failed(id, "the stored document cannot be normalized"))
        if (actual != expectedRevision) {
            return Target.Refused(StaleStyle(id, expectedRevision, actual))
        }
        return Target.User(loaded.document)
    }

    private fun commit(
        id: String,
        document: StyleDocument,
        derivationBaseId: String?,
    ): StyleMutationResult =
        when (val written = write(document)) {
            is Write.Rejected -> Rejected(id, written.errors)
            is Write.Failed -> Failed(id, written.detail)
            is Write.Saved -> Saved(
                id = id,
                document = document,
                revision = revisionOf(written.text),
                derivationBaseId = derivationBaseId,
            )
        }

    private sealed interface Write {

        data class Saved(val text: String) : Write

        data class Rejected(val errors: List<StyleIssue>) : Write

        data class Failed(val detail: String) : Write
    }

    private fun write(document: StyleDocument): Write =
        when (val outcome = repository.write(document)) {
            is StyleWriteOutcome.Written -> Write.Saved(outcome.text)
            is StyleWriteOutcome.Rejected -> Write.Rejected(outcome.errors)
            is StyleWriteOutcome.Unwritable -> Write.Failed(outcome.detail)
            is StyleWriteOutcome.InvalidId -> Write.Rejected(
                listOf(StyleIssue("id", "invalid style id '${outcome.id}'")),
            )
        }

    private fun baseDocument(baseId: String): StyleDocument? =
        when (val read = repository.read(baseId)) {
            is StyleReadResult.Loaded -> read.document
            is StyleReadResult.NotFound -> null
            is StyleReadResult.Rejected -> null
            is StyleReadResult.Unreadable -> null
            is StyleReadResult.InvalidId -> null
        }

    private fun allocateId(baseId: String): String? {
        val now = clock()
        repeat(ID_ATTEMPTS) { attempt ->
            val candidate = newId.next(baseId, now, attempt)
            if (repository.read(candidate) is StyleReadResult.NotFound) return candidate
        }
        return null
    }

    private suspend fun switchLocked(id: String): String? =
        try {
            when (val result = profileStore.switch(id)) {
                is StyleSwitchResult.Switched -> null
                is StyleSwitchResult.Rejected -> result.firstMessage
            }
        } catch (failure: IOException) {
            pointerDetail(failure)
        } catch (failure: SecurityException) {
            pointerDetail(failure)
        }

    private fun deletePointerFailure(id: String, detail: String): StyleMutationResult =
        if (repository.read(id) is StyleReadResult.NotFound) {
            Partial(id, "the style file was removed but the active pointer could not be cleared: $detail")
        } else {
            Failed(id, detail)
        }

    private fun unavailableBase(id: String, baseId: String): StyleMutationResult = Failed(
        id,
        "the derivation base '$baseId' is unavailable; nothing was written",
    )

    private fun invalidId(id: String): StyleMutationResult =
        Rejected(id, listOf(StyleIssue("id", "invalid style id '$id'")))

    private fun pointerDetail(failure: Exception): String =
        failure.message ?: "the active style pointer could not be written"

    private fun canonicalText(document: StyleDocument): String? =
        when (val written = StyleWriter.write(document)) {
            is StyleWriteResult.Written -> written.text
            is StyleWriteResult.Failed -> null
        }

    private fun revisionOf(document: StyleDocument): StyleRevision? =
        canonicalText(document)?.let { text -> revisionOf(text) }

    private fun revisionOf(text: String): StyleRevision = StyleRevision(sha256Hex(text))

    private companion object {
        private const val TAG = "styleMutation"
        private const val ID_ATTEMPTS = 8
    }
}
