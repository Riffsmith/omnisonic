package app.omnisonic.core.data.repository

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.provider.api.ProviderMediaType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records listening outcomes. The songs row is materialised first so a play
 * from a queue/radio item never requires a synced row; locally-owned playback
 * state is then updated by id. Eligibility rules (95% threshold, resume points)
 * stay in the playback layer that calls this.
 */
@Singleton
class PlayCounter @Inject constructor(
    private val db: OmnisonicDatabase,
    private val songRepository: SongRepository,
    private val outboxQueue: OutboxQueue,
) {

    suspend fun recordPlay(
        providerId: Long,
        externalId: String,
        snapshot: SongRepository.SongSnapshot,
        playedAtMs: Long,
        durationMs: Long,
        now: Long = playedAtMs,
    ): Long = db.withTransaction {
        val songId = songRepository.ensureSong(providerId, externalId, snapshot)
        db.songDao().addPlayCount(songId, delta = 1, now = now)
        db.playEventDao().recordPlay(
            providerId = providerId,
            externalId = externalId,
            playedAtMs = playedAtMs,
            durationMs = durationMs,
        )
        outboxQueue.enqueuePlayCountDelta(
            providerId = providerId,
            mediaType = ProviderMediaType.SONG,
            externalId = externalId,
            playedAtMs = playedAtMs,
            now = now,
        )
        songId
    }

    suspend fun recordSkip(
        providerId: Long,
        externalId: String,
        snapshot: SongRepository.SongSnapshot,
        playedAtMs: Long,
        durationMs: Long,
        now: Long = playedAtMs,
    ): Long = db.withTransaction {
        val songId = songRepository.ensureSong(providerId, externalId, snapshot)
        db.songDao().markSkipped(songId, now = now)
        db.playEventDao().recordSkip(
            providerId = providerId,
            externalId = externalId,
            playedAtMs = playedAtMs,
            durationMs = durationMs,
        )
        songId
    }
}
