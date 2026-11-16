package app.omnisonic.playback.service

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One connect call to the media session service. Production uses
 * [ServiceControllerConnector]; tests replace it to observe the bind without a
 * real service connection.
 */
fun interface ControllerConnector {
    /** Starts the bind. An ordinary failed connection must not throw. */
    fun connect()
}

/**
 * Binds the process to [OmniPlaybackService]. Media3 arms its notification
 * machinery only when a controller connects to the service session, so without
 * this bind the session never registers and no playback notification appears
 * (see `docs/omnisonic/playback/media-notification-fix.md`). The bind also
 * starts the service, whose queue restore runs behind the exactly-once guard
 * on `PlaybackController.restoreQueue`.
 *
 * The connect is one-shot per process and must originate on the main thread
 * (`Application.onCreate`) because [MediaController] connects on the
 * application looper.
 */
@Singleton
class PlaybackSessionHost @Inject constructor(
    private val connector: ControllerConnector,
) {

    private var connected = false

    fun connect() {
        if (connected) return
        connected = true
        runCatching { connector.connect() }
            .onFailure { Log.w(TAG, "playback session connect failed; notification unavailable", it) }
    }

    internal companion object {
        const val TAG = "PlaybackSessionHost"
    }
}

/**
 * Builds the real controller against [OmniPlaybackService]. The controller is
 * held for the process lifetime: releasing it unbinds the service, and media3
 * stops a service with no bound controller while nothing is playing.
 */
@UnstableApi
class ServiceControllerConnector(
    private val context: Context,
) : ControllerConnector {

    private var controller: MediaController? = null

    override fun connect() {
        if (isTestRuntime()) return
        val token = SessionToken(context, ComponentName(context, OmniPlaybackService::class.java))
        val future: ListenableFuture<MediaController> =
            MediaController.Builder(context, token).buildAsync()
        Futures.addCallback(
            future,
            object : FutureCallback<MediaController> {
                override fun onSuccess(result: MediaController) {
                    controller = result
                }

                override fun onFailure(t: Throwable) {
                    Log.w(PlaybackSessionHost.TAG, "playback session connect failed; notification unavailable", t)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    /**
     * Robolectric's shadow `bindService` delivers a null `ComponentName` to
     * `onServiceConnected` and media3 throws an NPE while draining the looper;
     * the JVM app suite boots this connector through `Application.onCreate`, so
     * the real bind is device-only. Test-environment probe, not a production
     * behaviour switch.
     */
    private fun isTestRuntime(): Boolean =
        Build.FINGERPRINT.contains("robolectric", ignoreCase = true)
}
