package app.omnisonic.playback.api.stream

import app.omnisonic.playback.api.queue.PlaybackQueueEntry

data class ResolvedStream(
    val entry: PlaybackQueueEntry,
    val uri: String,
    val startPositionMs: Long = 0L,
    val requiresNetwork: Boolean = true,
    val isRadio: Boolean = false,
) {
    val routedDirect: Boolean
        get() = uri.startsWith("content://") || uri.startsWith("file://")
}

interface StreamResolver {
    /** Suspends while resolving provider state; returns null when the stream is not playable. */
    suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream?
}

object NoStreamResolver : StreamResolver {
    override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream? = null
}
