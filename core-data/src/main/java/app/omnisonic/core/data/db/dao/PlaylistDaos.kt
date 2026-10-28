package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(playlists: List<PlaylistEntity>)

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun selectAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun selectById(id: Long): PlaylistEntity?

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE playlists SET entryCount = (SELECT COUNT(*) FROM playlists_entries WHERE playlists_entries.playlistId = playlists.id) WHERE playlists.id = :id")
    suspend fun recountEntries(id: Long)
}

@Dao
interface PlaylistEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PlaylistEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<PlaylistEntryEntity>)

    @Query("SELECT * FROM playlists_entries WHERE playlistId = :playlistId ORDER BY entryOrder")
    suspend fun selectByPlaylist(playlistId: Long): List<PlaylistEntryEntity>

    @Query("SELECT COUNT(*) FROM playlists_entries WHERE playlistId = :playlistId")
    suspend fun countByPlaylist(playlistId: Long): Int

    @Query("DELETE FROM playlists_entries WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylist(playlistId: Long)
}
