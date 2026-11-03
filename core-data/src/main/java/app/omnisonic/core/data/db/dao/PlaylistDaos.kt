package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity

@Dao
interface PlaylistDao {

    @Upsert
    suspend fun upsertRow(playlist: PlaylistEntity): Long

    @Transaction
    suspend fun upsert(playlist: PlaylistEntity): Long {
        val existingId = playlist.id.takeIf { it != 0L }
            ?: playlist.externalId?.let { selectByProviderAndExternal(playlist.providerId, it)?.id }
        return if (existingId == null) {
            upsertRow(playlist)
        } else {
            upsertRow(playlist.copy(id = existingId))
            existingId
        }
    }

    @Upsert
    suspend fun upsertRows(playlists: List<PlaylistEntity>)

    @Transaction
    suspend fun upsertAll(playlists: List<PlaylistEntity>) {
        playlists.forEach { upsert(it) }
    }

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun selectAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): PlaylistEntity?

    /** Playlists of this provider not seen in a full sync; their entries are removed too. */
    @Query(
        """
        DELETE FROM playlists
        WHERE providerId = :providerId
            AND externalId IS NOT NULL
            AND externalId NOT IN (:seenExternalIds)
        """,
    )
    suspend fun deleteUnseen(providerId: Long, seenExternalIds: List<String>)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun selectById(id: Long): PlaylistEntity?

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE playlists SET entryCount = (SELECT COUNT(*) FROM playlist_items WHERE playlist_items.playlistId = playlists.id) WHERE playlists.id = :id")
    suspend fun recountEntries(id: Long)
}

@Dao
interface PlaylistEntryDao {

    @Upsert
    suspend fun upsert(entry: PlaylistEntryEntity): Long

    @Upsert
    suspend fun upsertAll(entries: List<PlaylistEntryEntity>)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY entryOrder")
    suspend fun selectByPlaylist(playlistId: Long): List<PlaylistEntryEntity>

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun countByPlaylist(playlistId: Long): Int

    @Query("SELECT MAX(entryOrder) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun selectMaxEntryOrder(playlistId: Long): Int?

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId NOT IN (SELECT id FROM playlists)")
    suspend fun deleteOrphanedEntries()
}
