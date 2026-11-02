package app.omnisonic.core.data.repository

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.queue.OutboxPayload
import app.omnisonic.core.data.db.queue.OutboxQueue
import javax.inject.Inject
import javax.inject.Singleton

/** Sets the locally-owned favorite flag, materialising the song first. */
@Singleton
class FavoritesRepository @Inject constructor(
    private val db: OmnisonicDatabase,
    private val songRepository: SongRepository,
    private val outboxQueue: OutboxQueue,
) {

    suspend fun setFavorite(
        providerId: Long,
        externalId: String,
        snapshot: SongRepository.SongSnapshot,
        favorite: Boolean,
        now: Long,
    ): Long = db.withTransaction {
        val songId = songRepository.ensureSong(providerId, externalId, snapshot)
        db.songDao().setFavorite(songId, favorite = favorite, now = now)
        outboxQueue.enqueueMediaChange(
            providerId = providerId,
            mediaType = MEDIA_TYPE,
            externalId = externalId,
            action = OutboxQueue.ACTION_FAVORITE,
            payload = OutboxPayload(isFavorite = favorite),
            now = now,
        )
        songId
    }

    private companion object {
        const val MEDIA_TYPE = "track"
    }
}
