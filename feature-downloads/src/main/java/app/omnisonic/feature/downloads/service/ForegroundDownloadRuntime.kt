package app.omnisonic.feature.downloads.service

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.downloads.DownloadActiveProgress
import app.omnisonic.core.data.downloads.DownloadCompletionRecovery
import app.omnisonic.core.data.downloads.DownloadExportPublisher
import app.omnisonic.core.data.downloads.DownloadLease
import app.omnisonic.core.data.downloads.DownloadQueueCancellation
import app.omnisonic.core.data.downloads.DownloadQueueRepository
import app.omnisonic.core.data.downloads.DownloadQueueSettingsStore
import app.omnisonic.core.data.downloads.DownloadTransferFailure
import app.omnisonic.core.data.downloads.DownloadTransferResult
import app.omnisonic.core.data.downloads.description
import app.omnisonic.core.data.downloads.DownloadWorkStart
import app.omnisonic.core.data.downloads.DownloadWorkerCoordinator
import app.omnisonic.core.data.downloads.OfflineFileSweep
import app.omnisonic.core.data.downloads.OfflineRootWorkGate
import app.omnisonic.core.data.downloads.OfflineStorage
import app.omnisonic.core.data.network.NetworkMonitor
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.provider.api.ProviderRunGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide download loop behind the `dataSync` service. Exactly one
 * coordinator is constructed from the persisted worker count, startup recovery
 * runs once before the first claim, and repeated starts only wake the single
 * session (`START_STICKY` is reported only while `PENDING`/`ACTIVE` work
 * exists). It is also the real [OfflineRootWorkGate]: a root change stops new
 * claims and drains active transfers before publication/cleanup.
 */
