package app.omnisonic.downloads

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.feature.downloads.service.DownloadNotificationCenter
import app.omnisonic.feature.downloads.service.DownloadNotificationState
import app.omnisonic.feature.downloads.service.DownloadService
import app.omnisonic.feature.downloads.service.DownloadServiceProviders
import app.omnisonic.feature.downloads.service.DownloadServiceBindings
import app.omnisonic.feature.downloads.service.DownloadServiceRuntime
import app.omnisonic.feature.downloads.service.DownloadUserActions
import app.omnisonic.core.data.downloads.DownloadEnqueuePort
import app.omnisonic.core.data.downloads.DownloadQueueCancellation
import app.omnisonic.core.data.downloads.DownloadUserEnqueue
import app.omnisonic.core.data.downloads.DownloadWorkStart
import app.omnisonic.core.data.downloads.OfflineRootWorkGate
import app.omnisonic.provider.api.DownloadEnqueueResult
import com.google.common.truth.Truth.assertThat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.testing.TestInstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.junit.Before
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
class DownloadServiceLifecycleTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        OrderLog.events.clear()
        FakeRuntime.requestedRuns = 0
        FakeRuntime.pauses = 0
        FakeRuntime.resumes = 0
        FakeRuntime.timeouts = 0
        FakeRuntime.stops = 0
        FakeRuntime.sticky = true
        hiltRule.inject()
    }

    @Test
    fun `foreground is posted before the runtime starts any work`() {
        val controller = Robolectric.buildService(
            DownloadService::class.java,
            DownloadService.startIntent(ApplicationProvider.getApplicationContext()),
        )
        val service = controller.create().get()

        assertThat(OrderLog.events.take(3))
            .containsExactly("channel", "notification", "started")
            .inOrder()
        val shadow = shadowOf(service)
        assertThat(shadow.lastForegroundNotification).isNotNull()
        assertThat(shadow.lastForegroundNotificationId)
            .isEqualTo(1002)

        controller.destroy()
        assertThat(FakeRuntime.stops).isEqualTo(1)
    }

    @Test
    fun `start commands coalesce through the runtime and report sticky only while work remains`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = Robolectric.buildService(
            DownloadService::class.java,
            DownloadService.startIntent(context),
        )
        val service = controller.create().get()

        repeat(3) {
            assertThat(service.onStartCommand(DownloadService.startIntent(context), 0, it + 1))
                .isEqualTo(Service.START_STICKY)
        }
        assertThat(FakeRuntime.requestedRuns).isEqualTo(3)

        FakeRuntime.sticky = false
        assertThat(service.onStartCommand(DownloadService.startIntent(context), 0, 9))
            .isEqualTo(Service.START_NOT_STICKY)

        service.onStartCommand(
            DownloadService.actionIntent(context, DownloadService.ACTION_PAUSE),
            0,
            10,
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(FakeRuntime.pauses).isEqualTo(1)

        service.onStartCommand(
            DownloadService.actionIntent(context, DownloadService.ACTION_RESUME),
            0,
            11,
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(FakeRuntime.resumes).isEqualTo(1)

        controller.destroy()
    }

    @Test
    fun `a dataSync timeout releases work instead of crashing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = Robolectric.buildService(
            DownloadService::class.java,
            DownloadService.startIntent(context),
        )
        val service = controller.create().get()

        service.onTimeout(4, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertThat(FakeRuntime.timeouts).isEqualTo(1)
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
        controller.destroy()
    }
}

private object OrderLog {
    val events = mutableListOf<String>()
}

private object FakeRuntime : DownloadServiceRuntime {
    var sticky = true
    var requestedRuns = 0
    var pauses = 0
    var resumes = 0
    var timeouts = 0
    var stops = 0

    override val keepSticky: Boolean get() = sticky

    override fun serviceStarted(onIdle: () -> Unit) {
        OrderLog.events += "started"
    }

    override fun requestRun() {
        requestedRuns++
    }

    override suspend fun pauseAll() {
        pauses++
    }

    override suspend fun resumeAll() {
        resumes++
    }

    override fun serviceStopping() {
        stops++
    }

    override fun onDataSyncTimeout() {
        timeouts++
    }
}

private object FakeUserActions : DownloadUserActions {
    override suspend fun enqueue(request: DownloadUserEnqueue): DownloadEnqueueResult =
        DownloadEnqueueResult.Enqueued

    override suspend fun pauseAll() = Unit
    override suspend fun resumeAll() = Unit
    override suspend fun pauseItem(rowId: Long): Boolean = true
    override suspend fun resumeItem(rowId: Long): Boolean = true
    override suspend fun retry(rowId: Long): Boolean = true
    override suspend fun retryAll(): Int = 0
    override suspend fun cancel(rowId: Long): Boolean = true
}

private object FakeNotifications : DownloadNotificationCenter {
    override fun ensureChannel() {
        OrderLog.events += "channel"
    }

    override fun initialNotification(): Notification {
        OrderLog.events += "notification"
        return Notification()
    }

    override fun build(state: DownloadNotificationState): Notification = Notification()

    override fun publish(state: DownloadNotificationState) = Unit
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DownloadServiceBindings::class, DownloadServiceProviders::class],
)
object FakeDownloadServiceModule {

    @Provides
    @Singleton
    fun runtime(): DownloadServiceRuntime = FakeRuntime

    @Provides
    @Singleton
    fun notifications(): DownloadNotificationCenter = FakeNotifications

    @Provides
    @Singleton
    fun rootGate(): OfflineRootWorkGate = OfflineRootWorkGate.NoWorkers

    @Provides
    @Singleton
    fun cancellation(): DownloadQueueCancellation = DownloadQueueCancellation.Direct

    @Provides
    @Singleton
    fun workStart(): DownloadWorkStart = DownloadWorkStart.None

    @Provides
    @Singleton
    fun userActions(): DownloadUserActions = FakeUserActions

    @Provides
    @Singleton
    fun enqueuePort(actions: DownloadUserActions): DownloadEnqueuePort = actions
}
