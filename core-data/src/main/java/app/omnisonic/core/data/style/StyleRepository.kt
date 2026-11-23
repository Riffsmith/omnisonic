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
        val loaded = mutableListOf<BuiltIn>()
        val seenIds = mutableSetOf<String>()
        assets.names().sorted().forEach { name ->
            val text = try {
                assets.read(name)
            } catch (failure: IOException) {
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
