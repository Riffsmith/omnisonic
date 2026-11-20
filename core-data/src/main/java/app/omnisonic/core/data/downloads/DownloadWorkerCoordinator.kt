package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A worker-owned claim. [token] is process-unique and fences every later
 * progress, failure, completion and release write: a claim is valid only while
 * the singleton coordinator still maps [rowId] to this token, and each write
 * additionally requires the row to remain `ACTIVE` (closes the
 * `ACTIVE → pause → resume → ACTIVE` ABA race).
 */
data class DownloadLease(
    val rowId: Long,
    val providerId: Long,
    val token: Long,
    val errorCount: Int,
)

/** Why an active transfer must stop before it finishes: pause preserves `.part`, cancel deletes it. */
enum class DownloadInterruption { PAUSE, CANCEL }

/**
 * Process-singleton queue mutation and claiming coordinator
 * (`.agents/plans/phase-7-tasks.md` §claim and race protocol). One mutex
 * serializes enqueue/global-pause mutations and select-then-claim, so a
 * concurrent enqueue cannot slip through the global-pause check and a worker
 * never exceeds [workerCount]. Leases stay registered until the worker has
 * finished settlement and called [release], so a paused-but-not-yet-exited
 * transfer keeps consuming its slot.
 *
 * This type claims and fences only; it does not transfer bytes, own files or
 * start services.
 */
