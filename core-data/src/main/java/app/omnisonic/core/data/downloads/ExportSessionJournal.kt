package app.omnisonic.core.data.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where one export session stands: document created pending, or published and awaiting row settlement. */
enum class ExportSessionPhase {
    PENDING,
    PUBLISHED,
}

/**
 * The app's own record of one public export attempt (Phase 7 task 6b). [uri] is
 * the document this app created; recovery reuses it instead of inserting a
 * second document, and never resolves one it did not record. [expectedBytes] is
 * the staged copy's exact length at insert time, used to reject a stale session
 * whose document no longer matches the attempt.
 */
data class ExportSession(
    val mediaKey: String,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val expectedBytes: Long,
    val phase: ExportSessionPhase,
)

/**
 * Durable export-session ledger in app-internal storage, one
 * `<sha-256(mediaKey)>.export` file per attempt with an atomic replace and a
 * validating decode. It is deliberately not a queue column: export state is
 * attempt bookkeeping, and a corrupt or foreign file is reported and dropped
 * rather than trusted, which degrades to a fresh export instead of touching an
 * unrelated document.
 */
class ExportSessionJournal(private val directory: File) {

    fun sessionFile(mediaKey: String): File = File(directory, "${OfflinePathRules.sha256Hex(mediaKey)}$FILE_SUFFIX")

    fun read(mediaKey: String): ExportSession? = decodeRead(sessionFile(mediaKey))

    fun write(session: ExportSession): Boolean {
        if (!isEncodable(session)) return false
        val target = sessionFile(session.mediaKey)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
        val temporary = File(directory, "${target.name}.tmp")
        return try {
            temporary.delete()
            FileOutputStream(temporary).use { output ->
                output.write(encode(session).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            moveReplacing(temporary, target)
            true
        } catch (_: IOException) {
            temporary.delete()
            false
        } catch (_: SecurityException) {
            temporary.delete()
            false
        }
    }

    fun delete(mediaKey: String) {
        sessionFile(mediaKey).delete()
    }

    /** Every session file that decodes; malformed/foreign files are listed by [unreadable]. */
    fun selectAll(): List<ExportSession> = sessionFiles().mapNotNull(::decodeRead)

    /** Own ledger files that cannot be trusted and should be removed rather than acted on. */
    fun unreadable(): List<File> = sessionFiles().filter { decodeRead(it) == null }

    private fun sessionFiles(): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun decodeRead(file: File): ExportSession? {
        if (!file.isFile || file.length() !in 1..MAX_SESSION_BYTES) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        return decode(text)
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isEncodable(session: ExportSession): Boolean {
        if (!mediaKeyPattern.matches(session.mediaKey)) return false
        if (!isOwnedExportDocumentUri(session.uri)) return false
        if (!textPattern.matches(session.displayName)) return false
        if (!textPattern.matches(session.mimeType)) return false
        return session.expectedBytes > 0L
    }

    private fun encode(session: ExportSession): String = buildString {
        append("v=1\n")
        append("key=").append(session.mediaKey).append('\n')
        append("uri=").append(session.uri).append('\n')
        append("name=").append(session.displayName).append('\n')
        append("mime=").append(session.mimeType).append('\n')
        append("size=").append(session.expectedBytes).append('\n')
        append("phase=").append(
            when (session.phase) {
                ExportSessionPhase.PENDING -> "pending"
                ExportSessionPhase.PUBLISHED -> "published"
            },
        ).append('\n')
    }

    private fun decode(text: String): ExportSession? {
        val fields = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val separator = line.indexOf('=')
            if (separator <= 0) return null
            val key = line.substring(0, separator)
            if (key in fields) return null
            fields[key] = line.substring(separator + 1)
        }
        if (fields["v"] != "1") return null
        val mediaKey = fields["key"]?.takeIf { mediaKeyPattern.matches(it) } ?: return null
        val uri = fields["uri"]?.takeIf(::isOwnedExportDocumentUri) ?: return null
        val displayName = fields["name"]?.takeIf { textPattern.matches(it) } ?: return null
        val mimeType = fields["mime"]?.takeIf { textPattern.matches(it) } ?: return null
        val expectedBytes = fields["size"]?.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val phase = when (fields["phase"]) {
            "pending" -> ExportSessionPhase.PENDING
            "published" -> ExportSessionPhase.PUBLISHED
            else -> return null
        }
        return ExportSession(mediaKey, uri, displayName, mimeType, expectedBytes, phase)
    }

    private companion object {
        const val FILE_SUFFIX = ".export"
        const val MAX_SESSION_BYTES = 8192L
        val mediaKeyPattern = Regex("[^\\x00-\\x1F\\x7F]{1,512}")
        val textPattern = Regex("[^\\x00-\\x1F\\x7F]{1,512}")
    }
}
