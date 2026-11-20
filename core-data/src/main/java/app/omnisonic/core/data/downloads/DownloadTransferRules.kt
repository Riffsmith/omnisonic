package app.omnisonic.core.data.downloads

import java.util.Locale

/**
 * Why one transfer attempt failed. Messages are fixed, credential-free codes:
 * a signed URL, query string or response body never reaches `lastError` or a
 * log line (Phase 7 task 3).
 */
enum class DownloadTransferFailure {
    TRANSPORT,
    HTTP_STATUS,
    PROTOCOL_PAYLOAD,
    CONTENT_ENCODING,
    RANGE_UNSATISFIABLE,
    RANGE_MISMATCH,
    RECORD_MISMATCH,
    SHORT_BODY,
    EMPTY_BODY,
    WRITE_FAILED,
}

fun DownloadTransferFailure.description(): String = when (this) {
    DownloadTransferFailure.TRANSPORT -> "transfer failed: network"
    DownloadTransferFailure.HTTP_STATUS -> "transfer failed: server status"
    DownloadTransferFailure.PROTOCOL_PAYLOAD -> "transfer failed: not media"
    DownloadTransferFailure.CONTENT_ENCODING -> "transfer failed: encoded response"
    DownloadTransferFailure.RANGE_UNSATISFIABLE -> "transfer failed: range rejected"
    DownloadTransferFailure.RANGE_MISMATCH -> "transfer failed: range mismatch"
    DownloadTransferFailure.RECORD_MISMATCH -> "transfer failed: length mismatch"
    DownloadTransferFailure.SHORT_BODY -> "transfer failed: short body"
    DownloadTransferFailure.EMPTY_BODY -> "transfer failed: empty body"
    DownloadTransferFailure.WRITE_FAILED -> "transfer failed: write"
}

/** Uncounted deferral: the row returns to the gate state without an error increment. */
enum class DownloadTransferBlock {
    NETWORK_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
    STORAGE_NOT_WRITABLE,
    STORAGE_LOW_SPACE,
    AUTH_REJECTED,
}

/** Uncounted terminal stop: the row is parked `FAILED` instead of retrying in a loop. */
enum class DownloadTransferPark {
    PROVIDER_MISSING,
    DOWNLOAD_UNSUPPORTED,
    MEDIA_PATH_MISSING,
    QUEUE_KEY_INVALID,
    PATH_UNPLANNABLE,
    FINAL_PATH_CONFLICT,
}

fun DownloadTransferPark.description(): String = when (this) {
    DownloadTransferPark.PROVIDER_MISSING -> "download parked: provider missing"
    DownloadTransferPark.DOWNLOAD_UNSUPPORTED -> "download parked: unsupported"
    DownloadTransferPark.MEDIA_PATH_MISSING -> "download parked: media path missing"
    DownloadTransferPark.QUEUE_KEY_INVALID -> "download parked: invalid queue key"
    DownloadTransferPark.PATH_UNPLANNABLE -> "download parked: path unavailable"
    DownloadTransferPark.FINAL_PATH_CONFLICT -> "download parked: file conflict"
}

/** Parsed `Content-Range: bytes <start>-<end>/<total|*>` header. */
data class ContentRange(
    val start: Long,
    val end: Long,
    val totalBytes: Long?,
)

/**
 * Pure transfer vocabulary (Phase 7 task 3): progress bounds/throttling, the
 * `Content-Range` grammar, response-encoding and media-payload rejection, and
 * the strong-validator rule. Every function is side-effect free so the whole
 * response matrix is testable without a socket.
 */
object DownloadTransferRules {

    const val PROGRESS_UNKNOWN = DownloadLifecycleRules.PROGRESS_UNKNOWN
    const val PROGRESS_MIN_INTERVAL_MS = 250L
    const val PART_STATE_SUFFIX = ".state"
    const val MAX_VALIDATOR_LENGTH = 512
    const val SNIFF_BYTES = 512

    private val identityEncoding = "identity"
    private val contentRangePrefix = "bytes "
    private val textPayloadTypes = setOf(
        "application/json",
        "application/x-www-form-urlencoded",
        "application/xhtml+xml",
        "application/xml",
        "text/html",
        "text/plain",
        "text/xml",
    )
    private val sniffSkips = setOf(0x09, 0x0A, 0x0D, 0x20)

    /** Bounded percent, or [PROGRESS_UNKNOWN] while the total is not yet known. */
    fun progressPercent(bytesOnDisk: Long, totalBytes: Long?): Int {
        if (totalBytes == null || totalBytes <= 0L) return PROGRESS_UNKNOWN
        if (bytesOnDisk <= 0L) return 0
        if (bytesOnDisk >= totalBytes) return 100
        return (bytesOnDisk.toDouble() / totalBytes.toDouble() * 100.0).toInt().coerceIn(0, 100)
    }