class DownloadWorkerCoordinator(
    private val repository: DownloadQueueRepository,
    private val settings: DownloadQueueSettingsStore,
    private val runGate: ProviderRunGate,
    workerCount: Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()
    private val leasesByRow = LinkedHashMap<Long, DownloadLease>()
    private val interruptions = LinkedHashMap<Long, CompletableDeferred<DownloadInterruption?>>()
    private val nextToken = AtomicLong()

    val workerCount: Int = DownloadLifecycleRules.sanitizeWorkerCount(workerCount)

    suspend fun activeLeaseCount(): Int = mutex.withLock { leasesByRow.size }

    /**
     * The status on [item] is ignored: this coordinator owns the initial state
     * (`PENDING`, or `PAUSED` while the global gate is set) and checks the gate
     * under the same lock as the insert.
     */
    suspend fun enqueue(item: DownloadItemEntity): Boolean = mutex.withLock {
        val paused = settings.isGloballyPaused()
        repository.insertIfAbsent(item.copy(status = DownloadLifecycleRules.enqueueStatus(paused).name))
    }

    /** Runs before workers start and reconciles both sides of the persisted gate/row write order. */
    suspend fun recoverOnStartup(): Int = mutex.withLock {
        val paused = settings.isGloballyPaused()
        val recovered = repository.recoverOrphanedActive(paused)
        if (paused) repository.pauseAllRows()
        recovered
    }

    /**
     * Claims at most one due row. Internal callers pass
     * [ProfileScope.Unrestricted] explicitly; a UI read passes the real scope.
     * Blocked providers are skipped without mutation and later providers are
     * still considered.
     */
    suspend fun claimNext(scope: ProfileScope): DownloadLease? = mutex.withLock {
        if (leasesByRow.size >= workerCount || settings.isGloballyPaused()) return@withLock null
        val now = nowMs()
        for (row in repository.selectDue(scope, now)) {
            if (leasesByRow.containsKey(row.id)) continue
            if (runGate.check(row.providerId) != ProviderStatus.OK) continue
            if (!repository.claim(row)) continue
            val lease = DownloadLease(
                rowId = row.id,
                providerId = row.providerId,
                token = nextToken.incrementAndGet(),
                errorCount = row.errorCount,
            )
            leasesByRow[row.id] = lease
            interruptions[row.id] = CompletableDeferred()
            return@withLock lease
        }
        null
    }

    suspend fun reportProgress(lease: DownloadLease, progressPercent: Int): Boolean = mutex.withLock {
        if (!isCurrent(lease)) return@withLock false
        repository.reportProgress(lease.rowId, progressPercent)
    }

    suspend fun recordFailure(lease: DownloadLease, message: String?): DownloadStatus? = mutex.withLock {
        if (!isCurrent(lease)) return@withLock null
        repository.recordFailure(lease.rowId, lease.errorCount, message, nowMs())
    }

    /** Successful settlement: the queue row is deleted, never kept as a state. */
    suspend fun complete(lease: DownloadLease): Boolean = mutex.withLock {
        if (!isCurrent(lease)) return@withLock false
        val deleted = repository.deleteIfActive(lease.rowId)
        if (deleted) {
            interruptions.remove(lease.rowId)?.complete(null)
            leasesByRow.remove(lease.rowId)
        }
        deleted
    }

    /**
     * Terminal fail-closed stop without an error increment (unsupported or
     * vanished provider, unplannable path). Returning the row to `PENDING`
     * would loop forever on the same permanent condition, so it parks `FAILED`
     * and only an explicit retry revives it.
     */
    suspend fun park(lease: DownloadLease, message: String?): Boolean = mutex.withLock {
        if (!isCurrent(lease)) return@withLock false
        val applied = repository.parkActive(lease.rowId, message)
        if (applied) {
            interruptions.remove(lease.rowId)?.complete(null)
            leasesByRow.remove(lease.rowId)
        }
        applied
    }

    /**
     * One-shot pause/cancel signal for this lease. The transfer cancels its
     * OkHttp call when it completes (`null` means the lease already ended).
     */
    suspend fun interruptionFor(lease: DownloadLease): CompletableDeferred<DownloadInterruption?> =
        mutex.withLock {
            if (!isCurrent(lease)) {
                CompletableDeferred<DownloadInterruption?>().apply { complete(null) }
            } else {
                interruptions.getOrPut(lease.rowId) { CompletableDeferred() }
            }
        }

    /**
     * Cancellation-safe release of this worker's still-current lease. The row
     * returns to the gate state without an error increment and without touching
     * a concurrent pause, cancel or committed failure. Call from a worker's
     * `finally`; the non-cancellable section lets cleanup finish.
     */
    suspend fun release(lease: DownloadLease): Unit = withContext(NonCancellable) {
        mutex.withLock {
            if (!isCurrent(lease)) return@withLock
            val paused = runCatching { settings.isGloballyPaused() }.getOrDefault(true)
            repository.releaseActiveLease(lease.rowId, paused)
            interruptions.remove(lease.rowId)?.complete(null)
            leasesByRow.remove(lease.rowId)
        }
    }

    /** Writes the safe gate value first, then parks claimable rows and signals their workers. */
    suspend fun pauseAll(): Int = mutex.withLock {
        settings.setGloballyPaused(true)
        val paused = repository.pauseAllRows()
        signalAll(DownloadInterruption.PAUSE)
        paused
    }

    /** Releases globally paused rows first, then clears the gate. */
    suspend fun resumeAll(): Int = mutex.withLock {
        val released = repository.releaseGloballyPausedRows()
        settings.setGloballyPaused(false)
        released
    }

    suspend fun pauseOne(rowId: Long): Boolean = mutex.withLock {
        val paused = repository.pauseOne(rowId)
        if (paused) signal(rowId, DownloadInterruption.PAUSE)
        paused
    }

    suspend fun resumeOne(rowId: Long): Boolean = mutex.withLock {
        repository.resumeOne(rowId, settings.isGloballyPaused())
    }

    suspend fun cancel(rowId: Long): Boolean = mutex.withLock {
        val cancelled = repository.cancel(rowId)
        if (cancelled) signal(rowId, DownloadInterruption.CANCEL)
        cancelled
    }

    suspend fun retry(rowId: Long): Boolean = mutex.withLock {
        repository.retry(rowId, settings.isGloballyPaused(), nowMs())
    }

    suspend fun retryAll(): Int = mutex.withLock {
        repository.retryAllFailed(settings.isGloballyPaused(), nowMs())
    }

    private fun isCurrent(lease: DownloadLease): Boolean =
        leasesByRow[lease.rowId]?.token == lease.token

    private fun signal(rowId: Long, interruption: DownloadInterruption) {
        interruptions[rowId]?.complete(interruption)
    }

    private fun signalAll(interruption: DownloadInterruption) {
        interruptions.values.forEach { it.complete(interruption) }
    }
}
