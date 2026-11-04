package app.omnisonic.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.media3.session.MediaButtonReceiver
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.playback.di.PlaybackStack
import app.omnisonic.playback.service.OmniPlaybackService
import com.google.common.truth.Truth.assertThat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34])
class OmniPlaybackServiceTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    /**
     * Bootstrap smoke: the service builds its session on the exposed player
     * facade and tears down cleanly. One test per service instance because
     * media3 keeps session-id state static for the process. (A full
     * MediaController connect is shadow-blocked under Robolectric: the shadow
     * delivers onServiceConnected with a null component name.)
     */
    @Test
    fun serviceBootsOnTheExposedPlayerAndTearsDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = Robolectric.buildService(OmniPlaybackService::class.java)
        val service = controller.create().get()
        shadowOf(Looper.getMainLooper()).idle()

        val stack = EntryPointAccessors.fromApplication(
            context.applicationContext,
            PlaybackStackEntryPoint::class.java,
        ).playbackStack()

        val session = requireNotNull(service.currentSession)
        assertThat(session.player).isSameInstanceAs(stack.sessionPlayer)

        service.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(service.currentSession).isNull()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PlaybackStackEntryPoint {
        fun playbackStack(): PlaybackStack
    }

    @Test
    fun serviceIsExportedWithMediaPlaybackForegroundType() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val info = context.packageManager.getServiceInfo(
            ComponentName(context, OmniPlaybackService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertThat(info.exported).isTrue()
        assertThat(info.foregroundServiceType)
            .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    @Test
    fun mediaButtonReceiverAnswersMediaButtonBroadcasts() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val receivers = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_MEDIA_BUTTON),
            0,
        )

        assertThat(receivers.map { it.activityInfo.name }).contains(MediaButtonReceiver::class.java.name)
    }
}
