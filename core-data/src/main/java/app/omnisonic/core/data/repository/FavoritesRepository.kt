package app.omnisonic.core.data.repository

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.provider.api.OutboxActionPayload
import app.omnisonic.provider.api.OutboxActions
import app.omnisonic.provider.api.ProviderMediaType
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
            mediaType = ProviderMediaType.SONG,
            externalId = externalId,
            action = OutboxActions.FAVORITE,
            payload = OutboxActionPayload(isFavorite = favorite),
            now = now,
        )
        songId
    }
}
