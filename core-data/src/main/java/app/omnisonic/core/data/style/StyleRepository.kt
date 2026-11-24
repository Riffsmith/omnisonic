package app.omnisonic.core.data.style

import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.style.StyleDecodeResult
import app.omnisonic.core.model.style.StyleDecoder
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleIssue
import app.omnisonic.core.model.style.StyleMigrationResult
import app.omnisonic.core.model.style.StyleMigrator
import app.omnisonic.core.model.style.StyleWriteResult
import app.omnisonic.core.model.style.StyleWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Built-in style documents delivered as read-only assets by the app module
 * (`docs/omnisonic/style/style-loader.md` §Storage). No asset shape is assumed
 * beyond "one JSON document per asset"; TODO 4a owns the delivery form.
 */
interface StyleAssetSource {

    fun names(): List<String>

    fun read(name: String): String?
}

enum class StyleOrigin { BUILT_IN, USER }

data class StyleSummary(
    val id: String,
    val name: String,
    val origin: StyleOrigin,
)

sealed interface StyleReadResult {

    data class Loaded(
        val document: StyleDocument,
        val warnings: List<StyleIssue>,
        val origin: StyleOrigin,
    ) : StyleReadResult

    data class Rejected(val id: String, val errors: List<StyleIssue>) : StyleReadResult {
        val firstMessage: String get() = errors.first().message
    }

    /** The stored text could not be read at all; distinct from a validation rejection. */
    data class Unreadable(val id: String, val detail: String) : StyleReadResult

    data class InvalidId(val id: String) : StyleReadResult

    data class NotFound(val id: String) : StyleReadResult
}

sealed interface StyleTextResult {

    data class Loaded(val text: String, val origin: StyleOrigin) : StyleTextResult

    data class Rejected(val id: String, val errors: List<StyleIssue>) : StyleTextResult {
        val firstMessage: String get() = errors.first().message
    }

    data class Unreadable(val id: String, val detail: String) : StyleTextResult

    data class InvalidId(val id: String) : StyleTextResult

    data class NotFound(val id: String) : StyleTextResult
}

sealed interface StyleWriteOutcome {

    data class Written(val text: String) : StyleWriteOutcome

    data class Rejected(val errors: List<StyleIssue>) : StyleWriteOutcome {
        val firstMessage: String get() = errors.first().message
    }

    data class Unwritable(val id: String, val detail: String) : StyleWriteOutcome

    data class InvalidId(val id: String) : StyleWriteOutcome
}

sealed interface StyleDeleteOutcome {

    data class Deleted(val id: String) : StyleDeleteOutcome

    data class NotFound(val id: String) : StyleDeleteOutcome

    data class Unwritable(val id: String, val detail: String) : StyleDeleteOutcome

    data class InvalidId(val id: String) : StyleDeleteOutcome
}

/**
 * One row of the style listing (D-97/F-41): either a usable style or a user
 * file that could not be read or decoded. Built-ins are always [Valid] — a
 * damaged built-in asset keeps the D-106 behavior (skipped with one warning),
 * never a user "needs attention" row. [modifiedAt] is the user file's last
 * modified time and `null` for a built-in; the model is additive, so
 * [StyleSummary] and the existing [StyleRepository.list] contract are
 * unchanged.
 */
sealed interface StyleListEntry {

    val id: String

    val name: String

    val origin: StyleOrigin

    val modifiedAt: Long?

    data class Valid(
        override val id: String,
        override val name: String,
        override val origin: StyleOrigin,
        override val modifiedAt: Long? = null,
    ) : StyleListEntry

    /**
     * An unreadable or undecodable user file. [path] is the stored file itself
     * and [detail] repeats it, so the reason is qualified by the file the user
     * has to inspect. Such a row is never pickable as a style.
     */
    data class Damaged(
        override val id: String,
        val path: String,
        val detail: String,
        override val modifiedAt: Long? = null,
    ) : StyleListEntry {

        override val name: String get() = id

        override val origin: StyleOrigin get() = StyleOrigin.USER
    }
}

/**
 * The exact stored bytes of a user style file, without migration, decoding or
 * normalization — the support-export path for a damaged file (D-97/F-41).
 * [StyleReadResult.readText][StyleRepository.readText] is not this path: it
 * migrates and can reject.
 */
sealed interface StyleRawResult {

    data class Loaded(
        val id: String,
        val bytes: ByteArray,
        val path: String,
    ) : StyleRawResult

    data class NotFound(val id: String) : StyleRawResult

    data class Unreadable(val id: String, val detail: String) : StyleRawResult

    data class InvalidId(val id: String) : StyleRawResult
}

