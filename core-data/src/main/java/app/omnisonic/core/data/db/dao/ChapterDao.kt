package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.omnisonic.core.data.db.entity.ChapterEntity

@Dao
interface ChapterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chapter: ChapterEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE songId = :songId ORDER BY chapterIndex")
    suspend fun selectBySong(songId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE albumId = :albumId ORDER BY chapterIndex")
    suspend fun selectByAlbum(albumId: Long): List<ChapterEntity>

    @Query("DELETE FROM chapters WHERE songId = :songId")
    suspend fun deleteBySong(songId: Long)

    @Query("DELETE FROM chapters WHERE albumId = :albumId")
    suspend fun deleteByAlbum(albumId: Long)
}
