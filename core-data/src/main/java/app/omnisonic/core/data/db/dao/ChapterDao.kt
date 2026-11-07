package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.ChapterEntity

@Dao
interface ChapterDao {

    @Upsert
    suspend fun upsert(chapter: ChapterEntity): Long

    @Upsert
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE songId = :songId ORDER BY chapterIndex")
    suspend fun selectBySong(songId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE albumId = :albumId ORDER BY chapterIndex")
    suspend fun selectByAlbum(albumId: Long): List<ChapterEntity>

    @Query("DELETE FROM chapters WHERE songId = :songId")
    suspend fun deleteBySong(songId: Long)

    @Query("DELETE FROM chapters WHERE albumId = :albumId")
    suspend fun deleteByAlbum(albumId: Long)

    @Query("DELETE FROM chapters WHERE songId IN (:ids)")
    suspend fun deleteBySongIdsChunk(ids: List<Long>)

    @Transaction
    suspend fun deleteBySongIds(ids: List<Long>) {
        ids.forEachSqlChunk { deleteBySongIdsChunk(it) }
    }

    @Query("DELETE FROM chapters WHERE albumId IN (:ids)")
    suspend fun deleteByAlbumIdsChunk(ids: List<Long>)

    @Transaction
    suspend fun deleteByAlbumIds(ids: List<Long>) {
        ids.forEachSqlChunk { deleteByAlbumIdsChunk(it) }
    }
}
