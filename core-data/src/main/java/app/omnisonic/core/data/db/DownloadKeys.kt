package app.omnisonic.core.data.db

/**
 * `mediaKey` vocabulary for download dedupe. Keys reuse the stream cache-key
 * format `stream:<providerId>:<externalId>:<maxBitRateKbps|auto>:<format|auto>`
 * so a download and its cached stream share one identity.
 */
/** One queue row's stream identity, recovered from its persisted media key. */
data class DownloadStreamKey(
    val providerId: Long,
    val externalId: String,
    val transcodeBitrateKbps: Int?,
    val format: String?,
)

object DownloadKeys {

    private const val BITRATE_AUTO = "auto"
    private const val FORMAT_AUTO = "auto"

    fun mediaKey(providerId: Long, externalId: String, maxBitRateKbps: Int?, format: String?): String =
        "stream:$providerId:$externalId:${maxBitRateKbps ?: BITRATE_AUTO}:${format ?: FORMAT_AUTO}"

    /**
     * Parses a key written by [mediaKey]. The last two segments are always the
     * bitrate and format, so an external id that itself contains colons is
     * recovered intact; anything that cannot round-trip fails closed with null
     * instead of guessing an identity.
     */
    fun parseMediaKey(mediaKey: String): DownloadStreamKey? {
        val segments = mediaKey.split(':')
        if (segments.size < 5 || segments[0] != "stream") return null
        val providerId = segments[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val formatToken = segments.last()
        val bitrateToken = segments[segments.size - 2]
        val externalId = segments.subList(2, segments.size - 2).joinToString(":")
        if (externalId.isEmpty()) return null
        val bitrate = if (bitrateToken == BITRATE_AUTO) {
            null
        } else {
            bitrateToken.toIntOrNull()?.takeIf { it > 0 } ?: return null
        }
        val format = if (formatToken == FORMAT_AUTO) null else formatToken.takeIf { it.isNotEmpty() } ?: return null
        return DownloadStreamKey(providerId, externalId, bitrate, format)
    }

    /**
     * Owning provider parsed from a persisted media key; null when the key
     * does not carry a reliable provider segment. Used by the v1->v2 backfill:
     * rows that cannot be attributed this way are deleted by the migration
     * (documented there) — provider ids are never guessed or invented.
     */
    fun providerIdFromMediaKey(mediaKey: String): Long? =
        mediaKey
            .takeIf { it.startsWith("stream:") }
            ?.split(':')
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
}
