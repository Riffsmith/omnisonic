package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.omnisonic.core.data.db.entity.OfflineFileEntity

@Dao
interface OfflineFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: OfflineFileEntity): Long

    @Query("SELECT * FROM offline_files ORDER BY id")
    suspend fun selectAll(): List<OfflineFileEntity>

    @Query("SELECT * FROM offline_files WHERE providerId = :providerId ORDER BY id")
    suspend fun selectByProvider(providerId: Long): List<OfflineFileEntity>

    @Query("SELECT * FROM offline_files WHERE sourceKey = :sourceKey")
    suspend fun selectBySourceKey(sourceKey: String): OfflineFileEntity?

    @Query("SELECT COUNT(*) FROM offline_files")
    suspend fun count(): Int

    @Query("DELETE FROM offline_files WHERE sourceKey = :sourceKey")
    suspend fun deleteBySourceKey(sourceKey: String)
}