/**
 * File-backed style storage: built-in documents come from [assets], user
 * documents from `styles/<id>.json` under [stylesDir]
 * (`docs/omnisonic/style/style-loader.md` §Storage, D-71).
 *
 * Every read is text → [StyleMigrator] → [StyleDecoder]; every write is
 * [StyleWriter] then a temp file in the same directory, flushed, synced and
 * renamed over the target, so an interrupted write leaves the previous document
 * in place. `*.json.tmp` leftovers are never read or listed. A user file's
 * identity is its file name: a document declaring another `id` is rejected
 * rather than silently renamed.
 */
class StyleRepository(
    private val stylesDir: File,
    private val assets: StyleAssetSource,
    private val logger: OmnisonicLogger,
    private val migrator: StyleMigrator = StyleMigrator(),
    private val replace: (temp: File, target: File) -> Boolean = { temp, target ->
        temp.renameTo(target)
    },
) {

    private val changeVersion = MutableStateFlow(0L)

    /** Emits after a successful [write] or [delete] so observers re-resolve a file. */
    val changes: Flow<Long> = changeVersion.asStateFlow()

    fun read(id: String): StyleReadResult {
        if (!isValidId(id)) return StyleReadResult.InvalidId(id)
        val file = fileFor(id)
        if (!file.exists()) {
            return builtIns.firstOrNull { it.document.id == id }
                ?.let { StyleReadResult.Loaded(it.document, it.warnings, StyleOrigin.BUILT_IN) }
                ?: StyleReadResult.NotFound(id)
        }
        if (!file.isFile) return StyleReadResult.Unreadable(id, "not a regular file: ${file.name}")
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (failure: IOException) {
            return StyleReadResult.Unreadable(id, failure.message ?: "could not read ${file.name}")
        } catch (failure: SecurityException) {
            return StyleReadResult.Unreadable(id, failure.message ?: "could not read ${file.name}")
        }
        return when (val decoded = decode(text)) {
            is DecodedText.Rejected -> StyleReadResult.Rejected(id, decoded.errors)
            is DecodedText.Decoded ->
                if (decoded.document.id == id) {
                    StyleReadResult.Loaded(decoded.document, decoded.warnings, StyleOrigin.USER)
                } else {
                    StyleReadResult.Rejected(
                        id,
                        listOf(StyleIssue("id", "does not match the file name '$id'")),
                    )
                }
        }
    }

    /**
     * Migrated text without decoding, for profile merging over the built-in
     * defaults (`docs/omnisonic/style/style-loader.md` §Merge). Resolution and
     * id checks are identical to [read]; only the decode step is skipped.
     */
    fun readText(id: String): StyleTextResult {
        if (!isValidId(id)) return StyleTextResult.InvalidId(id)
        val file = fileFor(id)
        if (!file.exists()) {
            return builtIns.firstOrNull { it.document.id == id }
                ?.let { migrateText(id, it.text, StyleOrigin.BUILT_IN) }
                ?: StyleTextResult.NotFound(id)
        }
        if (!file.isFile) return StyleTextResult.Unreadable(id, "not a regular file: ${file.name}")
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (failure: IOException) {
            return StyleTextResult.Unreadable(id, failure.message ?: "could not read ${file.name}")
        } catch (failure: SecurityException) {
            return StyleTextResult.Unreadable(id, failure.message ?: "could not read ${file.name}")
        }
        return migrateText(id, text, StyleOrigin.USER)
    }

    fun write(document: StyleDocument): StyleWriteOutcome {
        if (!isValidId(document.id)) return StyleWriteOutcome.InvalidId(document.id)
        val text = when (val written = StyleWriter.write(document)) {
            is StyleWriteResult.Failed -> return StyleWriteOutcome.Rejected(written.errors)
            is StyleWriteResult.Written -> written.text
        }
        if (!stylesDir.isDirectory && !stylesDir.mkdirs() && !stylesDir.isDirectory) {
            return StyleWriteOutcome.Unwritable(document.id, "styles directory is unavailable")
        }
        val target = fileFor(document.id)
        val temp = File(stylesDir, "${target.name}$TEMP_SUFFIX")
        return try {
            temp.delete()
            FileOutputStream(temp).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (!replace(temp, target)) {
                temp.delete()
                return StyleWriteOutcome.Unwritable(
                    document.id,
                    "could not replace ${target.name}; the previous document is intact",
                )
            }
            changeVersion.update { it + 1 }
            StyleWriteOutcome.Written(text)
        } catch (failure: IOException) {
            temp.delete()
            StyleWriteOutcome.Unwritable(document.id, failure.message ?: "could not write ${target.name}")
        } catch (failure: SecurityException) {
            temp.delete()
            StyleWriteOutcome.Unwritable(document.id, failure.message ?: "could not write ${target.name}")
        }
    }

    fun delete(id: String): StyleDeleteOutcome {
        if (!isValidId(id)) return StyleDeleteOutcome.InvalidId(id)
        val file = fileFor(id)
        if (!file.exists()) return StyleDeleteOutcome.NotFound(id)
        return if (file.delete()) {
            changeVersion.update { it + 1 }
            StyleDeleteOutcome.Deleted(id)
        } else {
            StyleDeleteOutcome.Unwritable(id, "could not delete ${file.name}")
        }
    }

    /**
     * The listing with damaged user files surfaced instead of skipped
     * (D-97/F-41). Usable rows follow [list]'s order and rules exactly; a user
     * file that cannot be read, cannot be decoded or declares another id is a
     * [StyleListEntry.Damaged] row carrying the file path. This is the same
     * decode path as [list] — no second decoder or validator — and it changes
     * neither [list] nor [read].
     */
    fun listing(): List<StyleListEntry> {
        val entries = linkedMapOf<String, StyleListEntry>()
        builtIns.forEach { builtIn ->
            entries[builtIn.document.id] = StyleListEntry.Valid(
                id = builtIn.document.id,
                name = builtIn.document.name,
                origin = StyleOrigin.BUILT_IN,
                modifiedAt = null,
            )
        }
        userFiles().forEach { file ->
            val id = file.name.removeSuffix(FILE_SUFFIX)
            val modifiedAt = file.lastModified().takeIf { at -> at > 0L }
            val alreadyUser = (entries[id] as? StyleListEntry.Valid)?.origin == StyleOrigin.USER
            if (alreadyUser) {
                logger.warn(TAG, "style id $id is declared by more than one file; kept the first")
                return@forEach
            }
            entries.remove(id)
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (failure: IOException) {
                logger.warn(TAG, "style file ${file.name} could not be read", failure)
                entries[id] = damaged(file, id, modifiedAt, "could not be read: ${failure.message ?: file.name}")
                return@forEach
            } catch (failure: SecurityException) {
                logger.warn(TAG, "style file ${file.name} could not be read", failure)
                entries[id] = damaged(file, id, modifiedAt, "could not be read: ${failure.message ?: file.name}")
                return@forEach
            }
            when (val decoded = decode(text)) {
                is DecodedText.Rejected -> {
                    logger.warn(TAG, "style file ${file.name} rejected: ${decoded.errors.first().message}")
                    val first = decoded.errors.first()
                    entries[id] = damaged(file, id, modifiedAt, "${first.path}: ${first.message}")
                }

                is DecodedText.Decoded -> {
                    if (decoded.document.id != id) {
                        logger.warn(TAG, "style file ${file.name} declares another id; skipped")
                        entries[id] = damaged(
                            file,
                            id,
                            modifiedAt,
                            "declares the style id '${decoded.document.id}' instead of '$id'",
                        )
                        return@forEach
                    }
                    entries[id] = StyleListEntry.Valid(
                        id = id,
                        name = decoded.document.name,
                        origin = StyleOrigin.USER,
                        modifiedAt = modifiedAt,
                    )
                }
            }
        }
        return entries.values.toList()
    }

    /**
     * The exact stored bytes of a user file, with no migration, decoding or
     * normalization (D-97/F-41 support export). A built-in has no stored file,
     * so only user files can be exported this way; a missing file is
     * [StyleRawResult.NotFound] even when an asset with that id exists.
     */
    fun readRaw(id: String): StyleRawResult {
        if (!isValidId(id)) return StyleRawResult.InvalidId(id)
        val file = fileFor(id)
        if (!file.exists()) return StyleRawResult.NotFound(id)
        if (!file.isFile) return StyleRawResult.Unreadable(id, "not a regular file: ${file.name}")
        return try {
            StyleRawResult.Loaded(id, file.readBytes(), file.absolutePath)
        } catch (failure: IOException) {
            StyleRawResult.Unreadable(id, failure.message ?: "could not read ${file.name}")
        } catch (failure: SecurityException) {
            StyleRawResult.Unreadable(id, failure.message ?: "could not read ${file.name}")
        }
    }

    private fun damaged(file: File, id: String, modifiedAt: Long?, reason: String): StyleListEntry.Damaged =
        StyleListEntry.Damaged(
            id = id,
            path = file.absolutePath,
            detail = "${file.absolutePath}: $reason",
            modifiedAt = modifiedAt,
        )

    /**
     * Built-ins first in asset-name order, then user files in file-name order;
     * a user file shadows a built-in with the same id, and the first file name
     * wins when two documents declare one id.
     */
    fun list(): List<StyleSummary> {
        val summaries = linkedMapOf<String, StyleSummary>()
        builtIns.forEach { summaries[it.document.id] = it.summary }
        userFiles().forEach { file ->
            val id = file.name.removeSuffix(FILE_SUFFIX)
            summaries.remove(id)
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (failure: IOException) {
                logger.warn(TAG, "style file ${file.name} could not be read", failure)
                return@forEach
            } catch (failure: SecurityException) {
                logger.warn(TAG, "style file ${file.name} could not be read", failure)
                return@forEach
            }
            when (val decoded = decode(text)) {
                is DecodedText.Rejected ->
                    logger.warn(TAG, "style file ${file.name} rejected: ${decoded.errors.first().message}")

                is DecodedText.Decoded -> {
                    if (decoded.document.id != id) {
                        logger.warn(TAG, "style file ${file.name} declares another id; skipped")
                        return@forEach
                    }
                    val existing = summaries[id]
                    if (existing != null && existing.origin == StyleOrigin.USER) {
                        logger.warn(TAG, "style id $id is declared by more than one file; kept the first")
                        return@forEach
                    }
                    summaries[id] = StyleSummary(id, decoded.document.name, StyleOrigin.USER)
                }
            }
        }
        return summaries.values.toList()
    }

    private fun userFiles(): List<File> =
        stylesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun migrateText(id: String, text: String, origin: StyleOrigin): StyleTextResult =
        when (val migrated = migrator.migrate(text)) {
            is StyleMigrationResult.Failed -> StyleTextResult.Rejected(id, migrated.errors)
            is StyleMigrationResult.Migrated -> StyleTextResult.Loaded(migrated.text, origin)
        }

    private data class BuiltIn(
        val document: StyleDocument,
        val warnings: List<StyleIssue>,
        val summary: StyleSummary,
        val text: String,
    )

    private val builtIns: List<BuiltIn> by lazy { loadBuiltIns() }

    private fun loadBuiltIns(): List<BuiltIn> {
        val names = try {
            assets.names()
        } catch (failure: IOException) {
            logger.warn(TAG, "style assets could not be enumerated", failure)
            return emptyList()
        } catch (failure: SecurityException) {
            logger.warn(TAG, "style assets could not be enumerated", failure)
            return emptyList()
        }
        val loaded = mutableListOf<BuiltIn>()
        val seenIds = mutableSetOf<String>()
        names.sorted().forEach { name ->
            val text = try {
                assets.read(name)
            } catch (failure: IOException) {
                logger.warn(TAG, "style asset $name could not be read", failure)
                return@forEach
            } catch (failure: SecurityException) {
                logger.warn(TAG, "style asset $name could not be read", failure)
                return@forEach
            }
            if (text == null) {
                logger.warn(TAG, "style asset $name could not be read")
                return@forEach
            }
            when (val decoded = decode(text)) {
                is DecodedText.Rejected ->
                    logger.warn(TAG, "style asset $name rejected: ${decoded.errors.first().message}")

                is DecodedText.Decoded -> {
                    val id = decoded.document.id
                    if (!seenIds.add(id)) {
                        logger.warn(TAG, "style asset $name repeats id $id; kept the first")
                        return@forEach
                    }
                    loaded += BuiltIn(
                        document = decoded.document,
                        warnings = decoded.warnings,
                        summary = StyleSummary(id, decoded.document.name, StyleOrigin.BUILT_IN),
                        text = text,
                    )
                }
            }
        }
        return loaded
    }

    private sealed interface DecodedText {
        data class Decoded(val document: StyleDocument, val warnings: List<StyleIssue>) : DecodedText

        data class Rejected(val errors: List<StyleIssue>) : DecodedText
    }

    private fun decode(text: String): DecodedText =
        when (val migrated = migrator.migrate(text)) {
            is StyleMigrationResult.Failed -> DecodedText.Rejected(migrated.errors)
            is StyleMigrationResult.Migrated ->
                when (val decoded = StyleDecoder.decode(migrated.text)) {
                    is StyleDecodeResult.Rejected -> DecodedText.Rejected(decoded.errors)
                    is StyleDecodeResult.Decoded -> DecodedText.Decoded(decoded.document, decoded.warnings)
                }
        }

    private fun fileFor(id: String): File = File(stylesDir, id + FILE_SUFFIX)

    private fun isValidId(id: String): Boolean = ID_PATTERN.matches(id) && id != ".." && id != "."

    companion object {
        const val STYLES_DIRECTORY: String = "styles"
        const val FILE_SUFFIX: String = ".json"
        const val TEMP_SUFFIX: String = ".tmp"

        private const val TAG = "styleRepository"

        /** A single path segment: no separator, no leading dot, no traversal. */
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
