package app.omnisonic.playback.api.stream

import app.omnisonic.playback.api.queue.PlaybackQueueEntry

data class StreamKey(
    val providerId: Long,
    val externalId: String,
    val maxBitRateKbps: Int? = null,
    val format: String? = null,
) {
    val cacheKey: String
        get() = "stream:$providerId:$externalId:${maxBitRateKbps ?: "auto"}:${format ?: "auto"}"
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
}

object NoStreamResolver : StreamResolver {
    override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream? = null
}
