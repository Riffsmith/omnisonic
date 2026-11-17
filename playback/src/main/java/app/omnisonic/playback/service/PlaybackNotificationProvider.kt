package app.omnisonic.playback.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import app.omnisonic.playback.R
import app.omnisonic.playback.api.settings.DefaultNotificationActions
import app.omnisonic.playback.api.settings.NotificationAction
import app.omnisonic.playback.api.settings.PlaybackSettings
import app.omnisonic.playback.di.PlaybackEngineScope
import com.google.common.collect.ImmutableList
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shapes the playback notification: fixed previous / play-pause / next buttons
 * plus the settings-configured slot buttons (NONE slots drop out, chapter
 * actions wait for the chapters feature). The channel is created here with the
 * full spec (importance LOW with no sound, no badge, lockscreen PRIVATE)
 * before the session service's own ensure-no-op runs. The notification id is
 * fixed for the process so foreground handoff is stable.
 *
 * Cover art follows the metadata identity: a cached bitmap is attached while
 * the notification is built, a miss starts one load per identity and repaints
 * through [MediaNotification.Provider.Callback.onNotificationChanged]. The
 * notification is never built off the media session thread, so the load runs
 * on [scope] (the engine's main-immediate scope), never inline.
 */
@UnstableApi
@Singleton
class PlaybackNotificationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: PlaybackSettings,
    @PlaybackEngineScope private val scope: CoroutineScope,
    private val artworkLoader: NotificationArtworkLoader,
) : MediaNotification.Provider {

    @Volatile
    private var slots: List<NotificationAction> = DefaultNotificationActions.SLOT_DEFAULTS

    // Session-thread state (createNotification and the repaint continuation both
    // run on the engine's main-immediate scope).
    private val artworkCache = object : LinkedHashMap<NotificationArtworkIdentity, Bitmap>(
        MAX_CACHED_ARTWORK,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NotificationArtworkIdentity, Bitmap>): Boolean =
            size > MAX_CACHED_ARTWORK
    }
    private val pendingArtwork = mutableSetOf<NotificationArtworkIdentity>()
    private val failedArtwork = mutableSetOf<NotificationArtworkIdentity>()
    private var lastIdentity: NotificationArtworkIdentity? = null
    private var lastBuilder: NotificationCompat.Builder? = null

    init {
        scope.launch { settings.notificationActions.collect { slots = it } }
        ensureChannel()
    }

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        MediaNotification.Provider.NotificationChannelInfo(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_playback),
        )

    override fun createNotification(
        session: MediaSession,
        commandButtons: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        ensureChannel()
        val player = session.player
        val isPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        val metadata = player.mediaMetadata

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_playback)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(isPlaying)
            .setContentIntent(session.sessionActivity)
            .setDeleteIntent(actionFactory.createNotificationDismissalIntent(session))
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        metadata.title?.let(builder::setContentTitle)
        metadata.artist?.let(builder::setContentText)
        metadata.albumTitle?.let(builder::setSubText)

        builder.addAction(
            actionFactory.createMediaAction(
                session,
                fixedIcon(CommandButton.ICON_PREVIOUS),
                context.getString(R.string.notif_action_previous),
                Player.COMMAND_SEEK_TO_PREVIOUS,
            ),
        )
        builder.addAction(
            actionFactory.createMediaAction(
                session,
                fixedIcon(if (isPlaying) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY),
                context.getString(if (isPlaying) R.string.notif_action_pause else R.string.notif_action_play),
                Player.COMMAND_PLAY_PAUSE,
            ),
        )
        builder.addAction(
            actionFactory.createMediaAction(
                session,
                fixedIcon(CommandButton.ICON_NEXT),
                context.getString(R.string.notif_action_next),
                Player.COMMAND_SEEK_TO_NEXT,
            ),
        )

        val configured = slots
        NotificationActionMapper.slots(configured, chaptersAvailable = false).forEach { action ->
            builder.addAction(actionFactory.createCustomActionFromCustomCommandButton(session, buttonFor(action)))
        }

        val identity = NotificationArtworkMetadata.identityFrom(metadata)
        val cachedArt = identity?.let { artworkCache[it] }
        if (cachedArt != null) builder.setLargeIcon(cachedArt)
        // Build before publishing the builder for repaints: a load that
        // completes immediately must not mutate the notification being built.
        val notification = MediaNotification(NOTIFICATION_ID, builder.build())
        lastIdentity = identity
        lastBuilder = builder
        if (cachedArt == null && identity != null) requestArtwork(identity, callback)
        return notification
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: android.os.Bundle): Boolean = false

    private fun requestArtwork(
        identity: NotificationArtworkIdentity,
        callback: MediaNotification.Provider.Callback,
    ) {
        if (identity in failedArtwork) return
        if (!pendingArtwork.add(identity)) return
        scope.launch {
            val bitmap = runCatching { artworkLoader.load(identity) }.getOrNull()
            pendingArtwork.remove(identity)
            if (bitmap == null) {
                if (failedArtwork.add(identity)) {
                    Log.w(TAG, "notification artwork unavailable")
                }
                return@launch
            }
            artworkCache[identity] = bitmap
            // A track change or a dismissal while the load was in flight must
            // never repaint the previous track's art.
            if (lastIdentity != identity) return@launch
            val builder = lastBuilder ?: return@launch
            callback.onNotificationChanged(
                MediaNotification(NOTIFICATION_ID, builder.setLargeIcon(bitmap).build()),
            )
        }
    }

    private fun buttonFor(action: NotificationAction): CommandButton {
        val icon = NotificationActionMapper.iconConstant(action)
        return CommandButton.Builder(icon)
            .setSessionCommand(SessionCommand(NotificationActionMapper.customAction(action), android.os.Bundle.EMPTY))
            .setDisplayName(context.getString(NotificationActionMapper.labelRes(action)))
            .build()
    }

    private fun fixedIcon(iconConstant: Int): IconCompat =
        IconCompat.createWithResource(context, CommandButton.getIconResIdForIconConstant(iconConstant))

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_playback),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            manager.createNotificationChannel(channel)
        }
        // Channel importance cannot be changed in place, so existing installs
        // migrate to v2 and lose the still-alerting legacy channel entirely.
        if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
    }

    companion object {
        const val CHANNEL_ID = "omnisonic.playback.v2"
        const val LEGACY_CHANNEL_ID = "omnisonic.playback"
        const val NOTIFICATION_ID = 1001

        private const val TAG = "PlaybackNotification"
        private const val MAX_CACHED_ARTWORK = 4
    }
}
