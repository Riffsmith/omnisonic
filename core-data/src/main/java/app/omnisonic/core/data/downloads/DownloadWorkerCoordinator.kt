package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Outcome of one enqueue under the mutation lock. A known media key is never
 * rewritten; a different destination on that key needs the explicit
 * conversion/new-export command instead of a silent overwrite or a lost request.
 */
sealed interface EnqueueAttempt {

    data object Inserted : EnqueueAttempt

    /** Same media key, same destination: the strict whole-row no-op. */
    data object Duplicate : EnqueueAttempt

    data class DuplicateOtherDestination(val existingExportTarget: String?) : EnqueueAttempt
}

/** How a worker's completion settlement ended (Phase 7 task 5a publication protocol). */
sealed interface CompletionSettlement {

    /** The publication transaction committed and the lease is unregistered. */
    data object Applied : CompletionSettlement

    /** A newer lease owns the row; this worker must not touch anything. */
    data object LeaseStale : CompletionSettlement

    /** Pause/cancel settled the row first; the orphan/adoption flow owns the finalized file. */
    data object RowAlreadySettled : CompletionSettlement

    /** The publication transaction failed; the row is still `ACTIVE` and must not spin. */
    data object Failed : CompletionSettlement
}

/** One active transfer as the foreground notification sees it (`-1` is unknown length). */
data class DownloadActiveProgress(
    val rowId: Long,
    val title: String?,
    val progressPercent: Int,
)

/**
 * Process-wide wake request after a committed queue mutation. The foreground
 * service starts (or re-checks) its worker loop; the coordinator itself never
 * touches Android service APIs.
 */
