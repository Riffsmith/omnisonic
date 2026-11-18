package app.omnisonic.core.data.history

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.aggregation.PlayAggregateSettings
import app.omnisonic.core.data.db.aggregation.recomputeAlbumCounts
import app.omnisonic.core.data.db.aggregation.recomputeAlbumCountsForProvider
import app.omnisonic.core.data.db.aggregation.recomputeArtistCounts
import app.omnisonic.core.data.db.aggregation.recomputeArtistCountsForProvider
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.provider.api.OutboxActions
import app.omnisonic.provider.api.ProviderMediaType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/**
 * Write side of the listen-history controls (D-7, confirmed 2026-11-18).
 *
 * Every mutation runs in one transaction over the event row, the song's
 * locally-owned counters, the pending outbox contribution and the derived
 * album/artist counts (count-only: `resumeTrackPath` stays locally owned).
 * Server-side correction of an already accepted scrobble is out of reach —
 * Subsonic has no un-scrobble [INFERRED] — so deletes/edits are local plus
 * pending-outbox only.
 */
@Singleton
class HistoryEditor @Inject constructor(
    private val db: OmnisonicDatabase,
    private val outboxQueue: OutboxQueue,
    private val aggregateSettings: PlayAggregateSettings,
    private val recordingGate: HistoryRecordingGate,
) {

    val pausedProviders: StateFlow<Set<Long>> = recordingGate.pausedProviders

    fun isRecordingPaused(providerId: Long): Boolean = recordingGate.isPaused(providerId)

    fun setRecordingPaused(providerId: Long, paused: Boolean) = recordingGate.setPaused(providerId, paused)

    /**
     * Deletes one play event. A played event also drops its counter delta and
     * its timestamp from any unsubmitted outbox play-count row; a skip event
     * only loses its row (the song's skip marker is state, not a count).
     */
    suspend fun deleteEvent(eventId: Long): Boolean = db.withTransaction {
        val event = db.playEventDao().selectById(eventId) ?: return@withTransaction false
        db.playEventDao().deleteById(eventId)
        val song = db.songDao().selectByProviderAndExternal(event.providerId, event.externalId)
        if (song != null) {
            if (event.played) {
                db.songDao().removePlayCount(song.id)
                outboxQueue.removePlayTimestamp(
                    providerId = event.providerId,
                    mediaType = ProviderMediaType.SONG,
                    externalId = event.externalId,
                    playedAtMs = event.playedAtMs,
                )
            }
            refreshCounts(song.id)
        }
        true
    }

    /**
     * Sets a song's play count to an explicit value clamped at 0 and adjusts
     * the pending outbox delta by the same amount (floored at 0) so the
     * queued submission stays consistent with the edited count.
     */
    suspend fun setPlayCount(providerId: Long, externalId: String, count: Int) = db.withTransaction {
        val song = db.songDao().selectByProviderAndExternal(providerId, externalId)
            ?: return@withTransaction
        val previous = song.playCount
        val target = count.coerceAtLeast(0)
        db.songDao().setPlayCount(song.id, target, now = System.currentTimeMillis())
        if (target != previous) {
            outboxQueue.adjustPlayCountDelta(
                providerId = providerId,
                mediaType = ProviderMediaType.SONG,
                externalId = externalId,
                delta = target - previous,
            )
        }
        refreshCounts(song.id)
    }

    /**
     * Wipes one provider's history: its event rows, its songs' play state
     * (`playCount`/`lastPlayed`/`skipped`/`lastSkipped`) and its unsubmitted
     * play-count outbox rows. Ratings, favorites, resume points and other
     * providers are untouched.
     */
    suspend fun wipeProvider(providerId: Long) = db.withTransaction {
        db.playEventDao().deleteByProvider(providerId)
        db.songDao().resetPlayStateByProvider(providerId)
        db.outboxDao().deleteByProviderAndAction(providerId, OutboxActions.PLAY_COUNT)
        val options = aggregateSettings.options()
        db.aggregateDao().recomputeAlbumCountsForProvider(providerId, options.albumCountRule)
        db.aggregateDao().recomputeArtistCountsForProvider(providerId, options.includeSupportingArtists)
    }

    private suspend fun refreshCounts(songId: Long) {
        val song = db.songDao().selectById(songId) ?: return
        val options = aggregateSettings.options()
        song.albumId?.let { db.aggregateDao().recomputeAlbumCounts(listOf(it), options.albumCountRule) }
        val artistIds = db.songArtistDao().selectBySong(songId).map { it.artistId }.distinct()
        if (artistIds.isNotEmpty()) {
            db.aggregateDao().recomputeArtistCounts(artistIds, options.includeSupportingArtists)
        }
    }
}
