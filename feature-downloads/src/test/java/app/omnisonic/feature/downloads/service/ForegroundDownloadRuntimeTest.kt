package app.omnisonic.feature.downloads.service

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.downloads.DownloadLease
import app.omnisonic.core.data.downloads.DownloadQueueRepository
import app.omnisonic.core.data.downloads.DownloadQueueSettingsStore
import app.omnisonic.core.data.downloads.DownloadTransferBlock
import app.omnisonic.core.data.downloads.DownloadTransferFailure
import app.omnisonic.core.data.downloads.DownloadTransferResult
import app.omnisonic.core.data.downloads.DownloadWorkerCoordinator
import app.omnisonic.core.data.downloads.OfflinePathRules
import app.omnisonic.core.data.downloads.OfflineRoot
import app.omnisonic.core.data.downloads.OfflineRootSource
import app.omnisonic.core.data.downloads.OfflineStorage
import app.omnisonic.core.data.network.NetworkMonitor
import app.omnisonic.core.data.network.NetworkState
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundDownloadRuntimeTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private lateinit var db: OmnisonicDatabase
    private lateinit var settings: DownloadQueueSettingsStore
    private lateinit var root: File
    private lateinit var notifications: RecordingNotifications
    private lateinit var locks: RecordingLocks
    private lateinit var connectivity: FakeConnectivity
    private lateinit var network: FakeNetwork
    private lateinit var handlers: FakeHandlers

    private val logLines = mutableListOf<String>()

    private var gateStatus = ProviderStatus.OK
    private var freeBytes = OfflinePathRules.MIN_FREE_SPACE_BYTES * 10

    @Before
    fun setUp() {
        db = OmnisonicDatabaseFactory(ApplicationProvider.getApplicationContext()).openInMemory()
        settings = DownloadQueueSettingsStore.inMemory()
        root = File(temp.root, "root").apply { mkdirs() }
        notifications = RecordingNotifications()
        locks = RecordingLocks()
        connectivity = FakeConnectivity()
        network = FakeNetwork()
        handlers = FakeHandlers()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `one session recovers startup rows and coalesces repeated starts`() = runTest {
        settings.setWorkerCount(1)
        seed("orphan", status = DownloadStatus.ACTIVE)
        seed("pending", updatedAt = 2_000)
        val gate = CompletableDeferred<Unit>()
        handlers.onTransfer = {
            gate.await()
            DownloadTransferResult.Completed("done", 1)
        }
        val runtime = runtime(StandardTestDispatcher(testScheduler))
        var idle = false

        runtime.serviceStarted { idle = true }
        repeat(5) { runtime.requestRun() }
        pumpUntil { handlers.claims.size == 1 }

        assertThat(handlers.claims).hasSize(1)
        assertThat(row("orphan")!!.status).isEqualTo(DownloadStatus.ACTIVE.name)
        assertThat(row("pending")!!.status).isEqualTo(DownloadStatus.PENDING.name)

        gate.complete(Unit)
        pumpUntil { row("orphan") == null && row("pending") == null && idle }
        assertThat(runtime.keepSticky).isFalse()
    }

    @Test
    fun `a worker failure does not cancel its sibling and the failed row retries`() = runTest {
        settings.setWorkerCount(2)
        seed("bad", title = "bad")
        seed("good", title = "good")
        var failedOnce = false
        val goodGate = CompletableDeferred<Unit>()
        handlers.onTransfer = { lease ->
            when (lease.rowId) {
                row("bad")!!.id -> if (!failedOnce) {
                    failedOnce = true
                    throw IOException("worker crash")
                } else {
                    DownloadTransferResult.Completed("bad", 1)
                }
                else -> {
                    goodGate.await()
                    DownloadTransferResult.Completed("good", 1)
                }
            }
        }
        val runtime = runtime(StandardTestDispatcher(testScheduler))

        runtime.serviceStarted { }
        pumpUntil { handlers.claims.size == 2 }
        assertThat(failedOnce).isTrue()

        goodGate.complete(Unit)
        pumpUntil { row("bad") == null && row("good") == null }
        assertThat(locks.acquired).containsExactlyElementsIn(locks.released)
    }

    @Test
    fun `retries wake at the earliest due time without busy spinning`() = runTest {
        settings.setWorkerCount(1)
        seed("retry")
        var failures = 0
        handlers.onTransfer = { lease ->
            failures++
            handlers.coordinator!!.recordFailure(lease, "boom")
            DownloadTransferResult.Retry(DownloadTransferFailure.HTTP_STATUS, DownloadStatus.PENDING)
        }
        val runtime = runtime(StandardTestDispatcher(testScheduler))
        var idle = false

        runtime.serviceStarted { idle = true }
        pumpUntil { row("retry")?.errorCount == 1 }
        assertThat(handlers.claims).hasSize(1)

        advanceTimeBy(9_999)
        runCurrent()
        pumpUntil { handlers.claims.size == 1 }
        assertThat(handlers.claims).hasSize(1)

        advanceTimeBy(2)
        pumpUntil { handlers.claims.size == 2 }

        for (expected in 3..6) {
            advanceTimeBy(10_000)
            pumpUntil { handlers.claims.size >= expected }
        }
        pumpUntil { row("retry")?.status == DownloadStatus.FAILED.name && idle }
        assertThat(failures).isEqualTo(6)
        advanceTimeBy(3_600_000)
        runCurrent()
        pumpUntil { handlers.claims.size == 6 }
        assertThat(handlers.claims).hasSize(6)
        assertThat(runtime.keepSticky).isFalse()
    }

    @Test
    fun `wifi only blocking keeps rows pending and resumes on connectivity`() = runTest {
        seed("wifi-row")
        network.state = NetworkState(connected = true, wifi = false)
        val runtime = runtime(StandardTestDispatcher(testScheduler))

        runtime.serviceStarted { }
        pumpUntil { notifications.states.any { it.blocked == DownloadRunBlock.WIFI_REQUIRED } }
        assertThat(handlers.claims).isEmpty()
        assertThat(row("wifi-row")!!.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(row("wifi-row")!!.errorCount).isEqualTo(0)

        network.state = NetworkState(connected = true, wifi = true)
        handlers.onTransfer = { DownloadTransferResult.Completed("done", 1) }
        connectivity.emit()
        pumpUntil { row("wifi-row") == null }
    }

    @Test
    fun `offline and auth gates block claims without touching rows`() = runTest {
        settings.setWifiOnly(false)
        seed("offline-row")
        gateStatus = ProviderStatus.OFFLINE
        val runtime = runtime(StandardTestDispatcher(testScheduler))

        runtime.serviceStarted { }
        repeatRealPumps(20)
        assertThat(handlers.claims).isEmpty()
        assertThat(row("offline-row")!!.status).isEqualTo(DownloadStatus.PENDING.name)

        gateStatus = ProviderStatus.AUTH_FAILED
        connectivity.emit()
        repeatRealPumps(20)
        assertThat(handlers.claims).isEmpty()

        gateStatus = ProviderStatus.OK
        handlers.onTransfer = { DownloadTransferResult.Completed("done", 1) }
        connectivity.emit()
        pumpUntil { row("offline-row") == null }
    }

    @Test
    fun `the free space floor blocks claims without an error increment`() = runTest {
        freeBytes = 0
        seed("low-space")
        val runtime = runtime(StandardTestDispatcher(testScheduler))

        runtime.serviceStarted { }
        pumpUntil { notifications.states.any { it.blocked == DownloadRunBlock.STORAGE_LOW_SPACE } }
        assertThat(handlers.claims).isEmpty()
        assertThat(row("low-space")!!.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(row("low-space")!!.errorCount).isEqualTo(0)
    }

    @Test
    fun `an active network loss requeues without a failure and waits for a trigger`() = runTest {
        settings.setWifiOnly(false)
        seed("loss")
        handlers.onTransfer = { DownloadTransferResult.Blocked(DownloadTransferBlock.NETWORK_UNAVAILABLE) }
        val runtime = runtime(StandardTestDispatcher(testScheduler))

        runtime.serviceStarted { }
        pumpUntil { handlers.claims.size == 1 }
        pumpUntil { row("loss")!!.status == DownloadStatus.PENDING.name }
        assertThat(row("loss")!!.errorCount).isEqualTo(0)
        assertThat(row("loss")!!.updatedAt).isEqualTo(1_000L)

        repeatRealPumps(20)
        val settledClaims = handlers.claims.size

        advanceTimeBy(3_600_000)
        runCurrent()
        repeatRealPumps(10)
        assertWithMessage("logs=$logLines").that(handlers.claims).hasSize(settledClaims)

        connectivity.emit()
        pumpUntil { handlers.claims.size > settledClaims }
    }

    @Test
    fun `locks and leases are released when the session is cancelled`() = runTest {
        seed("cancel")
        handlers.onTransfer = { awaitCancellation() }
        val runtime = runtime(StandardTestDispatcher(testScheduler))

        runtime.serviceStarted { }
        pumpUntil { handlers.claims.size == 1 && locks.acquired.size == 1 }

        runtime.serviceStopping()
        pumpUntil { locks.released.size == 1 }
        pumpUntil { row("cancel")!!.status == DownloadStatus.PENDING.name }
        assertThat(row("cancel")!!.errorCount).isEqualTo(0)
    }

    @Test
    fun `the root change gate drains active work and stops new claims`() = runTest {
        settings.setWorkerCount(1)
        val firstId = seed("first")
        val firstGate = CompletableDeferred<Unit>()
        handlers.onTransfer = { lease ->
            if (lease.rowId == firstId) {
                firstGate.await()
                DownloadTransferResult.Completed("first", 1)
            } else {
                DownloadTransferResult.Completed("more", 1)
            }
        }
        val runtime = runtime(StandardTestDispatcher(testScheduler))

        runtime.serviceStarted { }
        pumpUntil { handlers.claims.size == 1 }

        val paused = async { runtime.pauseForRootChange() }
        runCurrent()
        assertThat(paused.isCompleted).isFalse()

        seed("second", updatedAt = 2_000)
        runtime.requestRun()
        repeatRealPumps(10)
        assertThat(handlers.claims).hasSize(1)

        firstGate.complete(Unit)
        pumpUntil { paused.isCompleted }
        pumpUntil { row("first") == null }

        runtime.resumeAfterRootChange()
        pumpUntil { row("second") == null }
    }

    @Test
    fun `a worker count increase admits another slot while running`() = runTest {
        settings.setWorkerCount(1)
        seed("one")
        seed("two", updatedAt = 2_000)
        val gate = CompletableDeferred<Unit>()
        handlers.onTransfer = {
            gate.await()
            DownloadTransferResult.Completed("done", 1)
        }
        val runtime = runtime(StandardTestDispatcher(testScheduler))

        runtime.serviceStarted { }
        pumpUntil { handlers.claims.size == 1 }
        assertThat(handlers.claims).hasSize(1)

        settings.setWorkerCount(2)
        pumpUntil { handlers.claims.size == 2 }

        gate.complete(Unit)
        pumpUntil { row("one") == null && row("two") == null }
    }

    private fun runtime(dispatcher: TestDispatcher): ForegroundDownloadRuntime =
        ForegroundDownloadRuntime(
            repository = DownloadQueueRepository(db.downloadDao()),
            settings = settings,
            storage = OfflineStorage(
                roots = OfflineRootSource { listOf(OfflineRoot("test-root", root)) },
                settings = settings,
                usableSpace = { freeBytes },
                writableProbe = { true },
                ioDispatcher = dispatcher,
            ),
            network = network,
            runGate = ProviderRunGate { gateStatus },
            locks = locks,
            notifications = notifications,
            connectivity = connectivity,
            handlers = handlers,
            workStart = app.omnisonic.core.data.downloads.DownloadWorkStart.None,
            logger = OmnisonicLogger(LogSink { level, tag, message, error ->
                logLines += "$level/$tag: $message ${error?.stackTraceToString()?.take(400)}"
                while (logLines.size > 40) logLines.removeAt(0)
            }),
            clock = { dispatcher.scheduler.currentTime },
            ioDispatcher = dispatcher,
        )

    private suspend fun seed(
        mediaKey: String,
        status: DownloadStatus = DownloadStatus.PENDING,
        title: String = "Track",
        errorCount: Int = 0,
        progressPercent: Int = 0,
        updatedAt: Long = 1_000,
    ): Long = db.downloadDao().upsert(
        DownloadItemEntity(
            mediaKey = mediaKey,
            providerId = 1,
            status = status.name,
            title = title,
            progressPercent = progressPercent,
            errorCount = errorCount,
            createdAt = 1_000,
            updatedAt = updatedAt,
        ),
    )

    private suspend fun row(mediaKey: String): DownloadItemEntity? =
        db.downloadDao().selectByMediaKey(mediaKey)

    /**
     * Room's suspend queries complete on executor threads, so the virtual
     * scheduler must be pumped while real time drains those completions.
     */
    private suspend fun TestScope.pumpUntil(
        timeoutMs: Long = 5_000,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            runCurrent()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) {
                "condition was not met in time: claims=${handlers.claims.take(8)}, " +
                    "lastNotification=${notifications.states.lastOrNull()}, " +
                    "logs=${logLines.takeLast(6)}"
            }
            withContext(Dispatchers.Default) { delay(2) }
        }
    }

    private suspend fun TestScope.repeatRealPumps(times: Int) {
        repeat(times) {
            runCurrent()
            withContext(Dispatchers.Default) { delay(2) }
        }
    }

    private class RecordingNotifications : DownloadNotificationCenter {
        val states = mutableListOf<DownloadNotificationState>()

        override fun ensureChannel() = Unit

        override fun initialNotification(): Notification = Notification()

        override fun build(state: DownloadNotificationState): Notification = Notification()

        override fun publish(state: DownloadNotificationState) {
            states += state
        }
    }

    private class RecordingLocks : DownloadTransferLocks {
        val acquired = mutableListOf<Long>()
        val released = mutableListOf<Long>()

        override fun acquire(rowId: Long): DownloadTransferLock {
            acquired += rowId
            val done = AtomicBoolean(false)
            return DownloadTransferLock {
                if (done.compareAndSet(false, true)) released += rowId
            }
        }
    }

    private class FakeConnectivity : DownloadConnectivityObserver {
        private var onChange: (() -> Unit)? = null

        override fun start(onChange: () -> Unit) {
            this.onChange = onChange
        }

        override fun stop() {
            onChange = null
        }

        fun emit() {
            onChange?.invoke()
        }
    }

    private class FakeNetwork : NetworkMonitor {
        var state = NetworkState(connected = true, wifi = true)

        override fun current(): NetworkState = state
    }

    private class FakeHandlers : DownloadTransferHandlerFactory {
        var onTransfer: suspend (DownloadLease) -> DownloadTransferResult = {
            DownloadTransferResult.Completed("done", 1)
        }
        val claims = mutableListOf<Long>()
        var coordinator: DownloadWorkerCoordinator? = null

        override fun create(coordinator: DownloadWorkerCoordinator): DownloadTransferHandler {
            this.coordinator = coordinator
            return DownloadTransferHandler { lease ->
                claims += lease.rowId
                val result = onTransfer(lease)
                if (result is DownloadTransferResult.Completed) coordinator.complete(lease)
                result
            }
        }
    }
}
