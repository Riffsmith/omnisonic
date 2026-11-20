package app.omnisonic.feature.downloads.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import app.omnisonic.feature.downloads.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the `dataSync` notification: one low-importance channel (silent, no
 * badge) and the fixed progress notification ID used by `startForeground`.
 * Progress is aggregated by [DownloadNotificationRules].
 */
@Singleton
class AndroidDownloadNotificationCenter @Inject constructor(
    @ApplicationContext private val context: Context,
) : DownloadNotificationCenter {

    override fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.download_channel_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    override fun initialNotification(): Notification = build(DownloadNotificationState())

    override fun build(state: DownloadNotificationState): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(MAX_PROGRESS, state.progressPercent, state.indeterminate)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_media_pause),
                    context.getString(R.string.download_action_pause),
                    DownloadNotificationActions.pauseIntent(context),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_media_play),
                    context.getString(R.string.download_action_resume),
                    DownloadNotificationActions.resumeIntent(context),
                ).build(),
            )
        contentText(state)?.let(builder::setContentText)
        DownloadNotificationActions.contentIntent(context)?.let(builder::setContentIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    override fun publish(state: DownloadNotificationState) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, build(state))
        }
    }

    private fun title(state: DownloadNotificationState): String = when {
        state.paused -> context.getString(R.string.download_notification_paused)
        state.blocked == DownloadRunBlock.WIFI_REQUIRED ->
            context.getString(R.string.download_notification_wifi)
        state.blocked != null -> context.getString(R.string.download_notification_storage)
        state.activeCount > 1 -> context.resources.getQuantityString(
            R.plurals.download_notification_downloading_count,
            state.activeCount,
            state.activeCount,
        )
        state.activeCount == 1 -> context.getString(R.string.download_notification_downloading)
        state.pendingCount > 0 -> context.resources.getQuantityString(
            R.plurals.download_notification_waiting_count,
            state.pendingCount,
            state.pendingCount,
        )
        else -> context.getString(R.string.download_notification_preparing)
    }

    private fun contentText(state: DownloadNotificationState): String? = when {
        state.paused -> context.getString(R.string.download_notification_paused_text)
        state.indeterminate -> null
        state.activeCount > 0 ->
            context.getString(R.string.download_notification_progress, state.progressPercent)
        else -> null
    }

    companion object {
        const val CHANNEL_ID = "omnisonic.downloads"
        const val NOTIFICATION_ID = 1002
        private const val MAX_PROGRESS = 100
    }
}
