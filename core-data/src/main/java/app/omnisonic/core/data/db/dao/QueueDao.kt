package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.QueueEntryEntity
import app.omnisonic.core.data.db.entity.QueueStateEntity

@Dao
interface QueueDao {

    @Insert
    suspend fun insertEntries(entries: List<QueueEntryEntity>)

    @Query("SELECT * FROM queue_entries ORDER BY position")
    suspend fun selectEntries(): List<QueueEntryEntity>

    @Query("SELECT * FROM queue_entries WHERE providerId = :providerId ORDER BY position")
    suspend fun selectEntriesByProvider(providerId: Long): List<QueueEntryEntity>

    @Query("SELECT COUNT(*) FROM queue_entries")
    suspend fun countEntries(): Int

    @Query("DELETE FROM queue_entries")
    suspend fun deleteEntries()

    @Upsert
    suspend fun upsertState(state: QueueStateEntity)

    @Query("SELECT * FROM queue_state WHERE id = :id")
    suspend fun selectState(id: Int): QueueStateEntity?

    @Query("DELETE FROM queue_state")
    suspend fun deleteState()
}
