package app.omnisonic.core.data.repository

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.aggregation.PlayAggregateSettings
import app.omnisonic.core.data.db.aggregation.recomputeAlbums
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.core.data.history.HistoryRecordingGate
import app.omnisonic.provider.api.ProviderMediaType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records listening outcomes. The songs row is materialised first so a play
 * from a queue/radio item never requires a synced row; locally-owned playback
 * state is then updated by id, and the affected album and artist rows are
 * recomputed from the song rows before the transaction commits. Eligibility
 * rules (95% threshold, resume points) stay in the playback layer that calls
 * this. A paused provider (D-7) writes nothing and returns null; existing
 * rows are kept.
 */
@Singleton
class PlayCounter @Inject constructor(
    private val db: OmnisonicDatabase,
    private val songRepository: SongRepository,
    private val outboxQueue: OutboxQueue,
    private val aggregateSettings: PlayAggregateSettings,
    private val recordingGate: HistoryRecordingGate,
) {

    suspend fun recordPlay(
        providerId: Long,
        externalId: String,
        snapshot: SongRepository.SongSnapshot,
        playedAtMs: Long,
        durationMs: Long,
        now: Long = playedAtMs,
    ): Long? {
        if (recordingGate.isPaused(providerId)) return null
        return db.withTransaction {
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
            refreshAggregates(songId)
            songId
        }
    }

    suspend fun recordSkip(
        providerId: Long,
        externalId: String,
        snapshot: SongRepository.SongSnapshot,
        playedAtMs: Long,
        durationMs: Long,
        now: Long = playedAtMs,
    ): Long? {
        if (recordingGate.isPaused(providerId)) return null
        return db.withTransaction {
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

    /**
     * Early track changes store the position they left at. The value is data,
     * not a start offset (D-18): playback never reads it back.
     */
    suspend fun recordResumePoint(
        providerId: Long,
        externalId: String,
        snapshot: SongRepository.SongSnapshot,
        positionMs: Long,
        now: Long,
    ): Long? {
        if (recordingGate.isPaused(providerId)) return null
        return db.withTransaction {
            val songId = songRepository.ensureSong(providerId, externalId, snapshot)
            db.songDao().setResumePoint(songId, resumeMs = positionMs, now = now)
            refreshAggregates(songId)
            songId
        }
    }

    /**
     * The album row carries the derived play count, last-played stamp and
     * resume track, so every write that changes one of its songs must refresh
     * it in the same transaction; the artist rows sum the same song counts.
     */
    private suspend fun refreshAggregates(songId: Long) {
        val song = db.songDao().selectById(songId) ?: return
        val options = aggregateSettings.options()
        song.albumId?.let { db.aggregateDao().recomputeAlbums(listOf(it), options.albumCountRule) }
        val artistIds = db.songArtistDao().selectBySong(songId).map { it.artistId }.distinct()
        if (artistIds.isNotEmpty()) {
            db.aggregateDao().recomputeArtistsById(artistIds, options.includeSupportingArtists)
        }
    }
}
