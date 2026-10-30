package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.omnisonic.core.data.db.entity.SongEntity

@Dao
interface SongDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("SELECT * FROM songs ORDER BY id")
    suspend fun selectAll(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY id")
    suspend fun selectByAlbum(albumId: Long): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun selectById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE providerId = :providerId AND externalId = :externalId")
    suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): SongEntity?

    @Query("SELECT * FROM songs WHERE providerId = :providerId AND filePath = :filePath")
    suspend fun selectByProviderAndFilePath(providerId: Long, filePath: String): SongEntity?

    @Query("UPDATE songs SET playCount = playCount + :delta, lastPlayed = :now WHERE id = :id")
    suspend fun addPlayCount(id: Long, delta: Int, now: Long)

    @Query("UPDATE songs SET resumePointMs = :resumePointMs, lastPlayed = :now WHERE id = :id")
    suspend fun setResumePoint(id: Long, resumePointMs: Long, now: Long)

    @Query("UPDATE songs SET resumePointMs = :resumePointMs WHERE id = :id")
    suspend fun applyProviderResumePoint(id: Long, resumePointMs: Long?)

    @Query("UPDATE songs SET userRating = :rating WHERE id = :id")
    suspend fun setUserRating(id: Long, rating: Int?)

    @Query("UPDATE songs SET isFavorite = :favorite, favoriteAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, now: Long)

    @Query("UPDATE songs SET skipped = 1, lastSkipped = :now WHERE id = :id")
    suspend fun markSkipped(id: Long, now: Long)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()
}
