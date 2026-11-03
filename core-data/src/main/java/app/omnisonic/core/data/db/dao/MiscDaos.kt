package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.SavedFilterEntity
import app.omnisonic.core.data.db.entity.SyncStatusEntity

@Dao
interface SavedFilterDao {

    @Upsert
    suspend fun upsertRow(filter: SavedFilterEntity): Long

    @Transaction
    suspend fun upsert(filter: SavedFilterEntity): Long {
        val existingId = filter.id.takeIf { it != 0L }
            ?: selectByMediaTypeAndName(filter.mediaType, filter.name)?.id
        return if (existingId == null) {
            upsertRow(filter)
        } else {
            upsertRow(filter.copy(id = existingId))
            existingId
        }
    }

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
    suspend fun upsertRow(status: SyncStatusEntity)

    @Transaction
    suspend fun upsert(status: SyncStatusEntity) {
        val existingId = status.id.takeIf { it != 0L } ?: selectByProvider(status.providerId)?.id
        upsertRow(if (existingId == null) status else status.copy(id = existingId))
    }

    @Query("SELECT * FROM sync_state WHERE providerId = :providerId")
    suspend fun selectByProvider(providerId: Long): SyncStatusEntity?

    @Query("SELECT COUNT(*) FROM sync_state")
    suspend fun count(): Int
}
