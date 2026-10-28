package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.omnisonic.core.data.db.entity.GenreEntity

@Dao
interface GenreDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(genre: GenreEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(genres: List<GenreEntity>)

    @Query("SELECT * FROM genres ORDER BY id")
    suspend fun selectAll(): List<GenreEntity>

    @Query("SELECT * FROM genres WHERE providerId = :providerId ORDER BY title")
    suspend fun selectByProvider(providerId: Long): List<GenreEntity>

    @Query("DELETE FROM genres WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM genres")
    suspend fun deleteAll()
}
