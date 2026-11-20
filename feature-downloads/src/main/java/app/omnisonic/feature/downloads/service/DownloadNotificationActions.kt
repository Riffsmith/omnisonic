package app.omnisonic.feature.downloads.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Notification commands are immutable and use fixed request codes, so the
 * actions never change identity across progress updates. Until task 9 adds a
 * downloads deep link, the content intent is the app's launcher activity.
 */
object DownloadNotificationActions {

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

    fun contentIntent(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            PendingIntent.getActivity(
                context,
                REQUEST_CONTENT,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    private fun serviceIntent(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, DownloadService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
