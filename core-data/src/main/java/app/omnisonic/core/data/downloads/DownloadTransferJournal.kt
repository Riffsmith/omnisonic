package app.omnisonic.core.data.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Resume state persisted beside the `.part` (Phase 7 task 3). [identityHash] is
 * the SHA-256 of the variant identity, so a sidecar can never be adopted by
 * another provider, path or bitrate/format variant. [strongEtag] and
 * [lastModified] are response validators used for `If-Range`; the total is the
 * entity length used for short-body detection and bounded progress.
 */
data class DownloadTransferState(
    val identityHash: String,
    val strongEtag: String? = null,
    val lastModified: String? = null,
    val totalBytes: Long? = null,
) {
    fun ifRangeValidator(): String? = strongEtag ?: lastModified
}

/**
 * Deterministic, code-only sidecar next to the extensionless `.part`
 * (`<hash>.part.state`). No queue column and therefore no migration: a corrupt
 * or foreign sidecar is discarded, which safely downgrades the attempt to a
 * fresh download.
 */
class DownloadTransferJournal {

    fun stateFile(partFile: File): File =
        File(partFile.parentFile, partFile.name + DownloadTransferRules.PART_STATE_SUFFIX)

    fun read(partFile: File): DownloadTransferState? {
        val file = stateFile(partFile)
        if (!file.isFile || file.length() !in 1..MAX_STATE_BYTES) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        return decode(text)
    }

    fun write(partFile: File, state: DownloadTransferState): Boolean {
        if (!isEncodable(state)) return false
        val target = stateFile(partFile)
        val parent = target.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val temporary = File(parent, "${target.name}.tmp")
        val encoded = encode(state)
        return try {
            temporary.delete()
            FileOutputStream(temporary).use { output ->
                output.write(encoded.toByteArray(Charsets.UTF_8))
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

    fun delete(partFile: File) {
        stateFile(partFile).delete()
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

    private fun isEncodable(state: DownloadTransferState): Boolean {
        if (!identityHashPattern.matches(state.identityHash)) return false
        state.strongEtag?.let { if (DownloadTransferRules.strongEtag(it) == null) return false }
        state.lastModified?.let { if (DownloadTransferRules.headerDate(it) == null) return false }
        state.totalBytes?.let { if (it < 0L) return false }
        return true
    }

    private fun encode(state: DownloadTransferState): String = buildString {
        append("v=1\n")
        append("identity=").append(state.identityHash).append('\n')
        append("etag=").append(state.strongEtag.orEmpty()).append('\n')
        append("last=").append(state.lastModified.orEmpty()).append('\n')
        append("total=").append(state.totalBytes?.toString().orEmpty()).append('\n')
    }

    private fun decode(text: String): DownloadTransferState? {
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
        val identity = fields["identity"] ?: return null
        if (!identityHashPattern.matches(identity)) return null
        val etag = fields["etag"]?.takeIf { it.isNotEmpty() }?.let { raw ->
            DownloadTransferRules.strongEtag(raw) ?: return null
        }
        val lastModified = fields["last"]?.takeIf { it.isNotEmpty() }?.let { raw ->
            DownloadTransferRules.headerDate(raw) ?: return null
        }
        val total = fields["total"]?.takeIf { it.isNotEmpty() }?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        }
        return DownloadTransferState(
            identityHash = identity,
            strongEtag = etag,
            lastModified = lastModified,
            totalBytes = total,
        )
    }

    private companion object {
        const val MAX_STATE_BYTES = 4096L
        val identityHashPattern = Regex("[0-9a-f]{64}")
    }
}
