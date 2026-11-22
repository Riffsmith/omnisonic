package app.omnisonic.playback.api.stream

import app.omnisonic.playback.api.queue.PlaybackQueueEntry

data class StreamKey(
    val providerId: Long,
    val externalId: String,
    val maxBitRateKbps: Int? = null,
    val format: String? = null,
    /**
     * True when the provider answered with a server-transcode session (D-54).
     * A transcode session is a different representation from the plain stream
     * even when the plan names no parameters, so it must never share the raw
     * `auto:auto` cache entry (D-57/D-62).
     */
    val serverTranscoded: Boolean = false,
) {
    val cacheKey: String
        get() = buildString {
            append("stream:$providerId:$externalId:${maxBitRateKbps ?: "auto"}:${format ?: "auto"}")
            if (serverTranscoded) append(":tx")
        }
}

enum class StreamTransport {
    PROVIDER_HTTP,
    DIRECT_URI,
    CUSTOM,
}

data class ResolvedStream(
    val entry: PlaybackQueueEntry,
    val uri: String,
    val startPositionMs: Long = 0L,
    val requiresNetwork: Boolean = true,
    val isRadio: Boolean = false,
    val cacheKey: String = StreamKey(entry.providerId, entry.externalId).cacheKey,
    val expiresAtMs: Long? = null,
    val transport: StreamTransport = StreamTransport.PROVIDER_HTTP,
    val customDataSourceFactory: String? = null,
) {
    val routedDirect: Boolean
        get() = uri.startsWith("content://") || uri.startsWith("file://")

    fun isStale(nowMs: Long, skewMs: Long = 60_000L): Boolean {
        val expiresAt = expiresAtMs ?: return false
        return expiresAt - skewMs <= nowMs
    }
}

interface StreamResolver {
    /** Suspends while resolving provider state; returns null when the stream is not playable. */
    suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream?

    /**
     * Re-resolves [entry] for a start at song position [startPositionMs] (D-61).
     * The default reports the position back as the stream's start position, so
     * the player seeks within the returned URI; a resolver that bakes the
     * offset into the request itself (a server transcode `timeOffsetSeconds`)
     * returns `startPositionMs = 0` because that timeline already begins at the
     * offset.
     */
    suspend fun resolve(entry: PlaybackQueueEntry, startPositionMs: Long): ResolvedStream? =
        resolve(entry)?.copy(startPositionMs = startPositionMs.coerceAtLeast(0L))
}

object NoStreamResolver : StreamResolver {
    override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream? = null
}
