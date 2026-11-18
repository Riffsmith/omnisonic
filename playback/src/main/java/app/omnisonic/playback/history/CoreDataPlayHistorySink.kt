package app.omnisonic.playback.history

import app.omnisonic.core.data.repository.PlayCounter
import app.omnisonic.core.data.repository.SongRepository
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production sink: delegates to the `:core-data` play counter, which owns the
 * transaction over the song row, the event log and the outbox row. The queue
 * snapshot supplies minimal metadata so a play from an unsynced (radio-less)
 * queue item still materialises a local row.
 */
@Singleton
class CoreDataPlayHistorySink @Inject constructor(
    private val counter: PlayCounter,
) : PlayHistorySink {

    override suspend fun recordPlay(entry: PlaybackQueueEntry, playedAtMs: Long, durationMs: Long) {
        counter.recordPlay(
            providerId = entry.providerId,
            externalId = entry.externalId,
            snapshot = entry.snapshot(),
            playedAtMs = playedAtMs,
            durationMs = durationMs,
        )
    }

    override suspend fun recordSkip(entry: PlaybackQueueEntry, playedAtMs: Long, durationMs: Long) {
        counter.recordSkip(
            providerId = entry.providerId,
            externalId = entry.externalId,
            snapshot = entry.snapshot(),
            playedAtMs = playedAtMs,
            durationMs = durationMs,
        )
    }

    override suspend fun saveResumePoint(entry: PlaybackQueueEntry, positionMs: Long, now: Long) {
        counter.recordResumePoint(
            providerId = entry.providerId,
            externalId = entry.externalId,
            snapshot = entry.snapshot(),
            positionMs = positionMs,
            now = now,
        )
    }

    private fun PlaybackQueueEntry.snapshot() = SongRepository.SongSnapshot(
        title = title,
        artistName = artistName,
        albumExternalId = albumExternalId,
        coverImageId = coverImageId,
        durationMs = durationMs,
    )
}