class ForegroundDownloadRuntime(
    private val repository: DownloadQueueRepository,
    private val settings: DownloadQueueSettingsStore,
    private val storage: OfflineStorage,
    private val completionRecovery: DownloadCompletionRecovery,
    private val exportPublisher: DownloadExportPublisher,
    private val offlineSweep: OfflineFileSweep,
    private val network: NetworkMonitor,
    private val runGate: ProviderRunGate,
    private val locks: DownloadTransferLocks,
    private val notifications: DownloadNotificationCenter,
    private val connectivity: DownloadConnectivityObserver,
    private val handlers: DownloadTransferHandlerFactory,
    private val workStart: DownloadWorkStart,
    private val logger: OmnisonicLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DownloadServiceRuntime, OfflineRootWorkGate, DownloadQueueCancellation {

    private class Session(val job: Job)

    private val sessionLock = Any()
    private val processScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** External triggers only: worker-settle wakes never clear a blocked gate. */
    private val externalTriggers = MutableStateFlow(0L)
    private val recoveryMutex = Mutex()
    private val claimAdmissionMutex = Mutex()
    private val coordinatorMutex = Mutex()
    private val claimsAllowed = AtomicBoolean(true)

    @Volatile
    private var session: Session? = null

    @Volatile
    private var coordinatorRef: DownloadWorkerCoordinator? = null

    @Volatile
    private var recovered = false

    @Volatile
    private var onIdle: (() -> Unit)? = null

    @Volatile
    private var sticky = true

    @Volatile
    private var blockedOnTrigger: Long? = null

    @Volatile
    private var paused = false

    @Volatile
    private var blocked: DownloadRunBlock? = null

    @Volatile
    private var pendingCount = 0

    @Volatile
    private var latestProgress: List<DownloadActiveProgress> = emptyList()

    override val keepSticky: Boolean get() = sticky

    override fun serviceStarted(onIdle: () -> Unit) {
        this.onIdle = onIdle
        sticky = true
        ensureSession()
    }

    override fun requestRun() {
        signalExternal()
        ensureSession()
    }

    override suspend fun pauseAll() {
        val coordinator = coordinator()
        coordinator.pauseAll()
        paused = true
        publishNotification()
    }

    override suspend fun resumeAll() {
        val coordinator = coordinator()
        coordinator.resumeAll()
        paused = false
        signalExternal()
        ensureSession()
    }

    /** Manual offline delete cancels its equivalent row through the one process-wide coordinator. */
    override suspend fun cancel(rowId: Long): Boolean = coordinator().cancel(rowId)

    override fun serviceStopping() {
        onIdle = null
        cancelSession()
    }

    override fun onDataSyncTimeout() {
        logger.warn(TAG, "dataSync time budget expired; releasing active claims")
        onIdle = null
        cancelSession()
    }

    override suspend fun pauseForRootChange() {
        claimAdmissionMutex.withLock { claimsAllowed.set(false) }
        wake.trySend(Unit)
        recoveryMutex.withLock { }
        coordinatorRef?.activeLeases?.first { it == 0 }
    }

    override suspend fun resumeAfterRootChange() {
        claimsAllowed.set(true)
        signalExternal()
        ensureSession()
    }

    private fun signalExternal() {
        externalTriggers.value += 1
        wake.trySend(Unit)
    }

    private fun ensureSession() {
        synchronized(sessionLock) {
            if (session?.job?.isActive == true) return
            val job = processScope.launch(start = CoroutineStart.LAZY) { runSession() }
            val created = Session(job)
            session = created
            job.invokeOnCompletion {
                synchronized(sessionLock) {
                    if (session === created) session = null
                }
            }
            job.start()
        }
    }

    private fun cancelSession() {
        val job = synchronized(sessionLock) {
            val current = session?.job
            session = null
            current
        }
        job?.cancel()
        if (job == null) processScope.coroutineContext[Job]?.children?.forEach { it.cancel() }
    }

    private suspend fun coordinator(): DownloadWorkerCoordinator = coordinatorMutex.withLock {
        coordinatorRef ?: DownloadWorkerCoordinator(
            repository = repository,
            settings = settings,
            runGate = runGate,
            workerCount = settings.awaitReady().workerCount,
            nowMs = clock,
            workStart = workStart,
        ).also { coordinatorRef = it }
    }

    private suspend fun ensureRecovered(coordinator: DownloadWorkerCoordinator) {
        if (!recovered) storage.recoverInterruptedRootChange(this)
        recoveryMutex.withLock {
            if (recovered) return@withLock
            // First recovery precedes every claim in this runtime, so a live lease
            // can only be a cancelled session still draining. Wait for it rather
            // than skip recovery and mark the process recovered.
            coordinator.activeLeases.first { it == 0 }
            coordinator.recoverOnStartup()
            // Export sessions settle/clean before app-private orphan adoption,
            // so an export row can never be published as offline media.
            exportPublisher.recoverSessions()
            completionRecovery.recoverOrphanedFinalFiles()
            offlineSweep.sweep()
            recovered = true
        }
    }

    private suspend fun runSession() {
        val coordinator = coordinator()
        ensureRecovered(coordinator)
        val handler = handlers.create(coordinator)
        connectivity.start { signalExternal() }
        try {
            coroutineScope {
                val progressJob = launch {
                    coordinator.activeProgress.collect { progress ->
                        latestProgress = progress
                        publishNotification()
                    }
                }
                val settingsJob = launch {
                    settings.settings.collect { current ->
                        coordinator.updateWorkerCount(current.workerCount)
                        signalExternal()
                    }
                }
                var idle = false
                try {
                    idle = workerLoop(coordinator, handler)
                } finally {
                    progressJob.cancel()
                    settingsJob.cancel()
                }
                if (idle) {
                    sticky = false
                    onIdle?.invoke()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.error(TAG, "download session failed; claims are released by their workers", failure)
        } finally {
            connectivity.stop()
        }
    }

    private suspend fun CoroutineScope.workerLoop(
        coordinator: DownloadWorkerCoordinator,
        handler: DownloadTransferHandler,
    ): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!claimsAllowed.get()) {
                wake.receive()
                continue
            }
            val blockedTrigger = blockedOnTrigger
            if (blockedTrigger != null) {
                logger.debug(TAG, "blocked transfer settled; waiting for an external trigger")
                externalTriggers.first { it != blockedTrigger }
                blockedOnTrigger = null
                continue
            }
            val snapshot = settings.awaitReady()
            coordinator.updateWorkerCount(snapshot.workerCount)
            paused = settings.isGloballyPaused()
            val gateBlock = DownloadRunGateRules.block(snapshot, network.current(), storage.prepare(0))
            blocked = gateBlock
            if (gateBlock != null) {
                pendingCount = repository.pendingCount(ProfileScope.Unrestricted)
                publishNotification()
                wake.receive()
                continue
            }

            var claimed = false
            while (coordinator.activeLeaseCount() < coordinator.workerCount) {
                val lease = claimAdmissionMutex.withLock {
                    if (!claimsAllowed.get()) return@withLock null
                    coordinator.claimNext(ProfileScope.Unrestricted)
                } ?: break
                claimed = true
                launchWorker(lease, coordinator, handler)
            }
            if (claimed) continue

            val active = coordinator.activeLeaseCount()
            val dueDelay = repository.pendingDueDelayMs(ProfileScope.Unrestricted, clock())
            pendingCount = repository.pendingCount(ProfileScope.Unrestricted)
            publishNotification()
            when {
                dueDelay == null && active == 0 -> return true
                dueDelay == null -> wake.receive()
                dueDelay > 0L -> withTimeoutOrNull(dueDelay) { wake.receive() }
                else -> wake.receive()
            }
        }
    }

    private fun CoroutineScope.launchWorker(
        lease: DownloadLease,
        coordinator: DownloadWorkerCoordinator,
        handler: DownloadTransferHandler,
    ) {
        launch {
            val lock = runCatching { locks.acquire(lease.rowId) }.getOrNull()
            try {
                if (handler.transfer(lease) is DownloadTransferResult.Blocked) {
                    blockedOnTrigger = externalTriggers.value
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn(TAG, "download worker failed; applying its retry backoff", failure)
                coordinator.recordFailure(lease, DownloadTransferFailure.WORKER_FAILED.description())
            } finally {
                runCatching { lock?.release() }
                coordinator.release(lease)
                wake.trySend(Unit)
            }
        }
    }

    private fun publishNotification() {
        notifications.publish(
            DownloadNotificationRules.state(
                progressPercents = latestProgress.map { it.progressPercent },
                pendingCount = pendingCount,
                paused = paused,
                blocked = blocked,
            ),
        )
    }

    private companion object {
        const val TAG = "DownloadRuntime"
    }
}
