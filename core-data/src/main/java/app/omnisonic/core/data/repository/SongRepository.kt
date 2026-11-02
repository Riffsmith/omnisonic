package app.omnisonic.core.data.repository

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.entity.SongEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Materialises songs from degraded sources (queue snapshots, play events, UI
 * intent) so playback history and queue persistence never depend on a synced
 * `songs` row. Idempotent: an existing row keeps its id, and the snapshot
 * only fills columns that are still NULL — locally-owned playback state and
 * fully-synced metadata are never overwritten.
 */
@Singleton
class SongRepository @Inject constructor(
    private val db: OmnisonicDatabase,
) {

    /** Minimal identity for a song that may have no synced row yet. */
    data class SongSnapshot(
        val title: String,
        val artistName: String? = null,
        val albumExternalId: String? = null,
        val coverImageId: String? = null,
        val durationMs: Long? = null,
    )

    suspend fun ensureSong(providerId: Long, externalId: String, snapshot: SongSnapshot): Long =
        db.withTransaction {
            val songDao = db.songDao()
            val albumId = snapshot.albumExternalId
                ?.let { db.albumDao().selectIdByProviderAndExternal(providerId, it) }
            songDao.insertOrIgnore(
                SongEntity(
                    providerId = providerId,
                    externalId = externalId,
                    albumId = albumId,
                    title = snapshot.title,
                    displayArtist = snapshot.artistName,
                    imagePath = snapshot.coverImageId,
                    durationMs = snapshot.durationMs,
                    // Epoch marks "never synced"; the syncer overwrites it with
                    // the server value on the next pass.
                    updatedAt = 0,
                ),
            )
            songDao.fillSnapshotFields(
                providerId = providerId,
                externalId = externalId,
                albumId = albumId,
                title = snapshot.title,
                displayArtist = snapshot.artistName,
                imagePath = snapshot.coverImageId,
                durationMs = snapshot.durationMs,
            )
            songDao.selectIdByProviderAndExternal(providerId, externalId)
                ?: error("song row missing after materialisation: provider $providerId external $externalId")
        }
}
