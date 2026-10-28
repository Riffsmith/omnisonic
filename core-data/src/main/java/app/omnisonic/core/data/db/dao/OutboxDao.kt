package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.OutboxItemEntity

@Dao
interface OutboxDao {

    @Upsert
    suspend fun upsert(item: OutboxItemEntity)

    @Query(
        """
        SELECT * FROM outbox_items
        WHERE providerId = :providerId
        ORDER BY nextAttemptAt, createdAt
        """,
    )
    suspend fun selectByProvider(providerId: Long): List<OutboxItemEntity>

    @Query(
        """
        SELECT * FROM outbox_items
        WHERE providerId = :providerId AND nextAttemptAt <= :now
        ORDER BY nextAttemptAt
        """,
    )
    suspend fun selectDue(providerId: Long, now: Long): List<OutboxItemEntity>

    @Query(
        """
        SELECT * FROM outbox_items
        WHERE providerId = :providerId AND mediaType = :mediaType AND externalId = :externalId AND action = :action
        """,
    )
    suspend fun selectTarget(providerId: Long, mediaType: String, externalId: String, action: String): OutboxItemEntity?

    @Query("UPDATE outbox_items SET attempts = :attempts, nextAttemptAt = :nextAttemptAt, lastError = :lastError WHERE id = :id")
    suspend fun recordFailure(id: Long, attempts: Int, nextAttemptAt: Long, lastError: String)

    @Query("DELETE FROM outbox_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM outbox_items")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM outbox_items")
    suspend fun count(): Int
}
