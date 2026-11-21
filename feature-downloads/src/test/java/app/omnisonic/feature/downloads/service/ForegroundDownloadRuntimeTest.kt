package app.omnisonic.feature.downloads.service

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.downloads.AutomaticRuleGenerationStore
import app.omnisonic.core.data.downloads.AutomaticRuleKind
import app.omnisonic.core.data.downloads.AutomaticRuleReactionFactory
import app.omnisonic.core.data.downloads.AutomaticRuleRepository
import app.omnisonic.core.data.downloads.DownloadCompletionRecovery
import app.omnisonic.core.data.downloads.DownloadEnqueuerFactory
import app.omnisonic.core.data.downloads.DownloadExportPublisher
import app.omnisonic.core.data.downloads.DownloadLease
import app.omnisonic.core.data.downloads.DownloadQueueRepository
import app.omnisonic.core.data.downloads.DownloadQueueSettingsStore
import app.omnisonic.core.data.downloads.DownloadTransferBlock
import app.omnisonic.core.data.downloads.DownloadTransferFailure
import app.omnisonic.core.data.downloads.DownloadTransferJournal
import app.omnisonic.core.data.downloads.DownloadTransferResult
import app.omnisonic.core.data.downloads.DownloadTransferState
import app.omnisonic.core.data.downloads.DownloadWorkerCoordinator
import app.omnisonic.core.data.downloads.ExportCopyOutcome
import app.omnisonic.core.data.downloads.ExportDocumentPresence
import app.omnisonic.core.data.downloads.ExportDocumentRef
import app.omnisonic.core.data.downloads.ExportDocumentRequest
import app.omnisonic.core.data.downloads.PendingExportDocument
import app.omnisonic.core.data.downloads.ExportDocumentStore
import app.omnisonic.core.data.downloads.ExportNaming
import app.omnisonic.core.data.downloads.ExportSession
import app.omnisonic.core.data.downloads.ExportSessionJournal
import app.omnisonic.core.data.downloads.ExportSessionPhase
import app.omnisonic.core.data.downloads.OfflineFileSweep
import app.omnisonic.core.data.downloads.OfflineMediaIdentity
import app.omnisonic.core.data.downloads.OfflineMediaRepository
import app.omnisonic.core.data.downloads.OfflinePathResult
import app.omnisonic.core.data.downloads.OfflinePathRules
import app.omnisonic.core.data.downloads.OfflineRoot
import app.omnisonic.core.data.downloads.OfflineRootSelection
import app.omnisonic.core.data.downloads.OfflineRootSource
import app.omnisonic.core.data.downloads.OfflineStorage
import app.omnisonic.core.data.network.NetworkMonitor
import app.omnisonic.core.data.network.NetworkState
import app.omnisonic.core.data.sync.LibrarySyncStatusRepository
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import app.omnisonic.provider.api.ProviderTraits
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
    private lateinit var exportStore: RecordingExportStore
    private lateinit var exportSessions: ExportSessionJournal
    private lateinit var ruleGenerations: AutomaticRuleGenerationStore

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
        exportStore = RecordingExportStore()
        exportSessions = ExportSessionJournal(File(temp.root, "export-sessions"))
        ruleGenerations = AutomaticRuleGenerationStore()
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
        pumpUntil { row("bad")?.errorCount == 1 }
        assertThat(failedOnce).isTrue()
        assertThat(handlers.claims).hasSize(2)

        advanceTimeBy(10_000)
        pumpUntil { handlers.claims.size == 3 }
        goodGate.complete(Unit)
        pumpUntil { row("bad") == null && row("good") == null }
        assertThat(locks.acquired).containsExactlyElementsIn(locks.released)
    }

    @Test
    fun `an escaping worker failure is counted and backed off instead of spinning`() = runTest {
        settings.setWorkerCount(1)
        seed("bad")
        handlers.onTransfer = { throw IOException("persistent worker crash") }
        val runtime = runtime(StandardTestDispatcher(testScheduler))
        var idle = false

        runtime.serviceStarted { idle = true }
        pumpUntil { row("bad")?.errorCount == 1 }
        pumpUntil { handlers.coordinator!!.activeLeaseCount() == 0 }
        assertThat(handlers.claims).hasSize(1)

        for (expected in 2..6) {
            advanceTimeBy(10_000)
            pumpUntil { handlers.claims.size >= expected }
        }
        pumpUntil { row("bad")?.status == DownloadStatus.FAILED.name && idle }
        assertThat(handlers.claims).hasSize(6)
        assertThat(row("bad")!!.errorCount).isEqualTo(6)
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
    fun `the root gate waits for a claim already inside the provider gate`() = runTest {
        settings.setWorkerCount(1)
        seed("first")
        val providerGateEntered = CompletableDeferred<Unit>()
        val releaseProviderGate = CompletableDeferred<Unit>()
        val finishTransfer = CompletableDeferred<Unit>()
        handlers.onTransfer = {
            finishTransfer.await()
            DownloadTransferResult.Completed("done", 1)
        }
        val runtime = runtime(
            StandardTestDispatcher(testScheduler),
            providerGate = ProviderRunGate {
                providerGateEntered.complete(Unit)
                releaseProviderGate.await()
                ProviderStatus.OK
            },
        )

        runtime.serviceStarted { }
        pumpUntil { providerGateEntered.isCompleted }
        val paused = async { runtime.pauseForRootChange() }
        runCurrent()
        assertThat(paused.isCompleted).isFalse()

        releaseProviderGate.complete(Unit)
        pumpUntil { handlers.claims.size == 1 }
        assertThat(paused.isCompleted).isFalse()

        finishTransfer.complete(Unit)
        pumpUntil { paused.isCompleted }
    }

    @Test
    fun `startup recovery finishes a pending root move before adopting files`() = runTest {
        val target = File(temp.root, "target").apply { mkdirs() }
        settings.publishRoot(OfflineRootSelection.Volume("test-root"), "test-root", 1)
        val mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null)
        seed(mediaKey)
        val plan = (OfflinePathRules.plan(OfflineMediaIdentity(1, "/music/song-1.flac")) as OfflinePathResult.Planned).plan
        val finalFile = File(root, plan.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(64) { (it % 251).toByte() })
        }
        val part = File(root, plan.relativePartPath)
        DownloadTransferJournal().write(
            part,
            DownloadTransferState(identityHash = plan.hash, totalBytes = 64, mediaKey = mediaKey),
        )
        settings.beginRootMove(OfflineRootSelection.Volume("target"), "target", 2)
        val runtime = runtime(StandardTestDispatcher(testScheduler), additionalRoots = listOf(OfflineRoot("target", target)))
        var idle = false

        runtime.serviceStarted { idle = true }
        pumpUntil { idle }

        val moved = File(target, plan.relativePath)
        assertThat(moved.length()).isEqualTo(64)
        assertThat(finalFile.exists()).isFalse()
        assertThat(db.offlineFileDao().selectBySourceKey(mediaKey)!!.offlinePath).isEqualTo(plan.relativePath)
        assertThat(db.downloadDao().selectByMediaKey(mediaKey)).isNull()
        assertThat(handlers.claims).isEmpty()
    }

    @Test
    fun `startup recovery adopts a crash orphan before any claim`() = runTest {
        val mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null)
        seed(mediaKey)
        val plan = (OfflinePathRules.plan(OfflineMediaIdentity(1, "/music/song-1.flac")) as OfflinePathResult.Planned).plan
        val finalFile = File(root, plan.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(64) { (it % 251).toByte() })
        }
        val part = File(root, plan.relativePartPath)
        DownloadTransferJournal().write(
            part,
            DownloadTransferState(
                identityHash = plan.hash,
                totalBytes = finalFile.length(),
                mediaKey = mediaKey,
            ),
        )
        val runtime = runtime(StandardTestDispatcher(testScheduler))
        var idle = false

        runtime.serviceStarted { idle = true }
        pumpUntil { idle }

        assertThat(handlers.claims).isEmpty()
        assertThat(db.downloadDao().selectByMediaKey(mediaKey)).isNull()
        val offline = db.offlineFileDao().selectBySourceKey(mediaKey)
        assertThat(offline).isNotNull()
        assertThat(offline!!.offlinePath).isEqualTo(plan.relativePath)
        assertThat(offline.sizeBytes).isEqualTo(64)
        assertThat(finalFile.exists()).isTrue()
        assertThat(DownloadTransferJournal().stateFile(part).exists()).isFalse()
    }

    @Test
    fun `startup recovery sweeps an offline row whose file is gone before any claim`() = runTest {
        File(root, OfflinePathRules.MEDIA_DIRECTORY_NAME).mkdirs()
        db.offlineFileDao().upsert(
            OfflineFileEntity(
                providerId = 1,
                mediaType = "SONG",
                sourceKey = DownloadKeys.mediaKey(1, "gone", null, null),
                offlinePath = "OfflineMedias/0/missing.flac",
                sizeBytes = 64,
            ),
        )
        val runtime = runtime(StandardTestDispatcher(testScheduler))
        var idle = false

        runtime.serviceStarted { idle = true }
        pumpUntil { idle }

        assertThat(db.offlineFileDao().count()).isEqualTo(0)
        assertThat(handlers.claims).isEmpty()
    }

    @Test
    fun `a rule added during a session expands through the runtime`() = runTest {
        settings.setWorkerCount(1)
        seedRuleSource()
        seed("keepalive")
        val gate = CompletableDeferred<Unit>()
        handlers.onTransfer = {
            gate.await()
            DownloadTransferResult.Completed("done", 1)
        }
        val runtime = runtime(StandardTestDispatcher(testScheduler))
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)

        try {
            runtime.serviceStarted { }
            pumpUntil { handlers.claims.size == 1 }

            AutomaticRuleRepository(db).add(1, AutomaticRuleKind.FAVORITES)
            pumpUntil { row(key) != null }
            assertThat(row(key)!!.tempCache).isTrue()

            AutomaticRuleRepository(db).remove(1, AutomaticRuleKind.FAVORITES, "")
            pumpUntil { row(key) == null }
        } finally {
            gate.complete(Unit)
            runtime.serviceStopping()
        }
    }

    @Test
    fun `a rule-only session expands and claims before it can go idle`() = runTest {
        settings.setWorkerCount(1)
        seedRuleSource()
        AutomaticRuleRepository(db).add(1, AutomaticRuleKind.FAVORITES)
        val transferGate = CompletableDeferred<Unit>()
        handlers.onTransfer = {
            transferGate.await()
            DownloadTransferResult.Completed("done", 1)
        }
        val runtime = runtime(StandardTestDispatcher(testScheduler))
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)

        try {
            runtime.serviceStarted { }
            pumpUntil { handlers.claims.size == 1 }
            assertThat(row(key)!!.status).isEqualTo(DownloadStatus.ACTIVE.name)
        } finally {
            transferGate.complete(Unit)
            runtime.serviceStopping()
        }
    }

    @Test
    fun `connectivity retries an automatic expansion blocked by the provider gate`() = runTest {
        settings.setWorkerCount(1)
        seedRuleSource()
        seed("keepalive")
        val transferGate = CompletableDeferred<Unit>()
        handlers.onTransfer = {
            transferGate.await()
            DownloadTransferResult.Completed("done", 1)
        }
        val runtime = runtime(StandardTestDispatcher(testScheduler))
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)

        try {
            runtime.serviceStarted { }
            pumpUntil { handlers.claims.size == 1 }

            gateStatus = ProviderStatus.OFFLINE
            AutomaticRuleRepository(db).add(1, AutomaticRuleKind.FAVORITES)
            pumpUntil {
                val state = ruleGenerations.state.value
                state.verified && state.generation?.expansions?.singleOrNull()?.blockedCount == 1
            }
            repeatRealPumps(30)
            assertThat(row(key)).isNull()
            val blockedSequence = ruleGenerations.state.value.generation!!.sequence

            gateStatus = ProviderStatus.OK
            connectivity.emit()

            pumpUntil {
                row(key) != null &&
                    ruleGenerations.state.value.generation!!.sequence > blockedSequence
            }
            assertThat(row(key)!!.tempCache).isTrue()
        } finally {
            transferGate.complete(Unit)
            runtime.serviceStopping()
        }
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

    @Test
    fun `an export session settles its row before app-private orphan adoption`() = runTest {
        settings.setWorkerCount(1)
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)
        seed(key, status = DownloadStatus.PENDING, exportTarget = "content://media/external_primary/downloads/9")
        val request = ExportNaming.request(key, "Track", "flac")
        val ref = exportStore.insertPending(request)!!
        exportStore.clearPending(ref)
        exportSessions.write(
            ExportSession(key, ref.uri, request.displayName, request.mimeType, 4, ExportSessionPhase.PUBLISHED),
        )
        val plan = (OfflinePathRules.plan(OfflineMediaIdentity(1, "/music/track.flac")) as OfflinePathResult.Planned).plan
        val orphan = File(root, plan.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4))
        }
        DownloadTransferJournal().write(
            File(root, plan.relativePartPath),
            DownloadTransferState(identityHash = plan.hash, totalBytes = 4, mediaKey = key),
        )

        val runtime = runtime(StandardTestDispatcher(testScheduler))
        runtime.serviceStarted { }

        pumpUntil { row(key) == null && !orphan.exists() }
        assertThat(db.offlineFileDao().count()).isEqualTo(0)
        assertThat(exportStore.documents).hasSize(1)
        assertThat(exportSessions.read(key)).isNull()
    }

    private fun runtime(
        dispatcher: TestDispatcher,
        providerGate: ProviderRunGate = ProviderRunGate { gateStatus },
        additionalRoots: List<OfflineRoot> = emptyList(),
    ): ForegroundDownloadRuntime {
        val storage = OfflineStorage(
            roots = OfflineRootSource { listOf(OfflineRoot("test-root", root)) + additionalRoots },
            settings = settings,
            usableSpace = { freeBytes },
            writableProbe = { true },
            ioDispatcher = dispatcher,
        )
        return ForegroundDownloadRuntime(
            repository = DownloadQueueRepository(db.downloadDao()),
            settings = settings,
            storage = storage,
            completionRecovery = DownloadCompletionRecovery(
                db = db,
                storage = storage,
                publisher = OfflineMediaRepository(db),
                logger = OmnisonicLogger(LogSink { _, _, _, _ -> }),
                ioDispatcher = dispatcher,
            ),
            exportPublisher = DownloadExportPublisher(
                queues = DownloadQueueRepository(db.downloadDao()),
                offline = OfflineMediaRepository(db),
                storage = storage,
                store = exportStore,
                sessions = exportSessions,
                logger = OmnisonicLogger(LogSink { _, _, _, _ -> }),
                ioDispatcher = dispatcher,
            ),
            offlineSweep = OfflineFileSweep(
                db = db,
                storage = storage,
                logger = OmnisonicLogger(LogSink { _, _, _, _ -> }),
                ioDispatcher = dispatcher,
            ),
            network = network,
            runGate = providerGate,
            locks = locks,
            notifications = notifications,
            connectivity = connectivity,
            handlers = handlers,
            enqueuerFactory = DownloadEnqueuerFactory(db) { ProviderTraits() },
            workStart = app.omnisonic.core.data.downloads.DownloadWorkStart.None,
            automaticRuleReactions = AutomaticRuleReactionFactory(
                database = db,
                traitsFor = { ProviderTraits() },
                syncStatus = LibrarySyncStatusRepository(),
                generations = ruleGenerations,
                storage = storage,
                logger = OmnisonicLogger(LogSink { _, _, _, _ -> }),
            ),
            logger = OmnisonicLogger(LogSink { level, tag, message, error ->
                logLines += "$level/$tag: $message ${error?.stackTraceToString()?.take(400)}"
                while (logLines.size > 40) logLines.removeAt(0)
            }),
            clock = { dispatcher.scheduler.currentTime },
            ioDispatcher = dispatcher,
        )
    }

    private suspend fun seedRuleSource() {
        db.providerDao().upsert(
            ProviderEntity(
                id = 1,
                name = "provider-1",
                api = "api-test",
                primaryScheme = "https",
                primaryHost = "host-1.example",
                primaryPort = 443,
                primaryPath = "/",
                username = "user-1",
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
        db.songDao().upsert(
            SongEntity(
                id = 1,
                providerId = 1,
                externalId = "song-1",
                title = "Favorite",
                isFavorite = true,
                updatedAt = 1_000,
            ),
        )
    }

    private suspend fun seed(
        mediaKey: String,
        status: DownloadStatus = DownloadStatus.PENDING,
        title: String = "Track",
        errorCount: Int = 0,
        progressPercent: Int = 0,
        updatedAt: Long = 1_000,
        exportTarget: String? = null,
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
            exportTarget = exportTarget,
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

private class RecordingExportStore : ExportDocumentStore {

    class Document(var pending: Boolean, var bytes: ByteArray)

    val documents = LinkedHashMap<String, Document>()
    private var nextId = 1L

    override suspend fun insertPending(request: ExportDocumentRequest): ExportDocumentRef {
        val uri = "content://media/external/downloads/${nextId++}"
        documents[uri] = Document(pending = true, bytes = ByteArray(0))
        return ExportDocumentRef(uri)
    }

    override suspend fun writeTruncating(ref: ExportDocumentRef, source: File): ExportCopyOutcome {
        val document = documents[ref.uri] ?: return ExportCopyOutcome.WriteFailed
        document.bytes = source.readBytes()
        return ExportCopyOutcome.Copied(document.bytes.size.toLong())
    }

    override suspend fun clearPending(ref: ExportDocumentRef): Boolean {
        val document = documents[ref.uri] ?: return false
        document.pending = false
        return true
    }

    override suspend fun delete(ref: ExportDocumentRef): Boolean = documents.remove(ref.uri) != null

    override suspend fun presence(ref: ExportDocumentRef): ExportDocumentPresence {
        val document = documents[ref.uri] ?: return ExportDocumentPresence.MISSING
        return if (document.pending) ExportDocumentPresence.PENDING else ExportDocumentPresence.VISIBLE
    }

    override suspend fun pendingMatches(mediaKey: String, title: String?): List<PendingExportDocument> = emptyList()
}
