package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.silentTestLogger
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import com.google.common.truth.Truth.assertThat
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadExportTransferTest : DatabaseTestBase() {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().build()
    private val store = FakeExportDocumentStore()
    private val journal = DownloadTransferJournal()
    private val floor = OfflinePathRules.MIN_FREE_SPACE_BYTES
    private var rootIndex = 0
    private var freeBytes = floor * 10
    private var now = 1_000L

    private lateinit var root: File
    private lateinit var storage: OfflineStorage
    private lateinit var sessions: ExportSessionJournal
    private lateinit var repository: DownloadQueueRepository
    private lateinit var coordinator: DownloadWorkerCoordinator
    private lateinit var offline: OfflineMediaRepository
    private lateinit var publisher: DownloadExportPublisher

    private val mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null)
    private val exportTarget = "MEDIASTORE_DOWNLOADS"
    private val providerPath = "/music/library/track.flac"
    private val payload = ByteArray(512) { (it % 251).toByte() }

    private val auth = object : DownloadAuthReporting {
        override suspend fun authRejected(providerId: Long) = Unit

        override suspend fun authSucceeded(providerId: Long) = Unit
    }

    @After
    fun tearDownServer() {
        server.shutdown()
    }

    private suspend fun setup() {
        server.start()
        newProvider(id = 1)
        root = File(temp.root, "root-${rootIndex++}").apply { mkdirs() }
        storage = OfflineStorage(
            roots = OfflineRootSource { listOf(OfflineRoot("test-root", root)) },
            settings = DownloadQueueSettingsStore.inMemory(),
            usableSpace = { freeBytes },
            writableProbe = { true },
        )
        sessions = ExportSessionJournal(File(temp.root, "sessions-${rootIndex}"))
        repository = DownloadQueueRepository(downloadDao)
        coordinator = DownloadWorkerCoordinator(
            repository = repository,
            settings = DownloadQueueSettingsStore.inMemory(),
            runGate = ProviderRunGate { ProviderStatus.OK },
            workerCount = 2,
            nowMs = { now },
        )
        offline = OfflineMediaRepository(db)
        publisher = DownloadExportPublisher(repository, offline, storage, store, sessions, silentTestLogger)
    }

    private fun plan(): OfflinePathPlan =
        (storage.planPath(OfflineMediaIdentity(1, providerPath)) as OfflinePathResult.Planned).plan

    private fun engine(
        media: DownloadMediaSource,
        exporter: DownloadExportPublisher? = publisher,
    ): DownloadTransferEngine = DownloadTransferEngine(
        repository = repository,
        coordinator = coordinator,
        publisher = offline,
        storage = storage,
        media = media,
        auth = auth,
        logger = silentTestLogger,
        clock = { 1_000 },
        exporter = exporter,
    )

    private fun mediaSource(url: String): DownloadMediaSource = DownloadMediaSource {
        DownloadMediaResolution.Available(providerPath, listOf(DownloadMediaEndpoint(url, client)))
    }

    private suspend fun seedExportRow(): DownloadItemEntity {
        downloadDao.upsert(
            DownloadItemEntity(
                mediaKey = mediaKey,
                providerId = 1,
                status = DownloadStatus.PENDING.name,
                title = "Track",
                exportTarget = exportTarget,
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
        return downloadDao.selectByMediaKey(mediaKey)!!
    }

    private suspend fun claim(): DownloadLease =
        coordinator.claimNext(ProfileScope.Unrestricted) ?: error("no claimable row")

    @Test
    fun `a complete export row publishes a visible document and never writes offline media`() = runDbTest {
        setup()
        val row = seedExportRow()
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(payload))
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Type", "audio/flac"),
        )

        val result = engine(mediaSource(server.url("/stream?token=SECRET").toString())).transfer(claim())

        val document = store.documents.values.single()
        assertThat(result).isInstanceOf(DownloadTransferResult.Exported::class.java)
        assertThat(document.bytes).isEqualTo(payload)
        assertThat(document.pending).isFalse()
        assertThat(document.request.relativePath).isEqualTo("Download/Omnisonic/")
        assertThat(document.request.displayName).doesNotContain("SECRET")
        assertThat(document.request.displayName).doesNotContain("http")
        assertThat(document.request.mimeType).isEqualTo("audio/flac")
        assertThat(downloadDao.selectById(row.id)).isNull()
        assertThat(offlineFileDao.count()).isEqualTo(0)
        assertThat(File(root, plan().relativePath).exists()).isFalse()
        assertThat(File(root, plan().relativePartPath).exists()).isFalse()
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(store.deletes).isEqualTo(0)
    }

    @Test
    fun `a resumed transfer exports the checksum of the whole file`() = runDbTest {
        setup()
        val row = seedExportRow()
        val plan = plan()
        val part = File(root, plan.relativePartPath).apply {
            parentFile?.mkdirs()
            writeBytes(payload.copyOfRange(0, 256))
        }
        journal.write(
            part,
            DownloadTransferState(
                identityHash = plan.hash,
                strongEtag = "\"v1\"",
                totalBytes = payload.size.toLong(),
                mediaKey = mediaKey,
            ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 256-511/512")
                .setHeader("ETag", "\"v1\"")
                .setHeader("Content-Type", "audio/flac")
                .setBody(Buffer().write(payload, 256, 256)),
        )

        val result = engine(mediaSource(server.url("/stream").toString())).transfer(claim())

        val request = server.takeRequest()
        assertThat(request.getHeader("Range")).isEqualTo("bytes=256-")
        assertThat(result).isInstanceOf(DownloadTransferResult.Exported::class.java)
        assertThat(store.documents.values.single().bytes).isEqualTo(payload)
        assertThat(store.documents.values.single().pending).isFalse()
        assertThat(downloadDao.selectById(row.id)).isNull()
        assertThat(part.exists()).isFalse()
    }

    @Test
    fun `an existing published offline copy is exported without a network request or file change`() = runDbTest {
        setup()
        val row = seedExportRow()
        val plan = plan()
        val permanent = File(root, plan.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(payload)
        }
        offlineFileDao.upsert(
            OfflineFileEntity(
                providerId = 1,
                mediaType = "SONG",
                sourceKey = mediaKey,
                offlinePath = plan.relativePath,
                sizeBytes = payload.size.toLong(),
                tempCache = false,
            ),
        )
        val media = DownloadMediaSource { error("a published copy must not be re-downloaded") }

        val result = engine(media).transfer(claim())

        assertThat(result).isInstanceOf(DownloadTransferResult.Exported::class.java)
        assertThat(store.documents.values.single().bytes).isEqualTo(payload)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(permanent.readBytes()).isEqualTo(payload)
        assertThat(offlineFileDao.selectBySourceKey(mediaKey)).isNotNull()
        assertThat(downloadDao.selectById(row.id)).isNull()
    }

    @Test
    fun `a staging disk below the floor blocks the export before any document exists`() = runDbTest {
        setup()
        seedExportRow()
        freeBytes = floor - 1L

        val result = engine(mediaSource(server.url("/stream").toString())).transfer(claim())

        assertThat(result).isEqualTo(
            DownloadTransferResult.Blocked(DownloadTransferBlock.STORAGE_LOW_SPACE),
        )
        assertThat(store.inserts).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(sessions.read(mediaKey)).isNull()
    }

    @Test
    fun `an export row without a publisher parks fail closed`() = runDbTest {
        setup()
        seedExportRow()

        val result = engine(mediaSource(server.url("/stream").toString()), exporter = null).transfer(claim())

        assertThat(result).isEqualTo(
            DownloadTransferResult.Parked(DownloadTransferPark.EXPORT_UNAVAILABLE),
        )
        assertThat(store.inserts).isEqualTo(0)
        assertThat(offlineFileDao.count()).isEqualTo(0)
        assertThat(File(root, plan().relativePath).exists()).isFalse()
    }

    @Test
    fun `an export copy failure retries and the next attempt publishes exactly one document`() = runDbTest {
        setup()
        val row = seedExportRow()
        store.failWrites = true
        server.enqueue(MockResponse().setBody(Buffer().write(payload)).setHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setBody(Buffer().write(payload)).setHeader("ETag", "\"v1\""))

        val failed = engine(mediaSource(server.url("/stream").toString())).transfer(claim())

        assertThat(failed).isEqualTo(
            DownloadTransferResult.Retry(DownloadTransferFailure.EXPORT_FAILED, DownloadStatus.PENDING),
        )
        assertThat(store.documents).isEmpty()
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(downloadDao.selectById(row.id)!!.errorCount).isEqualTo(1)

        store.failWrites = false
        now = 20_000L
        val published = engine(mediaSource(server.url("/stream").toString())).transfer(claim())

        assertThat(published).isInstanceOf(DownloadTransferResult.Exported::class.java)
        assertThat(store.documents).hasSize(1)
        assertThat(store.documents.values.single().bytes).isEqualTo(payload)
        assertThat(downloadDao.selectById(row.id)).isNull()
        assertThat(offlineFileDao.count()).isEqualTo(0)
    }
}
