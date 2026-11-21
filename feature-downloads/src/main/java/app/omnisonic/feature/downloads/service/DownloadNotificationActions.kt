package app.omnisonic.feature.downloads.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Notification commands are immutable and use fixed request codes, so the
 * actions never change identity across progress updates. The content intent
 * targets the app's downloads workspace through [ACTION_OPEN_DOWNLOADS]; the
 * activity handles it warm (`onNewIntent`) and cold (`onCreate`).
 */
object DownloadNotificationActions {

    /** Handled by `MainActivity`; never a broadcast or a service action. */
    const val ACTION_OPEN_DOWNLOADS = "app.omnisonic.action.OPEN_DOWNLOADS"

    const val REQUEST_CONTENT = 2_100
    const val REQUEST_PAUSE = 2_101
    const val REQUEST_RESUME = 2_102

    fun pauseIntent(context: Context): PendingIntent = serviceIntent(
        context = context,
        requestCode = REQUEST_PAUSE,
        action = DownloadService.ACTION_PAUSE,
    )

    fun resumeIntent(context: Context): PendingIntent = serviceIntent(
        context = context,
        requestCode = REQUEST_RESUME,
        action = DownloadService.ACTION_RESUME,
    )

    fun contentIntent(context: Context): PendingIntent? = runCatching {
        // Keep the launcher activity explicit when the package manager can
        // resolve it; the action/filter then routes the intent to downloads.
        val launch = (
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(ACTION_OPEN_DOWNLOADS).setPackage(context.packageName)
            ).apply {
            action = ACTION_OPEN_DOWNLOADS
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }.getOrNull()

    private fun serviceIntent(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, DownloadService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
