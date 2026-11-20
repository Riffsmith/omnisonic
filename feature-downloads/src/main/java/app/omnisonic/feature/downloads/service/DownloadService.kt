package app.omnisonic.feature.downloads.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * `dataSync` foreground service for downloads (`.agents/plans/phase-7-tasks.md`
 * §TODO 4). The notification is posted in `onCreate` before any suspending
 * database or provider work, so a slow start cannot miss the platform's
 * foreground deadline. Repeated starts only wake the runtime's single session.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject
    lateinit var runtime: DownloadServiceRuntime

    @Inject
    lateinit var notifications: DownloadNotificationCenter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannel()
        enterForeground()
        runtime.serviceStarted { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch { runtime.pauseAll() }
            ACTION_RESUME -> scope.launch { runtime.resumeAll() }
            else -> runtime.requestRun()
        }
        return if (runtime.keepSticky) START_STICKY else START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runtime.onDataSyncTimeout()
        stopSelf(startId)
    }

    override fun onDestroy() {
        runtime.serviceStopping()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground() {
        if (foregroundStarted) return
        foregroundStarted = true
        startForeground(
            AndroidDownloadNotificationCenter.NOTIFICATION_ID,
            notifications.initialNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val ACTION_START = "app.omnisonic.downloads.action.START"
        const val ACTION_PAUSE = "app.omnisonic.downloads.action.PAUSE"
        const val ACTION_RESUME = "app.omnisonic.downloads.action.RESUME"

        fun startIntent(context: Context): Intent =
            Intent(context, DownloadService::class.java).setAction(ACTION_START)

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, DownloadService::class.java).setAction(action)
    }
}
