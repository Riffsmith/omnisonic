package app.omnisonic.feature.downloads.service

import app.omnisonic.core.data.downloads.DownloadEnqueuePort

/**
 * User-issued download commands. Production is [ForegroundDownloadRuntime], so
 * pause/resume/cancel/retry and enqueue all address the one process-wide
 * coordinator (task 9a). Commands that create work already wake the service
 * through the coordinator's own `workStart`; pause and cancel deliberately do
 * not start anything.
 */
interface DownloadUserActions : DownloadEnqueuePort {

    suspend fun pauseAll()

    suspend fun resumeAll()

    suspend fun pauseItem(rowId: Long): Boolean

    suspend fun resumeItem(rowId: Long): Boolean

    suspend fun retry(rowId: Long): Boolean

    suspend fun retryAll(): Int

    suspend fun cancel(rowId: Long): Boolean
}
