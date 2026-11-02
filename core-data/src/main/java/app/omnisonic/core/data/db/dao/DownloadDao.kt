package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.DownloadItemEntity

@Dao
interface DownloadDao {

    @Upsert
    suspend fun upsertRow(item: DownloadItemEntity): Long

    @Transaction
    suspend fun upsert(item: DownloadItemEntity): Long {
        val existingId = item.id.takeIf { it != 0L } ?: selectByMediaKey(item.mediaKey)?.id
        return if (existingId == null) {
            upsertRow(item)
        } else {
            upsertRow(item.copy(id = existingId))
            existingId
        }
    }

    /** Insert-or-ignore so re-enqueues never reset an in-progress download. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(item: DownloadItemEntity): Long

    @Upsert
    suspend fun upsertRows(items: List<DownloadItemEntity>)

    @Transaction
    suspend fun upsertAll(items: List<DownloadItemEntity>) {
        items.forEach { upsert(it) }
    }

    @Query("SELECT * FROM download_items ORDER BY createdAt")
    suspend fun selectAll(): List<DownloadItemEntity>

    @Query("SELECT * FROM download_items WHERE status = :status ORDER BY createdAt")
    suspend fun selectByStatus(status: String): List<DownloadItemEntity>

    @Query("SELECT * FROM download_items WHERE mediaKey = :mediaKey")
    suspend fun selectByMediaKey(mediaKey: String): DownloadItemEntity?

    @Query("SELECT * FROM download_items WHERE providerId = :providerId ORDER BY createdAt")
    suspend fun selectByProvider(providerId: Long): List<DownloadItemEntity>

    @Query("UPDATE download_items SET progressPercent = :progressPercent, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setProgress(id: Long, progressPercent: Int, updatedAt: Long)

    @Query("UPDATE download_items SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, updatedAt: Long)

    @Query("UPDATE download_items SET errorCount = errorCount + 1, lastError = :lastError, status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun recordError(id: Long, lastError: String, status: String, updatedAt: Long)

    @Query("DELETE FROM download_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download_items")
    suspend fun deleteAll()
}
