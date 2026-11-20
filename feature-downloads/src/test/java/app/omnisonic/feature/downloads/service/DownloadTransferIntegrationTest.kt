package app.omnisonic.feature.downloads.service

import androidx.test.core.app.ApplicationProvider
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.downloads.DownloadAuthReporting
import app.omnisonic.core.data.downloads.DownloadLease
import app.omnisonic.core.data.downloads.DownloadMediaEndpoint
import app.omnisonic.core.data.downloads.DownloadMediaResolution
import app.omnisonic.core.data.downloads.DownloadMediaSource
import app.omnisonic.core.data.downloads.DownloadQueueRepository
import app.omnisonic.core.data.downloads.DownloadQueueSettingsStore
import app.omnisonic.core.data.downloads.DownloadTransferResult
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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** End-to-end proof that the service loop drives the real task-3 engine for three workers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadTransferIntegrationTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private lateinit var db: OmnisonicDatabase
    private lateinit var settings: DownloadQueueSettingsStore
    private lateinit var root: File
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().build()

    @Before
    fun setUp() {
        db = OmnisonicDatabaseFactory(ApplicationProvider.getApplicationContext()).openInMemory()
        settings = DownloadQueueSettingsStore.inMemory()
        root = File(temp.root, "root").apply { mkdirs() }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = ByteArray(2_048) { (it % 251).toByte() }
                return MockResponse()
                    .setHeader("Content-Type", "audio/mpeg")
                    .setBody(Buffer().write(body))
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun `three workers run the real engine to completion`() = runTest {
        settings.setWorkerCount(3)
        repeat(3) { index ->
            db.downloadDao().upsert(
                DownloadItemEntity(
                    mediaKey = DownloadKeys.mediaKey(1, "song-$index", null, null),
                    providerId = 1,
                    status = DownloadStatus.PENDING.name,
                    title = "Song $index",
                    createdAt = 1_000L + index,
                    updatedAt = 1_000,
                ),
            )
        }
        val repository = DownloadQueueRepository(db.downloadDao())
        val storage = OfflineStorage(
            roots = OfflineRootSource { listOf(OfflineRoot("test-root", root)) },
            settings = settings,
            usableSpace = { OfflinePathRules.MIN_FREE_SPACE_BYTES * 10 },
            writableProbe = { true },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val media = DownloadMediaSource { request ->
            DownloadMediaResolution.Available(
                providerPath = "/music/${request.externalId}.flac",
                endpoints = listOf(
                    DownloadMediaEndpoint(
                        url = server.url("/audio/${request.externalId}").toString(),
                        client = client,
                    ),
                ),
            )
        }
        val realFactory = EngineDownloadTransferHandlerFactory(
            repository = repository,
            storage = storage,
            media = media,
            auth = object : DownloadAuthReporting {
                override suspend fun authRejected(providerId: Long) = Unit
                override suspend fun authSucceeded(providerId: Long) = Unit
            },
            logger = OmnisonicLogger(LogSink { _, _, _, _ -> }),
        )
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val countingFactory = DownloadTransferHandlerFactory { coordinator ->
            val handler = realFactory.create(coordinator)
            DownloadTransferHandler { lease: DownloadLease ->
                val current = running.incrementAndGet()
                maximum.updateAndGet { existing -> maxOf(existing, current) }
                try {
                    handler.transfer(lease)
                } finally {
                    running.decrementAndGet()
                }
            }
        }
        val runtime = ForegroundDownloadRuntime(
            repository = repository,
            settings = settings,
            storage = storage,
            network = NetworkMonitor { NetworkState(connected = true, wifi = true) },
            runGate = ProviderRunGate { ProviderStatus.OK },
            locks = NoLocks,
            notifications = NoNotifications,
            connectivity = NoConnectivity,
            handlers = countingFactory,
            workStart = app.omnisonic.core.data.downloads.DownloadWorkStart.None,
            logger = OmnisonicLogger(LogSink { _, _, _, _ -> }),
            clock = { testScheduler.currentTime },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        runtime.serviceStarted { }
        pumpUntil(timeoutMs = 20_000) {
            db.downloadDao().selectAll(ProfileScope.Unrestricted).isEmpty()
        }

        assertThat(server.requestCount).isEqualTo(3)
        assertThat(maximum.get()).isEqualTo(3)
        val mediaFiles = File(root, OfflinePathRules.MEDIA_DIRECTORY_NAME)
            .walkTopDown()
            .filter { it.isFile && it.name != OfflinePathRules.NOMEDIA_FILE_NAME }
            .toList()
        assertThat(mediaFiles).hasSize(3)
        assertThat(mediaFiles.all { it.length() == 2_048L }).isTrue()
    }

    private suspend fun TestScope.pumpUntil(
        timeoutMs: Long,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            runCurrent()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "download run did not finish in time" }
            withContext(Dispatchers.Default) { delay(5) }
        }
    }

    private object NoLocks : DownloadTransferLocks {
        override fun acquire(rowId: Long): DownloadTransferLock = DownloadTransferLock { }
    }

    private object NoNotifications : DownloadNotificationCenter {
        override fun ensureChannel() = Unit
        override fun initialNotification() = android.app.Notification()
        override fun build(state: DownloadNotificationState) = android.app.Notification()
        override fun publish(state: DownloadNotificationState) = Unit
    }

    private object NoConnectivity : DownloadConnectivityObserver {
        override fun start(onChange: () -> Unit) = Unit
        override fun stop() = Unit
    }
}
