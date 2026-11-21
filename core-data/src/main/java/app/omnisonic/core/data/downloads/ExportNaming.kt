package app.omnisonic.core.data.downloads

import java.text.Normalizer
import java.util.Locale

/** One public document to create; every value is sanitized before it reaches the resolver. */
data class ExportDocumentRequest(
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
)

/**
 * Pure naming for public export documents (Phase 7 task 6a). The collection,
 * pending flag and MIME/extension fallbacks are fixed vocabulary; the display
 * name is the only user-visible value, so it is normalized and stripped of path
 * separators, control characters and URL syntax, bounded in length, and made
 * unique per stream identity by an identity token. Provider paths and signed
 * URLs never enter a public value.
 */
object ExportNaming {

    const val RELATIVE_PATH = "Download/Omnisonic/"
    const val MIME_FALLBACK = "application/octet-stream"
    const val FALLBACK_BASE_NAME = "Track"
    const val MAX_BASE_LENGTH = 96
    const val IDENTITY_TOKEN_LENGTH = 8

    private val mimeByExtension = mapOf(
        "aac" to "audio/aac",
        "aif" to "audio/x-aiff",
        "aiff" to "audio/x-aiff",
        "alac" to "audio/mp4",
        "ape" to "audio/x-ape",
        "caf" to "audio/x-caf",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "m4b" to "audio/mp4",
        "mka" to "audio/x-matroska",
        "mp2" to "audio/mpeg",
        "mp3" to "audio/mpeg",
        "mp4" to "audio/mp4",
        "oga" to "audio/ogg",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/x-wav",
        "wave" to "audio/x-wav",
        "webm" to "audio/webm",
        "wma" to "audio/x-ms-wma",
        "wv" to "audio/x-wavpack",
    )

    private val reservedBaseNames = buildSet {
        addAll(listOf("con", "prn", "aux", "nul"))
        for (index in 1..9) {
            add("com$index")
            add("lpt$index")
        }
    }

    private val controlCharacters = Regex("[\\x00-\\x1F\\x7F]")
    private val pathAndUrlCharacters = Regex("[\\\\/:*?\"<>|]")
    private val whitespaceRun = Regex("\\s+")
    private val safeExtension = Regex("[a-z0-9]{1,8}")

    fun request(mediaKey: String, title: String?, extension: String): ExportDocumentRequest {
        val safeExtension = extension.trim().lowercase(Locale.ROOT)
            .takeIf { safeExtension.matches(it) }
            ?: OfflinePathRules.FALLBACK_EXTENSION
        return ExportDocumentRequest(
            relativePath = RELATIVE_PATH,
            displayName = displayName(title, mediaKey, safeExtension),
            mimeType = mimeType(safeExtension),
        )
    }

    fun displayName(title: String?, mediaKey: String, extension: String): String =
        "${baseName(title)} [${identityToken(mediaKey)}].$extension"

    fun identityToken(mediaKey: String): String =
        OfflinePathRules.sha256Hex(mediaKey).take(IDENTITY_TOKEN_LENGTH)

    fun matchesDocumentName(displayName: String, mediaKey: String, title: String?): Boolean {
        val prefix = "${baseName(title)} [${identityToken(mediaKey)}]."
        if (!displayName.startsWith(prefix)) return false
        return safeExtension.matches(displayName.removePrefix(prefix))
    }

    fun baseName(title: String?): String {
        val normalized = Normalizer.normalize(title.orEmpty(), Normalizer.Form.NFC)
        val cleaned = pathAndUrlCharacters
            .replace(controlCharacters.replace(normalized, " "), " ")
            .replace(whitespaceRun, " ")
            .trim()
            .trimStart('.', ' ')
            .trimEnd('.', ' ')
        val bounded = truncate(cleaned)
        if (bounded.isBlank() || bounded.lowercase(Locale.ROOT) in reservedBaseNames) {
            return FALLBACK_BASE_NAME
        }
        return bounded
    }

    fun mimeType(extension: String?): String =
        extension?.trim()?.lowercase(Locale.ROOT)?.let { mimeByExtension[it] } ?: MIME_FALLBACK

    /** The one relative path this app writes to; a foreign path never becomes an export destination. */
    fun isOwnedRelativePath(path: String?): Boolean {
        val value = path?.trim() ?: return false
        return value.trimEnd('/') == RELATIVE_PATH.trimEnd('/')
    }

    private fun truncate(value: String): String {
        if (value.length <= MAX_BASE_LENGTH) return value
        var end = MAX_BASE_LENGTH
        if (Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end).trimEnd('.', ' ')
    }
}
