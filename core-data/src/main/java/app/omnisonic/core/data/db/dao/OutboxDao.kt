package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.entity.OutboxItemEntity
import kotlinx.coroutines.flow.Flow

/** Pending-row count per provider for the settings "pending sync" view. */
data class OutboxProviderCountRow(
    val providerId: Long,
    val pendingCount: Int,
    val failingCount: Int,
)

/** Most-backed-off failure per provider; shown as that provider's last error. */
data class OutboxProviderErrorRow(
    val providerId: Long,
    val lastError: String,
)

@Dao
interface OutboxDao {

    @Upsert
    suspend fun upsertRow(item: OutboxItemEntity)

    @Transaction
    suspend fun upsert(item: OutboxItemEntity) {
        val existingId = item.id.takeIf { it != 0L } ?: selectTarget(
            item.providerId, item.mediaType, item.externalId, item.action,
        )?.id
        upsertRow(if (existingId == null) item else item.copy(id = existingId))
    }

    @Query(
        """
        SELECT * FROM outbox_items
        WHERE providerId = :providerId
        ORDER BY nextAttemptAt, createdAt, id
        """,
    )
    suspend fun selectByProvider(providerId: Long): List<OutboxItemEntity>

    @Query(
        """
        SELECT * FROM outbox_items
        WHERE providerId = :providerId AND nextAttemptAt <= :now
        ORDER BY nextAttemptAt, createdAt, id
        """,
    )
    suspend fun selectDue(providerId: Long, now: Long): List<OutboxItemEntity>

    @Query(
        """
        SELECT * FROM outbox_items
        WHERE nextAttemptAt <= :now
        ORDER BY nextAttemptAt, createdAt, id
        """,
    )
    suspend fun selectAllDue(now: Long): List<OutboxItemEntity>

    @Query("UPDATE outbox_items SET payloadJson = :payloadJson, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun recordProgress(id: Long, payloadJson: String?, nextAttemptAt: Long)

    @Query(
        """
        SELECT * FROM outbox_items
        WHERE providerId = :providerId AND mediaType = :mediaType AND externalId = :externalId AND action = :action
        """,
    )
    suspend fun selectTarget(providerId: Long, mediaType: String, externalId: String, action: String): OutboxItemEntity?

    @Query("UPDATE outbox_items SET attempts = :attempts, nextAttemptAt = :nextAttemptAt, lastError = :lastError WHERE id = :id")
    suspend fun recordFailure(id: Long, attempts: Int, nextAttemptAt: Long, lastError: String)

    @Query("SELECT * FROM outbox_items WHERE id = :id")
    suspend fun selectById(id: Long): OutboxItemEntity?

    @Query("DELETE FROM outbox_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Deletes a row only while it still carries the payload the drain
     * submitted; returns the number of deleted rows (0 when a concurrent
     * enqueue replaced the payload).
     */
    @Query(
        """
        DELETE FROM outbox_items
        WHERE id = :id
        AND ((payloadJson IS NULL AND :payloadJson IS NULL) OR payloadJson = :payloadJson)
        """,
    )
    suspend fun deleteByIdAndPayload(id: Long, payloadJson: String?): Int

    @Query("DELETE FROM outbox_items")
    suspend fun deleteAll()

    /**
     * Parked rows become due again for a manual retry. The attempts counter is
     * reset so the next failure starts from the base back-off; `lastError`
     * stays until the row is cleaned up successfully.
     */
    @Query("UPDATE outbox_items SET attempts = 0, nextAttemptAt = 0")
    suspend fun clearAllBackoff()

    @Query(
        """
        SELECT providerId,
               COUNT(*) AS pendingCount,
               SUM(CASE WHEN lastError IS NOT NULL THEN 1 ELSE 0 END) AS failingCount
        FROM outbox_items
        GROUP BY providerId
        """,
    )
    fun observeProviderCounts(): Flow<List<OutboxProviderCountRow>>

    @Query(
        """
        SELECT o.providerId AS providerId, o.lastError AS lastError
        FROM outbox_items o
        WHERE o.lastError IS NOT NULL
          AND o.id = (
              SELECT i.id FROM outbox_items i
              WHERE i.providerId = o.providerId AND i.lastError IS NOT NULL
              ORDER BY i.nextAttemptAt DESC, i.id DESC
              LIMIT 1
          )
        """,
    )
    fun observeProviderErrors(): Flow<List<OutboxProviderErrorRow>>

    @Query("SELECT COUNT(*) FROM outbox_items")
    suspend fun count(): Int

    @Query(
        """
        SELECT COUNT(*) FROM outbox_items
        WHERE providerId = :providerId AND externalId = :externalId
        """,
    )
    suspend fun countPendingByExternal(providerId: Long, externalId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM outbox_items
        WHERE providerId = :providerId AND externalId = :externalId AND action = :action
        """,
    )
    suspend fun countPendingByExternalAndAction(providerId: Long, externalId: String, action: String): Int
}
