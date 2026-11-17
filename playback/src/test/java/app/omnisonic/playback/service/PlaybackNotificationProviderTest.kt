package app.omnisonic.playback.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.graphics.Bitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.core.graphics.drawable.IconCompat
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.playback.R
import app.omnisonic.playback.api.settings.DefaultNotificationActions
import app.omnisonic.playback.api.settings.NotificationAction
import app.omnisonic.playback.settings.PlaybackSettingsStore
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

private class RecordingActionFactory(private val context: Context) : MediaNotification.ActionFactory {
    val mediaCommands = mutableListOf<Int>()
    val customActions = mutableListOf<String>()

    private fun pendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent("app.omnisonic.playback.provider.test"),
            PendingIntent.FLAG_IMMUTABLE,
        )

    override fun createMediaAction(
        session: MediaSession,
        icon: IconCompat,
        title: CharSequence,
        command: Int,
    ): NotificationCompat.Action {
        mediaCommands += command
        return NotificationCompat.Action.Builder(icon, title, pendingIntent()).build()
    }

    override fun createCustomAction(
        session: MediaSession,
        icon: IconCompat,
        title: CharSequence,
        action: String,
        extras: android.os.Bundle,
    ): NotificationCompat.Action {
        customActions += action
        return NotificationCompat.Action.Builder(icon, title, pendingIntent()).build()
    }

    override fun createCustomActionFromCustomCommandButton(
        session: MediaSession,
        button: CommandButton,
    ): NotificationCompat.Action {
        customActions += requireNotNull(button.sessionCommand).customAction
        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, button.iconResId),
            button.displayName,
            pendingIntent(),
        ).build()
    }

    override fun createMediaActionPendingIntent(session: MediaSession, command: Int): PendingIntent = pendingIntent()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackNotificationProviderTest {

    private lateinit var context: Context
    private lateinit var settings: PlaybackSettingsStore
    private lateinit var scope: CoroutineScope
    private lateinit var artworkLoader: FakeArtworkLoader
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var provider: PlaybackNotificationProvider
    private lateinit var settingsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsDir = java.nio.file.Files.createTempDirectory("t11-settings").toFile()
        settings = PlaybackSettingsStore.createAt(File(settingsDir, "playback.preferences_pb"))
        scope = CoroutineScope(Dispatchers.Unconfined)
        artworkLoader = FakeArtworkLoader()
        provider = PlaybackNotificationProvider(context, settings, scope, artworkLoader)
        player = ExoPlayer.Builder(context).build()
        session = MediaSession.Builder(context, player).build()
    }

    @After
    fun tearDown() {
        runCatching { session.release() }
        runCatching { player.release() }
        scope.cancel()
        runCatching { settingsDir.deleteRecursively() }
    }

    private fun notification(callback: MediaNotification.Provider.Callback = NoopCallback): Notification {
        val factory = RecordingActionFactory(context)
        return provider.createNotification(session, ImmutableList.of(), factory, callback).notification
    }

    private fun setTrack(coverImageId: String?) {
        val metadata = MediaMetadata.Builder()
            .setTitle("Song title")
            .setArtist("Song artist")
            .apply {
                if (coverImageId != null) {
                    setExtras(NotificationArtworkMetadata.extras(providerId = 1L, imageId = coverImageId))
                }
            }
            .build()
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId("1")
                .setUri("https://playback.test/stream.wav")
                .setMediaMetadata(metadata)
                .build(),
        )
    }

    private fun Notification.hasLargeIcon(): Boolean =
        extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_LARGE_ICON) != null ||
            extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_LARGE_ICON_BIG) != null

    @Test
    fun `channel is created silent and without a badge`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(PlaybackNotificationProvider.CHANNEL_ID)

        assertThat(channel).isNotNull()
        // The legacy channel was IMPORTANCE_DEFAULT, which alerts on the first
        // post of every playback session; the fix is the channel itself.
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(channel.sound).isNull()
        assertThat(channel.canShowBadge()).isFalse()
        assertThat(channel.lockscreenVisibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
    }

    @Test
    fun `legacy alerting channel is deleted on an existing install`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                PlaybackNotificationProvider.LEGACY_CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        PlaybackNotificationProvider(context, settings, scope, artworkLoader)

        assertThat(manager.getNotificationChannel(PlaybackNotificationProvider.LEGACY_CHANNEL_ID))
            .isNull()
        assertThat(manager.getNotificationChannel(PlaybackNotificationProvider.CHANNEL_ID))
            .isNotNull()
    }

    @Test
    fun `fixed actions are previous, play-pause, next in compact view order`() {
        val built = notification()

        assertThat(built.actions).hasLength(3 + 3)
        assertThat(built.actions[0].title.toString()).isEqualTo(context.getString(R.string.notif_action_previous))
        assertThat(built.actions[1].title.toString()).isEqualTo(context.getString(R.string.notif_action_play))
        assertThat(built.actions[2].title.toString()).isEqualTo(context.getString(R.string.notif_action_next))
    }

    @Test
    fun `play-pause action flips with the player state and ongoing follows`() {
        assertThat(notification().actions[1].title.toString()).isEqualTo(context.getString(R.string.notif_action_play))
        assertThat(notification().hasOngoingFlag()).isFalse()

        player.play()

        assertThat(notification().actions[1].title.toString()).isEqualTo(context.getString(R.string.notif_action_pause))
        assertThat(notification().hasOngoingFlag()).isTrue()
    }

    private fun Notification.hasOngoingFlag(): Boolean =
        (flags and Notification.FLAG_ONGOING_EVENT) != 0

    @Test
    fun `metadata shapes title, artist and album text`() {
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId("1")
                .setUri("https://playback.test/stream.wav")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Song title")
                        .setArtist("Song artist")
                        .setAlbumTitle("Song album")
                        .build(),
                )
                .build(),
        )

        val built = notification()

        assertThat(built.extras.getCharSequence(Notification.EXTRA_TITLE)).isEqualTo("Song title")
        assertThat(built.extras.getCharSequence(Notification.EXTRA_TEXT)).isEqualTo("Song artist")
        assertThat(built.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)).isEqualTo("Song album")
    }

    @Test
    fun `cover art is attached once the loader resolves and the cache is reused`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        artworkLoader.results["cover-1"] = bitmap
        setTrack("cover-1")
        val callback = RecordingCallback()

        val first = notification(callback)

        assertThat(first.hasLargeIcon()).isFalse()
        assertThat(artworkLoader.requests.map { it.imageId }).containsExactly("cover-1")
        assertThat(callback.notifications).hasSize(1)
        assertThat(callback.notifications.single().notificationId)
            .isEqualTo(PlaybackNotificationProvider.NOTIFICATION_ID)
        assertThat(callback.notifications.single().notification.hasLargeIcon()).isTrue()

        val second = notification(callback)

        assertThat(second.hasLargeIcon()).isTrue()
        assertThat(artworkLoader.requests).hasSize(1)
        assertThat(callback.notifications).hasSize(1)
    }

    @Test
    fun `a missing cover is non-fatal and is not retried`() {
        setTrack("cover-missing")
        val callback = RecordingCallback()

        val built = notification(callback)

        assertThat(built.hasLargeIcon()).isFalse()
        assertThat(callback.notifications).isEmpty()
        assertThat(artworkLoader.requests).hasSize(1)

        notification(callback)

        assertThat(artworkLoader.requests).hasSize(1)
    }

    @Test
    fun `a stale cover load does not repaint the notification`() {
        artworkLoader.results["cover-a"] = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        artworkLoader.results["cover-b"] = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val staleGate = CompletableDeferred<Unit>()
        artworkLoader.gates["cover-a"] = staleGate
        val callback = RecordingCallback()

        setTrack("cover-a")
        notification(callback)
        setTrack("cover-b")
        notification(callback)

        assertThat(callback.notifications).hasSize(1)

        staleGate.complete(Unit)

        assertThat(callback.notifications).hasSize(1)
    }

    @Test
    fun `configured slots follow the settings order with none slots dropped`() = runBlocking<Unit> {
        settings.setNotificationActions(
            listOf(
                NotificationAction.SEEK_FORWARD_10,
                NotificationAction.NONE,
                NotificationAction.REPEAT,
                NotificationAction.SEEK_BACK_30,
                NotificationAction.SHUFFLE,
            ),
        )

        val built = notification()

        assertThat(built.actions).hasLength(3 + 4)
        val slotActions = built.actions.drop(3).map { it.title.toString() }
        assertThat(slotActions).containsExactly(
            context.getString(R.string.notif_action_seek_forward_10),
            context.getString(R.string.notif_action_repeat),
            context.getString(R.string.notif_action_seek_back_30),
            context.getString(R.string.notif_action_shuffle),
        ).inOrder()
    }

    @Test
    fun `chapter slots stay hidden while chapters are unavailable`() = runBlocking<Unit> {
        settings.setNotificationActions(
            listOf(
                NotificationAction.CHAPTER_NEXT,
                NotificationAction.REPEAT,
                NotificationAction.NONE,
                NotificationAction.NONE,
                NotificationAction.NONE,
            ),
        )

        val built = notification()

        assertThat(built.actions).hasLength(3 + 1)
        assertThat(built.actions[3].title.toString())
            .isEqualTo(context.getString(R.string.notif_action_repeat))
    }

    @Test
    fun `custom slot actions carry the mapper custom action strings`() {
        val factory = RecordingActionFactory(context)
        provider.createNotification(session, ImmutableList.of(), factory, NoopCallback)

        val defaults = NotificationActionMapper.slots(DefaultNotificationActions.SLOT_DEFAULTS, chaptersAvailable = false)
        assertThat(factory.customActions).isEqualTo(defaults.map { NotificationActionMapper.customAction(it) })
        assertThat(factory.mediaCommands).containsExactly(
            androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS,
            androidx.media3.common.Player.COMMAND_PLAY_PAUSE,
            androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT,
        ).inOrder()
    }

    @Test
    fun `notification id and channel are the fixed process-wide ones`() {
        val factory = RecordingActionFactory(context)
        val result = provider.createNotification(session, ImmutableList.of(), factory, NoopCallback)

        assertThat(result.notificationId).isEqualTo(PlaybackNotificationProvider.NOTIFICATION_ID)
        assertThat(result.notification.channelId)
            .isEqualTo(PlaybackNotificationProvider.CHANNEL_ID)
        assertThat(result.notification.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
        assertThat(result.notification.category).isEqualTo(Notification.CATEGORY_TRANSPORT)
    }

    @Test
    fun `provider leaves custom command handling to the session callback`() {
        assertThat(provider.handleCustomCommand(session, "anything", android.os.Bundle.EMPTY)).isFalse()
    }

    @Test
    fun `provider reports the playback channel info`() {
        val info = provider.notificationChannelInfo

        assertThat(info.id).isEqualTo(PlaybackNotificationProvider.CHANNEL_ID)
        assertThat(info.name).isEqualTo(context.getString(R.string.notification_channel_playback))
    }

    private class RecordingCallback : MediaNotification.Provider.Callback {
        val notifications = mutableListOf<MediaNotification>()

        override fun onNotificationChanged(notification: MediaNotification) {
            notifications += notification
        }
    }

    private object NoopCallback : MediaNotification.Provider.Callback {
        override fun onNotificationChanged(notification: MediaNotification) = Unit
    }
}

private class FakeArtworkLoader : NotificationArtworkLoader {
    val requests = mutableListOf<NotificationArtworkIdentity>()
    val results = mutableMapOf<String, Bitmap?>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun load(identity: NotificationArtworkIdentity): Bitmap? {
        requests += identity
        gates[identity.imageId]?.await()
        return results[identity.imageId]
    }
}
