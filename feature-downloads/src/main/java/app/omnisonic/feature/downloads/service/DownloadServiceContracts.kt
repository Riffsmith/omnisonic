package app.omnisonic.feature.downloads.service

import android.app.Notification
import app.omnisonic.core.data.downloads.DownloadLease
import app.omnisonic.core.data.downloads.DownloadTransferResult
import app.omnisonic.core.data.downloads.DownloadWorkerCoordinator

/**
 * Service-facing lifecycle seam. The implementation owns the worker loop so
 * repeated `startService` calls only wake one session (`.agents/plans/phase-7-tasks.md`
 * §TODO 4).
 */
interface DownloadServiceRuntime {

    /**
     * `START_STICKY` is reported only while the last session may still have
     * `PENDING`/`ACTIVE` work; an exhausted queue reports `START_NOT_STICKY`.
     */
    val keepSticky: Boolean

    /** Registers the stop callback and enters the foreground before any suspending work. */
    fun serviceStarted(onIdle: () -> Unit)

    /** Wakes the existing session or asks the starter to start the service. */
    fun requestRun()

    suspend fun pauseAll()

    suspend fun resumeAll()

    /** Cancels the session; active workers release leases and locks in `finally`. */
    fun serviceStopping()

    /** Platform `dataSync` timeout: release claims/locks instead of crashing. */
    fun onDataSyncTimeout()
}

interface DownloadNotificationCenter {
    fun ensureChannel()

    fun initialNotification(): Notification

    fun build(state: DownloadNotificationState): Notification

    fun publish(state: DownloadNotificationState)
}

/** One claimed transfer; production delegates to the task-3 engine. */
fun interface DownloadTransferHandler {
    suspend fun transfer(lease: DownloadLease): DownloadTransferResult
}

fun interface DownloadTransferHandlerFactory {
    fun create(coordinator: DownloadWorkerCoordinator): DownloadTransferHandler
}

/** Starts the foreground service from committed queue mutations. */
fun interface DownloadWorkStarter {
    fun start()
}

/** Connectivity callback seam; production registers one default-network callback per session. */
interface DownloadConnectivityObserver {
    fun start(onChange: () -> Unit)

    fun stop()
}

/** Per-item partial wake / Wi-Fi lock handle; [release] is idempotent. */
fun interface DownloadTransferLock {
    fun release()
}

interface DownloadTransferLocks {
    fun acquire(rowId: Long): DownloadTransferLock
}
