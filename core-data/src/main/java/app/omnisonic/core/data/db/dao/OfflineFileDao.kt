package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.OfflineFileEntity

@Dao
interface OfflineFileDao {

    @Upsert
    suspend fun upsertRow(file: OfflineFileEntity): Long

    @Transaction
    suspend fun upsert(file: OfflineFileEntity): Long {
        val existingId = file.id.takeIf { it != 0L }
            ?: selectIdBySourceKey(file.sourceKey)
            ?: selectIdByOfflinePath(file.offlinePath)
        return if (existingId == null) {
            upsertRow(file)
        } else {
            upsertRow(file.copy(id = existingId))
            existingId
        }
    }

    @Query("SELECT id FROM offline_media WHERE sourceKey = :sourceKey")
    suspend fun selectIdBySourceKey(sourceKey: String): Long?

    @Query("SELECT id FROM offline_media WHERE offlinePath = :offlinePath")
    suspend fun selectIdByOfflinePath(offlinePath: String): Long?

    @Query("SELECT * FROM offline_media ORDER BY id")
    suspend fun selectAll(): List<OfflineFileEntity>

    @Query("SELECT * FROM offline_media WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<OfflineFileEntity>

    @Query("SELECT * FROM offline_media WHERE sourceKey = :sourceKey")
    suspend fun selectBySourceKey(sourceKey: String): OfflineFileEntity?

    @Query("SELECT COUNT(*) FROM offline_media")
    suspend fun count(): Int

    @Query("DELETE FROM offline_media WHERE sourceKey = :sourceKey")
    suspend fun deleteBySourceKey(sourceKey: String)
}
