package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.SavedFilterEntity
import app.omnisonic.core.data.db.entity.SyncStatusEntity

@Dao
interface SavedFilterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(filter: SavedFilterEntity): Long

    @Query("SELECT * FROM saved_filters ORDER BY name")
    suspend fun selectAll(): List<SavedFilterEntity>

    @Query("SELECT * FROM saved_filters WHERE mediaType = :mediaType AND name = :name")
    suspend fun selectByMediaTypeAndName(mediaType: String, name: String): SavedFilterEntity?

    @Query("DELETE FROM saved_filters WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SyncStatusDao {

    @Upsert
    suspend fun upsert(status: SyncStatusEntity)

    @Query("SELECT * FROM sync_status WHERE providerId = :providerId")
    suspend fun selectByProvider(providerId: Long): SyncStatusEntity?

    @Query("SELECT COUNT(*) FROM sync_status")
    suspend fun count(): Int
}
