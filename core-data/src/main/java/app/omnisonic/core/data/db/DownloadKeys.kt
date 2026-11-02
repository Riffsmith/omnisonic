package app.omnisonic.core.data.db

/**
 * `mediaKey` vocabulary for download dedupe. Keys reuse the stream cache-key
 * format `stream:<providerId>:<externalId>:<maxBitRateKbps|auto>:<format|auto>`
 * so a download and its cached stream share one identity.
 */
object DownloadKeys {

    fun mediaKey(providerId: Long, externalId: String, maxBitRateKbps: Int?, format: String?): String =
        "stream:$providerId:$externalId:${maxBitRateKbps ?: "auto"}:${format ?: "auto"}"

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
