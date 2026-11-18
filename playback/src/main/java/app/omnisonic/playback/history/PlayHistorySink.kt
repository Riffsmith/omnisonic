package app.omnisonic.playback.history

import app.omnisonic.playback.api.queue.PlaybackQueueEntry

/**
 * Write seam for the recorder. Production binds the `:core-data` play counter
 * (one Room transaction per row); tests use an in-memory fake, so eligibility
 * ordering is testable without a database.
 */
interface PlayHistorySink {
    suspend fun recordPlay(entry: PlaybackQueueEntry, playedAtMs: Long, durationMs: Long)
    suspend fun recordSkip(entry: PlaybackQueueEntry, playedAtMs: Long, durationMs: Long)
    suspend fun saveResumePoint(entry: PlaybackQueueEntry, positionMs: Long, now: Long)
}
