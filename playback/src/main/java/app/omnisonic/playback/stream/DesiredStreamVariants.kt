package app.omnisonic.playback.stream

import app.omnisonic.playback.api.queue.PlaybackQueueEntry

/**
 * Supplies the desired offline stream variant (a `DownloadKeys.mediaKey`
 * source key) for one queue entry. Null means no exact preference, so the
 * resolver falls back to its deterministic newest-readable file instead of
 * guessing a variant.
 */
fun interface DesiredStreamVariants {
    suspend fun desiredKey(entry: PlaybackQueueEntry): String?
}
