package app.omnisonic.playback

import android.content.ComponentName
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.media3.session.MediaButtonReceiver
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.MainActivity
import app.omnisonic.playback.di.PlaybackStack
import app.omnisonic.playback.queue.QueuePersistenceCoordinator
import app.omnisonic.playback.service.OmniPlaybackService
import com.google.common.truth.Truth.assertThat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

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

        val sessionActivity = requireNotNull(session.sessionActivity)
        val shadowActivity = shadowOf(sessionActivity)
        assertThat(shadowActivity.isImmutable).isTrue()
        val launchIntent = shadowActivity.savedIntent
        assertThat(launchIntent.action).isEqualTo(Intent.ACTION_MAIN)
        assertThat(launchIntent.categories).contains(Intent.CATEGORY_LAUNCHER)
        assertThat(launchIntent.component)
            .isEqualTo(ComponentName(context, MainActivity::class.java))
        assertThat(launchIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            .isNotEqualTo(0)

        val persistence = service.persistence
        val flushStarted = CountDownLatch(1)
        service.persistence = mockk<QueuePersistenceCoordinator>(relaxed = true).also { mock ->
            coEvery { mock.flush() } coAnswers {
                flushStarted.countDown()
                delay(2_000)
            }
        }

        val trimElapsed = measureTimeMillis {
            service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        }

        assertThat(flushStarted.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(trimElapsed).isLessThan(1_000)
        service.persistence = persistence
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
    fun serviceDeclaresMedia3AndPlatformMediaBrowserIntentActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        listOf(
            "androidx.media3.session.MediaLibraryService",
            "android.media.browse.MediaBrowserService",
        ).forEach { action ->
            val matches = context.packageManager.queryIntentServices(
                Intent(action).setPackage(context.packageName),
                0,
            )
            assertThat(matches.map { it.serviceInfo.name })
                .contains(OmniPlaybackService::class.java.name)
        }
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