    /**
     * `Content-Length`/`Content-Range` accounting is only valid for an
     * identity-encoded body. A proxy that re-encodes the stream would make byte
     * offsets meaningless even though the request asked for identity.
     */
    fun isIdentityEncoding(contentEncoding: String?): Boolean {
        val value = contentEncoding?.trim() ?: return true
        return value.isEmpty() || value.equals(identityEncoding, ignoreCase = true)
    }

    /** Login, proxy and protocol-error bodies are never audio, whatever the status code. */
    fun rejectsContentType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (type.isEmpty()) return false
        if (type in textPayloadTypes) return true
        return type.endsWith("+json") || type.endsWith("+xml")
    }

    /**
     * A media file never begins with an HTML/XML/JSON opener. The BOM and
     * leading whitespace are skipped so a proxy notice with a preamble is still
     * rejected.
     */
    fun looksLikeTextPayload(bytes: ByteArray, length: Int): Boolean {
        var index = 0
        val limit = minOf(length, bytes.size)
        if (limit >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            index = 3
        }
        while (index < limit && bytes[index].toInt() and 0xFF in sniffSkips) index++
        if (index >= limit) return false
        return when (bytes[index].toInt() and 0xFF) {
            0x3C, // '<'
            0x7B, // '{'
            0x5B, // '['
            -> true
            else -> false
        }
    }

    fun payloadSniffComplete(bytes: ByteArray, length: Int): Boolean {
        var index = 0
        val limit = minOf(length, bytes.size)
        if (limit >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            index = 3
        }
        while (index < limit && bytes[index].toInt() and 0xFF in sniffSkips) index++
        return index < limit || limit == SNIFF_BYTES
    }

    fun parseContentRange(raw: String?): ContentRange? {
        val value = raw?.trim() ?: return null
        if (!value.startsWith(contentRangePrefix, ignoreCase = true)) return null
        val spec = value.substring(contentRangePrefix.length).trim()
        val slash = spec.indexOf('/')
        if (slash <= 0 || slash == spec.lastIndex) return null
        val range = spec.substring(0, slash)
        val totalText = spec.substring(slash + 1)
        val dash = range.indexOf('-')
        if (dash <= 0 || dash == range.lastIndex) return null
        val start = range.substring(0, dash).toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val end = range.substring(dash + 1).toLongOrNull()?.takeIf { it >= start } ?: return null
        val total = when (totalText) {
            "*" -> null
            else -> totalText.toLongOrNull()?.takeIf { it > end } ?: return null
        }
        return ContentRange(start, end, total)
    }

    /**
     * A strong `ETag` is the preferred `If-Range` validator. Weak tags
     * (`W/"..."`) never validate a byte range, so they fall back to
     * `Last-Modified`.
     */
    fun strongEtag(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty() || value.length > MAX_VALIDATOR_LENGTH) return null
        if (value.any { it.code < 0x20 || it.code == 0x7F }) return null
        if (value.startsWith("W/")) return null
        val quoted = value.length >= 2 && value.startsWith('"') && value.endsWith('"')
        if (!quoted) return null
        return value
    }

    fun headerDate(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty() || value.length > MAX_VALIDATOR_LENGTH) return null
        if (value.any { it.code < 0x20 || it.code == 0x7F }) return null
        return value
    }

    /** A part may only become the permanent file once it is complete and trustworthy. */
    fun canFinalize(bytesOnDisk: Long, totalBytes: Long?): Boolean =
        bytesOnDisk > 0L && (totalBytes == null || bytesOnDisk == totalBytes)
}

/**
 * Emits at most one progress write per [minIntervalMs] while the percentage
 * moves, plus forced writes for the first value, `100`, and transitions into or
 * out of the indeterminate state. Storage still gets a final write even on a
 * fast transfer.
 */
class DownloadProgressThrottle(private val minIntervalMs: Long = DownloadTransferRules.PROGRESS_MIN_INTERVAL_MS) {

    private var lastPercent: Int? = null
    private var lastAtMs = 0L

    fun shouldEmit(percent: Int, nowMs: Long): Boolean {
        val bounded = if (percent < 0) {
            DownloadTransferRules.PROGRESS_UNKNOWN
        } else {
            percent.coerceIn(0, 100)
        }
        val previous = lastPercent
        if (bounded == previous) return false
        val forced = previous == null ||
            bounded == 100 ||
            bounded < 0 ||
            previous < 0 ||
            nowMs - lastAtMs >= minIntervalMs
        if (!forced) return false
        lastPercent = bounded
        lastAtMs = nowMs
        return true
    }
}
