package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.omnisonic.core.data.db.ProfileScope
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

    /** Insert-or-ignore is the strict idempotence rule: an existing key is never rewritten. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(item: DownloadItemEntity): Long

    @Upsert
    suspend fun upsertRows(items: List<DownloadItemEntity>)

    @Transaction
    suspend fun upsertAll(items: List<DownloadItemEntity>) {
        items.forEach { upsert(it) }
    }

    @Query(
        """
        SELECT * FROM download_queue
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        ORDER BY createdAt, id
        """,
    )
    suspend fun selectAllRaw(excludedProviderIdsCsv: String): List<DownloadItemEntity>

    suspend fun selectAll(scope: ProfileScope): List<DownloadItemEntity> =
        selectAllRaw(scope.excludedProviderIds.joinToString(","))

    @Query(
        """
        SELECT * FROM download_queue
        WHERE status = :status
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        ORDER BY createdAt, id
        """,
    )
    suspend fun selectByStatusRaw(status: String, excludedProviderIdsCsv: String): List<DownloadItemEntity>

    suspend fun selectByStatus(status: String, scope: ProfileScope): List<DownloadItemEntity> =
        selectByStatusRaw(status, scope.excludedProviderIds.joinToString(","))

    @Query("SELECT * FROM download_queue WHERE mediaKey = :mediaKey")
    suspend fun selectByMediaKey(mediaKey: String): DownloadItemEntity?

    @Query("SELECT * FROM download_queue WHERE id = :id")
    suspend fun selectById(id: Long): DownloadItemEntity?

    @Query("SELECT * FROM download_queue WHERE providerId = :providerId ORDER BY createdAt, id")
    suspend fun selectByProvider(providerId: Long): List<DownloadItemEntity>

    /**
     * Conditional claim: only a still-`PENDING` row whose scheduling anchor is
     * unchanged becomes `ACTIVE`. Claiming never touches `updatedAt`, so a
     * claim neither resets nor extends a retry delay.
     */
    @Query(
        """
        UPDATE download_queue SET status = 'ACTIVE'
        WHERE id = :id AND status = 'PENDING'
            AND updatedAt = :expectedUpdatedAt AND errorCount = :expectedErrorCount
        """,
    )
    suspend fun claimIfPending(id: Long, expectedUpdatedAt: Long, expectedErrorCount: Int): Int

    /** `-1` is the persisted indeterminate marker; otherwise the caller bounds the percent. */
    @Query("UPDATE download_queue SET progressPercent = :progressPercent WHERE id = :id AND status = 'ACTIVE'")
    suspend fun setProgressIfActive(id: Long, progressPercent: Int): Int

    /**
     * Terminal fail-closed park: `ACTIVE -> FAILED` without an error increment
     * and without touching the retry anchor. Used for permanent conditions
     * (unsupported or vanished provider, unplannable path) that must not spin a
     * retry loop; an explicit retry revives the row.
     */
    @Query("UPDATE download_queue SET status = 'FAILED', lastError = :lastError WHERE id = :id AND status = 'ACTIVE'")
    suspend fun parkIfActive(id: Long, lastError: String?): Int

    @Query(
        """
        UPDATE download_queue
        SET errorCount = :errorCount, lastError = :lastError, status = :status, updatedAt = :updatedAt
        WHERE id = :id AND status = 'ACTIVE' AND errorCount = :expectedErrorCount
        """,
    )
    suspend fun recordFailureIfActive(
        id: Long,
        expectedErrorCount: Int,
        errorCount: Int,
        lastError: String?,
        status: String,
        updatedAt: Long,
    ): Int

    /** Global pause parks claimable work; sticky user pauses and parked failures are untouched. */
    @Query("UPDATE download_queue SET status = 'PAUSED' WHERE status IN ('PENDING', 'ACTIVE')")
    suspend fun pauseClaimable(): Int

    @Query("UPDATE download_queue SET status = 'USER_PAUSED' WHERE id = :id AND status IN ('PENDING', 'PAUSED', 'ACTIVE')")
    suspend fun pauseOneIfClaimable(id: Long): Int

    @Query("UPDATE download_queue SET status = 'PENDING' WHERE status = 'PAUSED'")
    suspend fun releaseGloballyPaused(): Int

    @Query("UPDATE download_queue SET status = :target WHERE id = :id AND status = 'USER_PAUSED'")
    suspend fun resumeOneIfUserPaused(id: Long, target: String): Int

    @Query("UPDATE download_queue SET status = :target WHERE id = :id AND status = 'ACTIVE'")
    suspend fun releaseActiveLease(id: Long, target: String): Int

    @Query("UPDATE download_queue SET status = :target WHERE status = 'ACTIVE'")
    suspend fun recoverOrphanedActive(target: String): Int

    @Query(
        """
        UPDATE download_queue
        SET errorCount = 0, lastError = NULL, status = :target, updatedAt = :updatedAt
        WHERE id = :id AND status = 'FAILED'
        """,
    )
    suspend fun retryIfFailed(id: Long, target: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE download_queue
        SET errorCount = 0, lastError = NULL, status = :target, updatedAt = :updatedAt
        WHERE status = 'FAILED'
        """,
    )
    suspend fun retryAllFailed(target: String, updatedAt: Long): Int

    /** Cancel deletes known-state rows only; an unrecognized raw status is left untouched. */
    @Query(
        """
        DELETE FROM download_queue
        WHERE id = :id AND status IN ('PENDING', 'PAUSED', 'USER_PAUSED', 'ACTIVE', 'FAILED')
        """,
    )
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM download_queue WHERE id = :id AND status = 'ACTIVE'")
    suspend fun deleteIfActive(id: Long): Int

    @Query("DELETE FROM download_queue")
    suspend fun deleteAll()
}
