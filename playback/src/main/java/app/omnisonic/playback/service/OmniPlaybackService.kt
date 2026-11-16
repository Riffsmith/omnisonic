package app.omnisonic.playback.service

import android.app.PendingIntent
import android.content.ComponentCallbacks2
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.omnisonic.playback.api.controller.PlaybackController
import app.omnisonic.playback.api.graph.BrowseKind
import app.omnisonic.playback.api.graph.BrowseNode
import app.omnisonic.playback.api.graph.BrowseTreeProvider
import app.omnisonic.playback.di.PlaybackStack
import app.omnisonic.playback.queue.QueuePersistenceCoordinator
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Restores the persisted queue without letting a slow store hold the service
 * start path. A timed-out restore leaves the empty in-memory queue, matching a
 * store with no snapshot.
 */
internal suspend fun restoreQueueWithin(controller: PlaybackController, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) { controller.restoreQueue() } ?: false

/**
 * The media session surface. Binds the session to the exposed player facade so
 * session commands route through the controller, restores the persisted queue
 * (without auto-play) on the service scope, and holds the process wake lock
 * while the controller reports playback. Snapshot flushes happen on teardown
 * and when the app UI goes hidden; media3 owns the notification foreground
 * lifecycle and offers no stop-foreground hook, so those are the service-side
 * flush sites available.
 */
@AndroidEntryPoint
@UnstableApi
class OmniPlaybackService : MediaLibraryService() {

    @Inject
    lateinit var playback: PlaybackStack

    @Inject
    lateinit var browseTree: BrowseTreeProvider

    @Inject
    lateinit var notificationProvider: PlaybackNotificationProvider

    @Inject
    lateinit var persistence: QueuePersistenceCoordinator

    private var session: MediaLibrarySession? = null
    private var sessionCallback: MediaSession.Callback? = null
    private var serviceScope: CoroutineScope? = null
    private var wakeLock: PlaybackWakeLock? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = PlaybackWakeLock(this)
        val controller = playback.controller
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch {
                controller.playerState.collect { wakeLock?.setHeld(it.isPlaying) }
            }
            scope.launch { restoreQueueWithin(controller, RESTORE_TIMEOUT_MS) }
        }
        val callback = SessionCallback(controller)
        sessionCallback = callback
        val sessionBuilder =
            MediaLibrarySession.Builder(this, playback.sessionPlayer, callback)
                .setId(SESSION_ID)
        createSessionActivity()?.let(sessionBuilder::setSessionActivity)
        session = sessionBuilder.build()
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** The published session, for diagnostics and test harnesses. */
    val currentSession: MediaLibrarySession?
        get() = session

    /** The connection callback, for test harnesses that assert the grant. */
    val currentCallback: MediaSession.Callback?
        get() = sessionCallback

    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            serviceScope?.launch { persistence.flush() }
        }
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        runBlocking { withTimeoutOrNull(FLUSH_TIMEOUT_MS) { persistence.flush() } }
        serviceScope?.cancel()
        serviceScope = null
        wakeLock?.release()
        wakeLock = null
        session?.release()
        session = null
        sessionCallback = null
        super.onDestroy()
    }

    private inner class SessionCallback(
        private val controller: PlaybackController,
    ) : MediaLibrarySession.Callback {

        /**
         * Grants the controllers the session's own command set plus the
         * notification buttons' custom commands. Never build on the deprecated
         * `super.onConnect`: its default result carries an empty player command
         * set, and media3 filters each timeline out of every update to a
         * controller with no `COMMAND_GET_TIMELINE` — which leaves the
         * notification manager with an empty timeline and no notification.
         */
        override fun onConnect(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val base = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controllerInfo)
            val commands = base.build().availableSessionCommands.buildUpon()
                .addSessionCommands(NotificationActionMapper.sessionCommands().commands)
                .build()
            return base.setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            val action = NotificationActionMapper.fromCustomAction(customCommand.customAction)
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            controller.applyAction(action)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            controllerInfo: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = runBlocking { browseTree.root() }
            return Futures.immediateFuture(LibraryResult.ofItem(root.toMediaItem(), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            controllerInfo: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))

        private fun BrowseNode.toMediaItem(): MediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(kind != BrowseKind.TRACK)
                    .setIsPlayable(isPlayable)
                    .build(),
            )
            .build()
    }

    /**
     * The package launch intent is the only entry point the app exposes; the
     * notification content intent reads it back from the session, so this is
     * the single source for notification taps. Immutable because no consumer
     * fills in extras.
     */
    private fun createSessionActivity(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            SESSION_ACTIVITY_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val SESSION_ID = "playback"
        const val SESSION_ACTIVITY_REQUEST_CODE = 0
        const val FLUSH_TIMEOUT_MS = 2_000L
        const val RESTORE_TIMEOUT_MS = 2_000L
    }
}
