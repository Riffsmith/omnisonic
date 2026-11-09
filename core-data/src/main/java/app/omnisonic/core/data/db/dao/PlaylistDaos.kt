package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.ProfileScope
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

    /** Alphabetical browse page; hidden playlists never surface (ProfileScope convention). */
    @Query(
        """
        SELECT * FROM playlists
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
            AND hidden = 0
        ORDER BY title COLLATE NOCASE, id
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun browsePlaylistsRaw(excludedProviderIdsCsv: String, limit: Int, offset: Int): List<PlaylistEntity>

    suspend fun browsePlaylists(scope: ProfileScope, limit: Int, offset: Int): List<PlaylistEntity> =
        browsePlaylistsRaw(scope.excludedProviderIds.joinToString(","), limit, offset)

    @Query(
        """
        SELECT COUNT(*) FROM playlists
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
            AND hidden = 0
        """,
    )
    suspend fun countPlaylistsRaw(excludedProviderIdsCsv: String): Int

    suspend fun countPlaylists(scope: ProfileScope): Int =
        countPlaylistsRaw(scope.excludedProviderIds.joinToString(","))

    @Query("SELECT * FROM playlists WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): PlaylistEntity?

    @Query("SELECT MAX(lastSeenSyncId) FROM playlists WHERE providerId = :providerId")
    suspend fun selectMaxLastSeenSyncId(providerId: Long): Long?

    /**
     * Playlists of this provider not stamped by the current sync run; their
     * entries are removed too. Rows without any stamp are unseen too: the
     * caller (SubsonicLibrarySyncer.prune) may only reach pruning after the
     * FIX-02 safety guards confirmed the walk was complete.
     */
    @Query(
        """
        DELETE FROM playlists
        WHERE providerId = :providerId
            AND externalId IS NOT NULL
            AND (lastSeenSyncId IS NULL OR lastSeenSyncId != :runId)
        """,
    )
    suspend fun deleteUnseen(providerId: Long, runId: Long)

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

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    suspend fun selectByPlaylist(playlistId: Long): List<PlaylistEntryEntity>

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun countByPlaylist(playlistId: Long): Int

    @Query("SELECT MAX(position) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun selectMaxPosition(playlistId: Long): Int?

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId NOT IN (SELECT id FROM playlists)")
    suspend fun deleteOrphanedEntries()
}
