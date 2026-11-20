package app.omnisonic.core.data.downloads

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * The stable identity of one saved download. The provider path is the song
 * snapshot path (or `songDetails` fallback for an unsynced enqueue); the
 * optional bitrate/format are the row's explicit variant, never invented.
 */
data class OfflineMediaIdentity(
    val providerId: Long,
    val providerPath: String,
    val transcodeBitrateKbps: Int? = null,
    val format: String? = null,
)

/**
 * Relative stored paths are rooted at the app-private storage root:
 * `OfflineMedias/<first hash char>/<hash>.<extension>`.
 *
 * The hash covers the provider id, the NFC-normalized provider path and the
 * variant, so the same path on two providers, two bitrates or two formats
 * cannot share a file. The `.part` is a deterministic sibling without an
 * extension: the extension may only become known from the response (task 3),
 * while resumable bytes must keep one stable name.
 */
data class OfflinePathPlan(
    val relativePath: String,
    val relativePartPath: String,
    val hash: String,
    val extension: String,
)

sealed interface OfflinePathResult {

    data class Planned(val plan: OfflinePathPlan) : OfflinePathResult

    data class Collision(
        val relativePath: String,
        val existingIdentityKey: String,
    ) : OfflinePathResult

    data class InvalidIdentity(val reason: String) : OfflinePathResult
}

/**
 * Pure path vocabulary for offline media (Phase 7 task 2). No file I/O except
 * the caller-supplied canonical-containment check, so every rule can be tested
 * directly and the hash can be injected to prove collision refusal.
 */
object OfflinePathRules {

    const val MEDIA_DIRECTORY_NAME = "OfflineMedias"
    const val NOMEDIA_FILE_NAME = ".nomedia"
    const val PART_SUFFIX = ".part"
    const val FALLBACK_EXTENSION = "bin"
    const val MIN_FREE_SPACE_BYTES = 100L * 1024L * 1024L
    const val MAX_IDENTITY_PATH_LENGTH = 4096
    const val MAX_STORED_PATH_LENGTH = 512

    private val safeHash = Regex("[a-z0-9]{1,128}")
    private val safeExtension = Regex("[a-z0-9]{1,8}")
    private val windowsDrivePrefix = Regex("^[A-Za-z]:")

    private val audioExtensions = setOf(
        "aac", "aif", "aiff", "alac", "ape", "caf", "dff", "dsf", "flac",
        "m4a", "m4b", "mka", "mp2", "mp3", "mp4", "oga", "ogg", "opus",
        "wav", "wave", "webm", "wma", "wv",
    )

    private val formatAliases = mapOf(
        "alac" to "m4a",
        "mp4" to "m4a",
        "mpeg" to "mp3",
        "mpga" to "mp3",
        "pcm" to "wav",
        "vorbis" to "ogg",
        "wave" to "wav",
    )

    private val passthroughFormats = setOf("auto", "none", "original", "passthrough", "raw")

    /**
     * Canonical identity text. Length-prefix-free but separator-safe because the
     * path is last and control characters are rejected from the path.
     */
    fun identityKey(identity: OfflineMediaIdentity): String = buildString {
        append(identity.providerId)
        append('\u0000')
        append(identity.transcodeBitrateKbps?.toString() ?: "auto")
        append('\u0000')
        append(identity.format ?: "auto")
        append('\u0000')
        append(normalizePath(identity.providerPath))
    }

    fun plan(
        identity: OfflineMediaIdentity,
        assignedPaths: Map<String, String> = emptyMap(),
        hasher: (String) -> String = ::sha256Hex,
    ): OfflinePathResult {
        validate(identity)?.let { return OfflinePathResult.InvalidIdentity(it) }
        val key = identityKey(identity)
        val hash = hasher(key)
        if (!safeHash.matches(hash)) {
            return OfflinePathResult.InvalidIdentity("hasher produced an unusable name")
        }
        val extension = resolveExtension(identity.format, identity.providerPath)
        val relativePath = "$MEDIA_DIRECTORY_NAME/${hash.first()}/$hash.$extension"
        assignedPaths[relativePath]?.let { existing ->
            if (existing != key) return OfflinePathResult.Collision(relativePath, existing)
        }
        return OfflinePathResult.Planned(
            OfflinePathPlan(
                relativePath = relativePath,
                relativePartPath = "$MEDIA_DIRECTORY_NAME/${hash.first()}/$hash$PART_SUFFIX",
                hash = hash,
                extension = extension,
            ),
        )
    }

    fun resolveExtension(format: String?, providerPath: String?): String =
        extensionForFormat(format)
            ?: sourceExtension(providerPath)
            ?: FALLBACK_EXTENSION

    /** An explicit transcode format decides the extension; passthrough asks for the source. */
    fun extensionForFormat(format: String?): String? {
        val token = format?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        if (token in passthroughFormats) return null
        val canonical = formatAliases[token] ?: token
        return canonical.takeIf { it in audioExtensions }
    }

    fun sourceExtension(providerPath: String?): String? {
        val path = providerPath
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?: return null
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        val extension = name.substring(dot + 1).lowercase(Locale.ROOT)
        return extension.takeIf { safeExtension.matches(it) && it in audioExtensions }
    }

    /**
     * A stored path is relative, forward-slash separated, free of control
     * characters and `.`/`..` segments, and never an absolute or drive-qualified
     * path. Anything else is rejected before it can touch the filesystem.
     */
    fun isSafeRelativePath(relativePath: String): Boolean {
        if (relativePath.isBlank() || relativePath.length > MAX_STORED_PATH_LENGTH) return false
        if (relativePath.startsWith('/') || relativePath.contains('\\')) return false
        if (relativePath.any { it.code < 0x20 || it.code == 0x7F }) return false
        if (windowsDrivePrefix.containsMatchIn(relativePath)) return false
        val segments = relativePath.split('/')
        return segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    /**
     * Resolves a stored relative path under [root] and rejects any canonical
     * escape, including a symlinked intermediate directory. Null means the path
     * is unsafe or the resolver could not canonicalize it; callers must never
     * substitute an empty path.
     */
    fun resolveUnder(root: File, relativePath: String): File? {
        if (!isSafeRelativePath(relativePath)) return null
        return try {
            val canonicalRoot = root.canonicalFile.toPath()
            val candidate = File(root, relativePath).canonicalFile.toPath()
            if (candidate.startsWith(canonicalRoot)) candidate.toFile() else null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun validate(identity: OfflineMediaIdentity): String? {
        if (identity.providerId <= 0L) return "provider id must be positive"
        val path = identity.providerPath
        if (path.isBlank()) return "provider path must not be blank"
        if (path.length > MAX_IDENTITY_PATH_LENGTH) return "provider path is too long"
        if (path.any { it.code < 0x20 || it.code == 0x7F }) return "provider path has control characters"
        identity.transcodeBitrateKbps?.let { if (it <= 0) return "bitrate must be positive" }
        identity.format?.let { format ->
            if (format.isBlank()) return "format must not be blank"
            if (format.any { it.code < 0x20 || it.code == 0x7F }) return "format has control characters"
        }
        return null
    }

    private fun normalizePath(path: String): String = Normalizer.normalize(path, Normalizer.Form.NFC)
}
