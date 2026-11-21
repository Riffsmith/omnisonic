package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.dao.DownloadDao
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus

/**
 * Typed operations over [DownloadDao]. Every mutating read-modify-write is a
 * conditional statement carrying the observed status and scheduling fields, so
 * a stale worker or an independently constructed coordinator cannot settle a
 * row it no longer owns.
 */
class DownloadQueueRepository(private val dao: DownloadDao) {

    /** True when the row was inserted; a duplicate media key writes nothing. */
    suspend fun insertIfAbsent(item: DownloadItemEntity): Boolean =
        dao.insertIgnoring(item) != -1L

    suspend fun selectById(id: Long): DownloadItemEntity? = dao.selectById(id)

    suspend fun selectByMediaKey(mediaKey: String): DownloadItemEntity? = dao.selectByMediaKey(mediaKey)

    suspend fun selectVisible(scope: ProfileScope): List<DownloadItemEntity> = dao.selectAll(scope)

    /**
     * Claim candidates: known `PENDING` rows that are due, ordered by effective
     * due time then creation and id so retries and ties are deterministic.
     */
    suspend fun selectDue(scope: ProfileScope, nowMs: Long): List<DownloadItemEntity> =
        dao.selectByStatus(DownloadStatus.PENDING.name, scope)
            .asSequence()
            .filter { DownloadLifecycleRules.isDue(it.updatedAt, it.errorCount, nowMs) }
            .sortedWith(
                compareBy<DownloadItemEntity>(
                    { DownloadLifecycleRules.effectiveDueAtMs(it.updatedAt, it.errorCount) },
                    { it.createdAt },
                    { it.id },
                ),
            )
            .toList()

    suspend fun claim(row: DownloadItemEntity): Boolean =
        dao.claimIfPending(row.id, row.updatedAt, row.errorCount) == 1

    /**
     * Delay until the earliest pending retry, or null when no `PENDING` row
     * exists. Zero means work is already due; a caller that cannot claim it
     * must wait for an external trigger instead of polling.
     */
    suspend fun pendingDueDelayMs(scope: ProfileScope, nowMs: Long): Long? {
        val pending = dao.selectByStatus(DownloadStatus.PENDING.name, scope)
        if (pending.isEmpty()) return null
        val earliest = pending.minOf { DownloadLifecycleRules.effectiveDueAtMs(it.updatedAt, it.errorCount) }
        return (earliest - nowMs).coerceAtLeast(0L)
    }

    suspend fun pendingCount(scope: ProfileScope): Int =
        dao.selectByStatus(DownloadStatus.PENDING.name, scope).size

    /** `-1` stays the persisted indeterminate marker; otherwise the percent is bounded. */
    suspend fun reportProgress(id: Long, progressPercent: Int): Boolean {
        val bounded = if (progressPercent < 0) {
            DownloadLifecycleRules.PROGRESS_UNKNOWN
        } else {
            progressPercent.coerceIn(0, 100)
        }
        return dao.setProgressIfActive(id, bounded) == 1
    }

    /** Uncounted terminal park of a still-active row; a stale lease writes nothing. */
    suspend fun parkActive(id: Long, message: String?): Boolean =
        dao.parkIfActive(id, DownloadLifecycleRules.sanitizeFailureMessage(message)) == 1

    /**
     * Counted transfer failure: increments the counter, sanitizes the message,
     * stamps the scheduling anchor and targets `PENDING` or, on the sixth
     * failure, `FAILED`. Null when the row left `ACTIVE` or the counter moved
     * (retry/pause/cancel/newer lease won).
     */
    suspend fun recordFailure(
        id: Long,
        expectedErrorCount: Int,
        message: String?,
        failedAtMs: Long,
    ): DownloadStatus? {
        if (expectedErrorCount !in 0 until DownloadLifecycleRules.MAX_COUNTED_FAILURES) return null
        val nextCount = expectedErrorCount + 1
        val target = DownloadLifecycleRules.failureTarget(nextCount)
        val applied = dao.recordFailureIfActive(
            id = id,
            expectedErrorCount = expectedErrorCount,
            errorCount = nextCount,
            lastError = DownloadLifecycleRules.sanitizeFailureMessage(message),
            status = target.name,
            updatedAt = failedAtMs,
        )
        return target.takeIf { applied == 1 }
    }

    /** Cancellation recovery: `ACTIVE` only, no error increment, no anchor change. */
    suspend fun releaseActiveLease(id: Long, globallyPaused: Boolean): Boolean =
        dao.releaseActiveLease(id, DownloadLifecycleRules.resumeTarget(globallyPaused).name) == 1

    suspend fun recoverOrphanedActive(globallyPaused: Boolean): Int =
        dao.recoverOrphanedActive(DownloadLifecycleRules.resumeTarget(globallyPaused).name)

    suspend fun deleteIfActive(id: Long): Boolean = dao.deleteIfActive(id) == 1

    suspend fun statusOf(id: Long): String? = dao.selectById(id)?.status

    suspend fun pauseAllRows(): Int = dao.pauseClaimable()

    suspend fun pauseOne(id: Long): Boolean = dao.pauseOneIfClaimable(id) == 1

    suspend fun releaseGloballyPausedRows(): Int = dao.releaseGloballyPaused()

    suspend fun resumeOne(id: Long, globallyPaused: Boolean): Boolean =
        dao.resumeOneIfUserPaused(id, DownloadLifecycleRules.resumeTarget(globallyPaused).name) == 1

    suspend fun cancel(id: Long): Boolean = dao.deleteById(id) == 1

    /** Explicit destination change; false while a worker owns the row or the row is gone. */
    suspend fun convertDestination(id: Long, exportTarget: String?): Boolean =
        dao.convertDestinationIfSettled(id, exportTarget) == 1

    suspend fun retry(id: Long, globallyPaused: Boolean, retriedAtMs: Long): Boolean =
        dao.retryIfFailed(id, DownloadLifecycleRules.resumeTarget(globallyPaused).name, retriedAtMs) == 1

    suspend fun retryAllFailed(globallyPaused: Boolean, retriedAtMs: Long): Int =
        dao.retryAllFailed(DownloadLifecycleRules.resumeTarget(globallyPaused).name, retriedAtMs)
}
