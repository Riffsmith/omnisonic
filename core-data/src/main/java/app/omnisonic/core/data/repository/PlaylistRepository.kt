package app.omnisonic.core.data.repository

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Playlist edits materialise the song so an unsynced item still gets a row. */
@Singleton
class PlaylistRepository @Inject constructor(
    private val db: OmnisonicDatabase,
    private val songRepository: SongRepository,
) {

    suspend fun addSong(
        playlistId: Long,
        providerId: Long,
        externalId: String,
        snapshot: SongRepository.SongSnapshot,
        now: Long,
    ): Long = db.withTransaction {
        val playlistDao = db.playlistDao()
        val playlist = playlistDao.selectById(playlistId)
            ?: error("playlist $playlistId not found")
        require(playlist.providerId == providerId) {
            "playlist $playlistId belongs to provider ${playlist.providerId}, not $providerId"
        }
        val songId = songRepository.ensureSong(providerId, externalId, snapshot)
        val entryDao = db.playlistEntryDao()
        val nextOrder = entryDao.selectMaxEntryOrder(playlistId)?.plus(1) ?: 0
        val entryId = entryDao.upsert(
            PlaylistEntryEntity(
                playlistId = playlistId,
                providerId = providerId,
                entryOrder = nextOrder,
                externalId = externalId,
                songId = songId,
                title = snapshot.title,
                displayArtist = snapshot.artistName,
                durationMs = snapshot.durationMs,
                imagePath = snapshot.coverImageId,
                addedAt = now,
            ),
        )
        playlistDao.recountEntries(playlistId)
        entryId
    }
}