fun interface DownloadWorkStart {
    fun requestStart()

    companion object {
        val None = DownloadWorkStart {}
    }
}

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
    private val workStart: DownloadWorkStart = DownloadWorkStart.None,
) {

    private val mutex = Mutex()
    private val leasesByRow = LinkedHashMap<Long, DownloadLease>()
    private val progressByRow = LinkedHashMap<Long, DownloadActiveProgress>()
    private val interruptions = LinkedHashMap<Long, CompletableDeferred<DownloadInterruption?>>()
    private val nextToken = AtomicLong()
    private val _activeLeases = MutableStateFlow(0)
    private val _activeProgress = MutableStateFlow<List<DownloadActiveProgress>>(emptyList())

    @Volatile
    private var currentWorkerCount = DownloadLifecycleRules.sanitizeWorkerCount(workerCount)

    /** Dynamic cap: a decrease never preempts running leases, later claims respect it. */
    val workerCount: Int get() = currentWorkerCount

    val activeLeases: StateFlow<Int> = _activeLeases.asStateFlow()

    val activeProgress: StateFlow<List<DownloadActiveProgress>> = _activeProgress.asStateFlow()

    suspend fun activeLeaseCount(): Int = mutex.withLock { leasesByRow.size }

    /** Worker-count changes arrive from persisted settings while the loop runs. */
    suspend fun updateWorkerCount(requested: Int): Unit = mutex.withLock {
        currentWorkerCount = DownloadLifecycleRules.sanitizeWorkerCount(requested)
    }

    /**
     * The status on [item] is ignored: this coordinator owns the initial state
     * (`PENDING`, or `PAUSED` while the global gate is set) and checks the gate
     * under the same lock as the insert.
     */
    suspend fun enqueue(item: DownloadItemEntity): Boolean = enqueueChecked(item) == EnqueueAttempt.Inserted

    suspend fun enqueueChecked(item: DownloadItemEntity): EnqueueAttempt {
        val attempt = mutex.withLock {
            val existing = repository.selectByMediaKey(item.mediaKey)
            if (existing != null) {
                return@withLock if (existing.exportTarget == item.exportTarget) {
                    EnqueueAttempt.Duplicate
                } else {
                    EnqueueAttempt.DuplicateOtherDestination(existing.exportTarget)
                }
            }
            val paused = settings.isGloballyPaused()
            if (repository.insertIfAbsent(item.copy(status = DownloadLifecycleRules.enqueueStatus(paused).name))) {
                EnqueueAttempt.Inserted
            } else {
                EnqueueAttempt.Duplicate
            }
        }
        if (attempt == EnqueueAttempt.Inserted) workStart.requestStart()
        return attempt
    }

    /** Explicit destination change for a row no worker owns; never part of enqueue idempotence. */
    suspend fun convertDestination(rowId: Long, exportTarget: String?): Boolean {
        val converted = mutex.withLock {
            if (leasesByRow.containsKey(rowId)) return@withLock false
            repository.convertDestination(rowId, exportTarget)
        }
        if (converted) workStart.requestStart()
        return converted
    }

    /**
     * Runs before workers start and reconciles both sides of the persisted
     * gate/row write order. Live leases are never reset: another service
     * instance (or a second start) must not park this process's active rows.
     */
    suspend fun recoverOnStartup(): Int = mutex.withLock {
        if (leasesByRow.isNotEmpty()) return@withLock 0
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
        if (leasesByRow.size >= currentWorkerCount || settings.isGloballyPaused()) return@withLock null
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
            progressByRow[row.id] = DownloadActiveProgress(
                rowId = row.id,
                title = row.title,
                progressPercent = normalizeProgress(row.progressPercent),
            )
            interruptions[row.id] = CompletableDeferred()
            publishLeaseState()
            return@withLock lease
        }
        null
    }

    suspend fun reportProgress(lease: DownloadLease, progressPercent: Int): Boolean = mutex.withLock {
        if (!isCurrent(lease)) return@withLock false
        val applied = repository.reportProgress(lease.rowId, progressPercent)
        if (applied) {
            progressByRow[lease.rowId] = DownloadActiveProgress(
                rowId = lease.rowId,
                title = progressByRow[lease.rowId]?.title,
                progressPercent = normalizeProgress(progressPercent),
            )
            publishLeaseState()
        }
        applied
    }

    suspend fun recordFailure(lease: DownloadLease, message: String?): DownloadStatus? = mutex.withLock {
        if (!isCurrent(lease)) return@withLock null
        repository.recordFailure(lease.rowId, lease.errorCount, message, nowMs())
    }

    /** Successful settlement: the queue row is deleted, never kept as a state. */
    suspend fun complete(lease: DownloadLease): Boolean = completeWith(lease) { rowId ->
        if (repository.deleteIfActive(rowId)) PublishSettle.APPLIED else PublishSettle.ROW_NOT_ACTIVE
    } == CompletionSettlement.Applied

    /**
     * Lease-fenced completion with a caller-supplied settlement. The engine's
     * task-5a settlement publishes `offline_media` and deletes the queue row in
     * one transaction; only [PublishSettle.APPLIED] unregisters the lease, so a
     * paused/cancelled or failed settlement leaves the file and sidecar for the
     * orphan/adoption flow instead of pretending success.
     */
    suspend fun completeWith(
        lease: DownloadLease,
        settle: suspend (rowId: Long) -> PublishSettle,
    ): CompletionSettlement = mutex.withLock {
        if (!isCurrent(lease)) return@withLock CompletionSettlement.LeaseStale
        when (settle(lease.rowId)) {
            PublishSettle.APPLIED -> {
                interruptions.remove(lease.rowId)?.complete(null)
                leasesByRow.remove(lease.rowId)
                progressByRow.remove(lease.rowId)
                publishLeaseState()
                CompletionSettlement.Applied
            }
            PublishSettle.ROW_NOT_ACTIVE -> CompletionSettlement.RowAlreadySettled
            PublishSettle.FAILED -> CompletionSettlement.Failed
        }
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
            progressByRow.remove(lease.rowId)
            publishLeaseState()
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
            progressByRow.remove(lease.rowId)
            publishLeaseState()
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
    suspend fun resumeAll(): Int {
        val released = mutex.withLock {
            val count = repository.releaseGloballyPausedRows()
            settings.setGloballyPaused(false)
            count
        }
        if (released > 0) workStart.requestStart()
        return released
    }

    suspend fun pauseOne(rowId: Long): Boolean = mutex.withLock {
        val paused = repository.pauseOne(rowId)
        if (paused) signal(rowId, DownloadInterruption.PAUSE)
        paused
    }

    suspend fun resumeOne(rowId: Long): Boolean {
        val resumed = mutex.withLock { repository.resumeOne(rowId, settings.isGloballyPaused()) }
        if (resumed) workStart.requestStart()
        return resumed
    }

    suspend fun cancel(rowId: Long): Boolean = mutex.withLock {
        val cancelled = repository.cancel(rowId)
        if (cancelled) signal(rowId, DownloadInterruption.CANCEL)
        cancelled
    }

    suspend fun retry(rowId: Long): Boolean {
        val retried = mutex.withLock {
            repository.retry(rowId, settings.isGloballyPaused(), nowMs())
        }
        if (retried) workStart.requestStart()
        return retried
    }

    suspend fun retryAll(): Int {
        val retried = mutex.withLock {
            repository.retryAllFailed(settings.isGloballyPaused(), nowMs())
        }
        if (retried > 0) workStart.requestStart()
        return retried
    }

    private fun isCurrent(lease: DownloadLease): Boolean =
        leasesByRow[lease.rowId]?.token == lease.token

    private fun publishLeaseState() {
        _activeLeases.value = leasesByRow.size
        _activeProgress.value = progressByRow.values.toList()
    }

    private fun normalizeProgress(percent: Int): Int =
        if (percent < 0) DownloadLifecycleRules.PROGRESS_UNKNOWN else percent.coerceIn(0, 100)

    private fun signal(rowId: Long, interruption: DownloadInterruption) {
        interruptions[rowId]?.complete(interruption)
    }

    private fun signalAll(interruption: DownloadInterruption) {
        interruptions.values.forEach { it.complete(interruption) }
    }
}
