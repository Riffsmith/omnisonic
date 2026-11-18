package app.omnisonic.playback.history

import app.omnisonic.core.data.repository.PlayCounter
import app.omnisonic.core.data.repository.SongRepository
import app.omnisonic.core.data.sync.OutboxSyncControl
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production sink: delegates to the `:core-data` play counter, which owns the
 * transaction over the song row, the event log and the outbox row. The queue
 * snapshot supplies minimal metadata so a play from an unsynced (radio-less)
 * queue item still materialises a local row. After the transaction commits, a
 * drain is requested so the play leaves the device without waiting for the next
 * foreground edge.
 */
@Singleton
class CoreDataPlayHistorySink @Inject constructor(
    private val counter: PlayCounter,
    private val outboxSync: OutboxSyncControl,
) : PlayHistorySink {

    override suspend fun recordPlay(entry: PlaybackQueueEntry, playedAtMs: Long, durationMs: Long) {
        counter.recordPlay(
            providerId = entry.providerId,
            externalId = entry.externalId,
            snapshot = entry.snapshot(),
            playedAtMs = playedAtMs,
            durationMs = durationMs,
        )
        outboxSync.requestDrain()
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
