package app.omnisonic.core.data.downloads

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.silentTestLogger
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadTransferEngineTest : DatabaseTestBase() {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private val client = OkHttpClient.Builder().build()
    private val floor = OfflinePathRules.MIN_FREE_SPACE_BYTES
    private var now = 100_000L
    private var rootIndex = 0

    private class ToggleFailureDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        var failReads = false

        override val data: Flow<Preferences>
            get() = flow {
                if (failReads) throw IOException("injected read failure")
                emit(state.value)
            }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private class RecordingAuth : DownloadAuthReporting {
        val rejected = mutableListOf<Long>()
        val succeeded = mutableListOf<Long>()

        override suspend fun authRejected(providerId: Long) {
            rejected += providerId
        }

        override suspend fun authSucceeded(providerId: Long) {
            succeeded += providerId
        }
    }

    private class Rig(
        val repository: DownloadQueueRepository,
        val coordinator: DownloadWorkerCoordinator,
        val storage: OfflineStorage,
        val auth: RecordingAuth,
        val root: File,
    ) {
        fun engine(media: DownloadMediaSource, clock: () -> Long): DownloadTransferEngine =
            DownloadTransferEngine(
                repository = repository,
                coordinator = coordinator,
                storage = storage,
                media = media,
                auth = auth,
                logger = silentTestLogger,
                clock = clock,
            )

        suspend fun claim(): DownloadLease =
            coordinator.claimNext(ProfileScope.Unrestricted) ?: error("no claimable row")

        suspend fun plan(
            providerPath: String = DEFAULT_PROVIDER_PATH,
            bitrate: Int? = null,
            format: String? = null,
        ): OfflinePathPlan = when (
            val planned = storage.planPath(OfflineMediaIdentity(1, providerPath, bitrate, format))
        ) {
            is OfflinePathResult.Planned -> planned.plan
            else -> error("identity did not plan")
        }

        fun file(relativePath: String): File = File(root, relativePath)
    }

    private fun rig(
        freeBytes: () -> Long = { floor * 10 },
        workerCount: Int = 2,
        settings: DownloadQueueSettingsStore = DownloadQueueSettingsStore.inMemory(),
    ): Rig {
        val root = File(temp.root, "root-${rootIndex++}").apply { mkdirs() }
        val storage = OfflineStorage(
            roots = OfflineRootSource { listOf(OfflineRoot("test-root", root)) },
            settings = settings,
            usableSpace = { freeBytes() },
            writableProbe = { true },
        )
        val repository = DownloadQueueRepository(downloadDao)
        val coordinator = DownloadWorkerCoordinator(
            repository = repository,
            settings = settings,
            runGate = ProviderRunGate { ProviderStatus.OK },
            workerCount = workerCount,
            nowMs = { now },
        )
        return Rig(repository, coordinator, storage, RecordingAuth(), root)
    }

    private suspend fun seedRow(bitrate: Int? = null, format: String? = null): DownloadItemEntity =
        seedDownloadItem(
            providerId = 1,
            mediaKey = DownloadKeys.mediaKey(1, "song-1", bitrate, format),
        )

    private fun media(
        vararg urls: String,
        path: String = DEFAULT_PROVIDER_PATH,
    ): DownloadMediaSource = DownloadMediaSource { request ->
        DownloadMediaResolution.Available(
            providerPath = path,
            endpoints = urls.map { url -> DownloadMediaEndpoint(url, client) },
        )
    }

    private fun url(server: MockWebServer): String = server.url("/download?token=secret-1").toString()

    private suspend fun useServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private fun body(size: Int = 2_048, salt: Int = 1): ByteArray =
        ByteArray(size) { index -> ((index * 31 + salt * 17) % 251).toByte() }

    private fun seedPart(
        rig: Rig,
        plan: OfflinePathPlan,
        bytes: ByteArray,
        totalBytes: Long = bytes.size.toLong(),
        etag: String? = "\"v1\"",
    ): File {
        val part = rig.file(plan.relativePartPath)
        part.parentFile?.mkdirs()
        part.writeBytes(bytes)
        DownloadTransferJournal().write(
            part,
            DownloadTransferState(
                identityHash = plan.hash,
                strongEtag = etag,
                totalBytes = totalBytes,
            ),
        )
        return part
    }

    private suspend fun transfer(
        rig: Rig,
        lease: DownloadLease,
        media: DownloadMediaSource,
        clock: () -> Long = { now },
    ): DownloadTransferResult = rig.engine(media, clock).transfer(lease)

    private suspend fun row(id: Long): DownloadItemEntity? = downloadDao.selectById(id)

    @Test
    fun `a fresh download streams identity bytes into the part and finalizes`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body()
            server.enqueue(MockResponse().setBody(Buffer().write(payload)).setHeader("ETag", "\"v1\""))
            val seeded = seedRow()
            val lease = rig.claim()
            val plan = rig.plan()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isEqualTo(DownloadTransferResult.Completed(plan.relativePath, payload.size.toLong()))
            assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(payload)
            assertThat(rig.file(plan.relativePartPath).exists()).isFalse()
            assertThat(DownloadTransferJournal().stateFile(rig.file(plan.relativePartPath)).exists()).isFalse()
            assertThat(row(seeded.id)).isNull()
            assertThat(rig.auth.succeeded).contains(1L)

            val request = server.takeRequest()
            assertThat(request.getHeader("Accept-Encoding")).isEqualTo("identity")
            assertThat(request.getHeader("Range")).isNull()
            assertThat(request.getHeader("If-Range")).isNull()
        }
    }

    @Test
    fun `a valid 206 appends to the sidecar-validated part`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(1_000)
            val plan = rig.plan()
            seedPart(rig, plan, payload.copyOfRange(0, 400), totalBytes = payload.size.toLong())
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 400-999/1000")
                    .setHeader("ETag", "\"v1\"")
                    .setBody(Buffer().write(payload, 400, 600)),
            )
            seedRow()
            val lease = rig.claim()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isInstanceOf(DownloadTransferResult.Completed::class.java)
            assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(payload)
            val request = server.takeRequest()
            assertThat(request.getHeader("Range")).isEqualTo("bytes=400-")
            assertThat(request.getHeader("If-Range")).isEqualTo("\"v1\"")
        }
    }

    @Test
    fun `a changed entity tag on 206 restarts instead of appending`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val oldPayload = body(1_000, salt = 1)
            val newPayload = body(1_000, salt = 9)
            val plan = rig.plan()
            seedPart(rig, plan, oldPayload.copyOfRange(0, 400), totalBytes = oldPayload.size.toLong())
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 400-999/1000")
                    .setHeader("ETag", "\"v2\"")
                    .setBody(Buffer().write(newPayload, 400, 600)),
            )
            server.enqueue(MockResponse().setBody(Buffer().write(newPayload)).setHeader("ETag", "\"v2\""))
            seedRow()
            val lease = rig.claim()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isInstanceOf(DownloadTransferResult.Completed::class.java)
            assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(newPayload)
            assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=400-")
            assertThat(server.takeRequest().getHeader("Range")).isNull()
        }
    }

    @Test
    fun `an empty part with a stale sidecar starts a full request`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(512)
            val plan = rig.plan()
            seedPart(rig, plan, ByteArray(0), totalBytes = 999)
            server.enqueue(MockResponse().setBody(Buffer().write(payload)).setHeader("ETag", "\"v2\""))
            seedRow()
            val lease = rig.claim()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isInstanceOf(DownloadTransferResult.Completed::class.java)
            assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(payload)
            assertThat(server.takeRequest().getHeader("Range")).isNull()
        }
    }

    @Test
    fun `a part that already equals its validated total finalizes without a request`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(256)
            val plan = rig.plan()
            seedPart(rig, plan, payload, totalBytes = payload.size.toLong())
            seedRow()
            val lease = rig.claim()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isInstanceOf(DownloadTransferResult.Completed::class.java)
            assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(payload)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun `a server that ignores Range never gets its full body appended`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(800)
            val stale = ByteArray(400) { 0x7F }
            val plan = rig.plan()
            seedPart(rig, plan, stale, totalBytes = payload.size.toLong())
            server.enqueue(MockResponse().setBody(Buffer().write(payload)).setHeader("ETag", "\"v2\""))
            seedRow()
            val lease = rig.claim()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isInstanceOf(DownloadTransferResult.Completed::class.java)
            assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(payload)
            val request = server.takeRequest()
            assertThat(request.getHeader("Range")).isEqualTo("bytes=400-")
        }
    }

    @Test
    fun `a 206 without Content-Range restarts from zero once`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(600)
            val plan = rig.plan()
            seedPart(rig, plan, payload.copyOfRange(0, 200), totalBytes = payload.size.toLong())
            server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(payload, 200, 400)))
            server.enqueue(MockResponse().setBody(Buffer().write(payload)).setHeader("ETag", "\"v1\""))
            seedRow()
            val lease = rig.claim()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isInstanceOf(DownloadTransferResult.Completed::class.java)
            assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(payload)
            assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=200-")
            assertThat(server.takeRequest().getHeader("Range")).isNull()
        }
    }

    @Test
    fun `a malformed Content-Range restarts from zero`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(600)
            val plan = rig.plan()
            seedPart(rig, plan, payload.copyOfRange(0, 200), totalBytes = payload.size.toLong())
            server.enqueue(
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-1/oops")
                    .setBody(Buffer().write(payload, 0, 2)),
            )
            server.enqueue(MockResponse().setBody(Buffer().write(payload)).setHeader("ETag", "\"v1\""))
            seedRow()
            val lease = rig.claim()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isInstanceOf(DownloadTransferResult.Completed::class.java)
            assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(payload)
            assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=200-")
            assertThat(server.takeRequest().getHeader("Range")).isNull()
        }
    }

    @Test
    fun `a Content-Range for another offset restarts from zero`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(600)
            val plan = rig.plan()
            seedPart(rig, plan, payload.copyOfRange(0, 200), totalBytes = payload.size.toLong())
            server.enqueue(
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 500-599/600")
                    .setBody(Buffer().write(payload, 500, 100)),
            )
            server.enqueue(MockResponse().setBody(Buffer().write(payload)).setHeader("ETag", "\"v1\""))
            seedRow()
            val lease = rig.claim()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isInstanceOf(DownloadTransferResult.Completed::class.java)
            assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(payload)
            assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=200-")
            assertThat(server.takeRequest().getHeader("Range")).isNull()
        }
    }

    @Test
    fun `a 416 restarts from zero once and a second one counts a failure`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(400)
            val plan = rig.plan()
            seedPart(rig, plan, payload.copyOfRange(0, 100), totalBytes = payload.size.toLong())
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setBody(Buffer().write(payload)).setHeader("ETag", "\"v1\""))
            seedRow()
            val first = transfer(rig, rig.claim(), media(url(server)))
            assertThat(first).isInstanceOf(DownloadTransferResult.Completed::class.java)
            assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=100-")
            assertThat(server.takeRequest().getHeader("Range")).isNull()

            val secondRow = seedRow(bitrate = 128)
            val secondPlan = rig.plan(bitrate = 128)
            seedPart(rig, secondPlan, payload.copyOfRange(0, 100), totalBytes = payload.size.toLong())
            now += 60_000
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setResponseCode(416))
            val second = transfer(rig, rig.claim(), media(url(server)))

            assertThat(second).isEqualTo(
                DownloadTransferResult.Retry(
                    DownloadTransferFailure.RANGE_UNSATISFIABLE,
                    DownloadStatus.PENDING,
                ),
            )
            assertThat(row(secondRow.id)!!.errorCount).isEqualTo(1)
            assertThat(rig.file(secondPlan.relativePath).exists()).isFalse()
            assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=100-")
            assertThat(server.takeRequest().getHeader("Range")).isNull()
        }
    }

    @Test
    fun `an unknown length streams to EOF and finalizes`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val text = "streamed-without-a-length-".repeat(20)
            server.enqueue(MockResponse().setChunkedBody(text, 8))
            seedRow()
            val lease = rig.claim()
            val plan = rig.plan()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isInstanceOf(DownloadTransferResult.Completed::class.java)
            assertThat(rig.file(plan.relativePath).readText()).isEqualTo(text)
        }
    }

    @Test
    fun `a 206 shorter than its Content-Range counts a short body and keeps the part`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(600)
            val plan = rig.plan()
            seedPart(rig, plan, payload.copyOfRange(0, 100), totalBytes = payload.size.toLong())
            server.enqueue(
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 100-599/600")
                    .setChunkedBody("x".repeat(50), 10),
            )
            seedRow()
            val lease = rig.claim()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isEqualTo(
                DownloadTransferResult.Retry(DownloadTransferFailure.SHORT_BODY, DownloadStatus.PENDING),
            )
            assertThat(rig.file(plan.relativePartPath).length()).isEqualTo(150L)
            assertThat(rig.file(plan.relativePath).exists()).isFalse()
        }
    }

    @Test
    fun `a 206 with an unknown entity total never finalizes a possible prefix`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(600)
            val plan = rig.plan()
            seedPart(rig, plan, payload.copyOfRange(0, 100), totalBytes = 600)
            val part = rig.file(plan.relativePartPath)
            DownloadTransferJournal().write(
                part,
                DownloadTransferState(plan.hash, strongEtag = "\"v1\"", totalBytes = null),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 100-199/*")
                    .setHeader("ETag", "\"v1\"")
                    .setBody(Buffer().write(payload, 100, 100)),
            )
            val seeded = seedRow()

            val result = transfer(rig, rig.claim(), media(url(server)))

            assertThat(result).isEqualTo(
                DownloadTransferResult.Retry(DownloadTransferFailure.SHORT_BODY, DownloadStatus.PENDING),
            )
            assertThat(row(seeded.id)!!.errorCount).isEqualTo(1)
            assertThat(rig.file(plan.relativePath).exists()).isFalse()
        }
    }

    @Test
    fun `a 206 body longer than its declared range is discarded`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val plan = rig.plan()
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-9/*")
                    .setChunkedBody(Buffer().write(body(20)), 5),
            )
            val seeded = seedRow()

            val result = transfer(rig, rig.claim(), media(url(server)))

            assertThat(result).isEqualTo(
                DownloadTransferResult.Retry(DownloadTransferFailure.RECORD_MISMATCH, DownloadStatus.PENDING),
            )
            assertThat(row(seeded.id)!!.errorCount).isEqualTo(1)
            assertThat(rig.file(plan.relativePartPath).exists()).isFalse()
            assertThat(rig.file(plan.relativePath).exists()).isFalse()
        }
    }

    @Test
    fun `a non-identity response encoding is rejected and the part discarded`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            server.enqueue(
                MockResponse()
                    .setBody(Buffer().write(body(64)))
                    .setHeader("Content-Encoding", "gzip")
                    .setHeader("Content-Type", "audio/mpeg"),
            )
            val seeded = seedRow()
            val lease = rig.claim()
            val plan = rig.plan()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isEqualTo(
                DownloadTransferResult.Retry(DownloadTransferFailure.CONTENT_ENCODING, DownloadStatus.PENDING),
            )
            assertThat(row(seeded.id)!!.errorCount).isEqualTo(1)
            assertThat(rig.file(plan.relativePartPath).exists()).isFalse()
            assertThat(rig.file(plan.relativePath).exists()).isFalse()
        }
    }

    @Test
    fun `JSON and HTML bodies are rejected even with HTTP 200`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            server.enqueue(
                MockResponse().setBody("{\"subsonic-response\":{\"status\":\"failed\"}}")
                    .setHeader("Content-Type", "application/json"),
            )
            server.enqueue(MockResponse().setBody("<html><body>proxy login</body></html>"))
            val jsonRow = seedRow()
            val jsonResult = transfer(rig, rig.claim(), media(url(server)))
            assertThat(jsonResult).isEqualTo(
                DownloadTransferResult.Retry(DownloadTransferFailure.PROTOCOL_PAYLOAD, DownloadStatus.PENDING),
            )
            assertThat(row(jsonRow.id)!!.errorCount).isEqualTo(1)

            val htmlRow = seedRow(bitrate = 128)
            now += 60_000
            val htmlResult = transfer(rig, rig.claim(), media(url(server)))
            assertThat(htmlResult).isEqualTo(
                DownloadTransferResult.Retry(DownloadTransferFailure.PROTOCOL_PAYLOAD, DownloadStatus.PENDING),
            )
            assertThat(row(htmlRow.id)!!.errorCount).isEqualTo(1)
        }
    }

    @Test
    fun `fragmented protocol text is rejected without clearing auth`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            server.enqueue(
                MockResponse()
                    .setBody(" \n\t{\"status\":\"failed\"}")
                    .setHeader("Content-Type", "application/octet-stream")
                    .throttleBody(1, 5, TimeUnit.MILLISECONDS),
            )
            val seeded = seedRow()
            val plan = rig.plan()

            val result = transfer(rig, rig.claim(), media(url(server)))

            assertThat(result).isEqualTo(
                DownloadTransferResult.Retry(DownloadTransferFailure.PROTOCOL_PAYLOAD, DownloadStatus.PENDING),
            )
            assertThat(row(seeded.id)!!.errorCount).isEqualTo(1)
            assertThat(rig.auth.succeeded).isEmpty()
            assertThat(rig.file(plan.relativePartPath).exists()).isFalse()
            assertThat(rig.file(plan.relativePath).exists()).isFalse()
        }
    }

    @Test
    fun `a server status failure counts and never leaks the signed URL`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            server.enqueue(MockResponse().setResponseCode(503).setBody("maintenance"))
            val seeded = seedRow()
            val lease = rig.claim()

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isEqualTo(
                DownloadTransferResult.Retry(DownloadTransferFailure.HTTP_STATUS, DownloadStatus.PENDING),
            )
            val stored = row(seeded.id)!!
            assertThat(stored.errorCount).isEqualTo(1)
            assertThat(stored.updatedAt).isEqualTo(now)
            assertThat(stored.lastError).doesNotContain("secret-1")
            assertThat(stored.lastError).doesNotContain("://")
            assertThat(stored.lastError).contains("server status")
        }
    }

    @Test
    fun `network loss requeues without a counted failure`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            server.enqueue(
                MockResponse()
                    .setBody("unavailable")
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START),
            )
            val seeded = seedRow()

            val result = transfer(rig, rig.claim(), media(url(server)))

            assertThat(result).isEqualTo(DownloadTransferResult.Blocked(DownloadTransferBlock.NETWORK_UNAVAILABLE))
            val stored = row(seeded.id)!!
            assertThat(stored.status).isEqualTo(DownloadStatus.PENDING.name)
            assertThat(stored.errorCount).isEqualTo(0)
            assertThat(stored.updatedAt).isEqualTo(1_000L)
            assertThat(stored.lastError).isNull()
        }
    }

    @Test
    fun `the sixth counted failure parks the row`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val seeded = seedRow()
            repeat(6) {
                now += 120_000
                server.enqueue(MockResponse().setResponseCode(500))
                val result = transfer(rig, rig.claim(), media(url(server)))
                assertThat(result).isInstanceOf(DownloadTransferResult.Retry::class.java)
            }

            val parked = row(seeded.id)!!
            assertThat(parked.status).isEqualTo(DownloadStatus.FAILED.name)
            assertThat(parked.errorCount).isEqualTo(6)
            assertThat(rig.coordinator.claimNext(ProfileScope.Unrestricted)).isNull()
        }
    }

    @Test
    fun `an auth rejection is uncounted and reports the tracker`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            server.enqueue(MockResponse().setResponseCode(401))
            val seeded = seedRow()

            val result = transfer(rig, rig.claim(), media(url(server)))

            assertThat(result).isEqualTo(
                DownloadTransferResult.Blocked(DownloadTransferBlock.AUTH_REJECTED),
            )
            assertThat(rig.auth.rejected).containsExactly(1L)
            val stored = row(seeded.id)!!
            assertThat(stored.status).isEqualTo(DownloadStatus.PENDING.name)
            assertThat(stored.errorCount).isEqualTo(0)
            assertThat(stored.lastError).isNull()
        }
    }

    @Test
    fun `unavailable provider credentials block without counting`(): Unit = runDbTest {
        val rig = rig()
        val seeded = seedRow()

        val result = transfer(rig, rig.claim(), DownloadMediaSource { DownloadMediaResolution.CredentialsUnavailable })

        assertThat(result).isEqualTo(DownloadTransferResult.Blocked(DownloadTransferBlock.AUTH_REJECTED))
        assertThat(rig.auth.rejected).containsExactly(1L)
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(row(seeded.id)!!.errorCount).isEqualTo(0)
    }

    @Test
    fun `an authenticated raw response clears a stale auth marker`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            server.enqueue(MockResponse().setBody(Buffer().write(body(32))))
            seedRow()

            transfer(rig, rig.claim(), media(url(server)))

            assertThat(rig.auth.succeeded).containsExactly(1L)
            assertThat(rig.auth.rejected).isEmpty()
        }
    }

    @Test
    fun `a dead primary fails over to the secondary endpoint`(): Unit = runDbTest {
        useServer { primary ->
            useServer { secondary ->
                val rig = rig()
                val payload = body(700)
                primary.enqueue(
                    MockResponse()
                        .setBody("unavailable")
                        .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START),
                )
                secondary.enqueue(MockResponse().setBody(Buffer().write(payload)))
                seedRow()
                val plan = rig.plan()

                val result = transfer(rig, rig.claim(), media(url(primary), url(secondary)))

                assertThat(result).isInstanceOf(DownloadTransferResult.Completed::class.java)
                assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(payload)
                assertThat(primary.requestCount).isEqualTo(1)
                assertThat(secondary.requestCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun `a failover resumes the partial bytes with the same representation`(): Unit = runDbTest {
        useServer { primary ->
            useServer { secondary ->
                val rig = rig()
                val payload = body(4_096)
                primary.enqueue(
                    MockResponse()
                        .setBody(Buffer().write(payload))
                        .setHeader("ETag", "\"v1\"")
                        .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
                )
                var secondaryRange: String? = null
                var secondaryValidator: String? = null
                secondary.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        secondaryRange = request.getHeader("Range")
                        secondaryValidator = request.getHeader("If-Range")
                        val range = secondaryRange
                        val start = range?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toInt() }
                            ?: return MockResponse().setResponseCode(500)
                        return MockResponse()
                            .setResponseCode(206)
                            .setHeader("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                            .setHeader("ETag", "\"v1\"")
                            .setBody(Buffer().write(payload, start, payload.size - start))
                    }
                }
                seedRow()
                val plan = rig.plan()

                val result = transfer(rig, rig.claim(), media(url(primary), url(secondary)))

                assertThat(result).isInstanceOf(DownloadTransferResult.Completed::class.java)
                assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(payload)
                assertThat(secondaryRange).isNotNull()
                assertThat(secondaryRange).isNotEqualTo("bytes=0-")
                assertThat(secondaryValidator).isEqualTo("\"v1\"")
            }
        }
    }

    @Test
    fun `a changed representation after failover restarts instead of appending`(): Unit = runDbTest {
        useServer { primary ->
            useServer { secondary ->
                val rig = rig()
                val first = body(4_096, salt = 1)
                val second = body(3_000, salt = 9)
                primary.enqueue(
                    MockResponse()
                        .setBody(Buffer().write(first))
                        .setHeader("ETag", "\"v1\"")
                        .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
                )
                secondary.enqueue(
                    MockResponse().setBody(Buffer().write(second)).setHeader("ETag", "\"v2\""),
                )
                seedRow()
                val plan = rig.plan()

                val result = transfer(rig, rig.claim(), media(url(primary), url(secondary)))

                assertThat(result).isInstanceOf(DownloadTransferResult.Completed::class.java)
                assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(second)
            }
        }
    }

    @Test
    fun `pause during the body preserves the part without counting`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(256 * 1024)
            server.enqueue(
                MockResponse().setBody(Buffer().write(payload)).throttleBody(4_096, 20, TimeUnit.MILLISECONDS),
            )
            val seeded = seedRow()
            val lease = rig.claim()
            val plan = rig.plan()
            val result = withContext(Dispatchers.Default) {
                val work = async(Dispatchers.Default) { transfer(rig, lease, media(url(server))) }
                withTimeout(2_000) {
                    while (rig.file(plan.relativePartPath).length() == 0L) delay(25)
                }
                rig.coordinator.pauseAll()
                withTimeout(2_000) { work.await() }
            }

            assertThat(result).isInstanceOf(DownloadTransferResult.Paused::class.java)
            val stored = row(seeded.id)!!
            assertThat(stored.status).isEqualTo(DownloadStatus.PAUSED.name)
            assertThat(stored.errorCount).isEqualTo(0)
            assertThat(stored.progressPercent).isIn(-1..100)
            assertThat(rig.file(plan.relativePartPath).exists()).isTrue()
            assertThat(rig.file(plan.relativePartPath).length()).isGreaterThan(0L)
            assertThat(rig.file(plan.relativePartPath).length()).isLessThan(payload.size.toLong())
            assertThat(rig.file(plan.relativePath).exists()).isFalse()
        }
    }

    @Test
    fun `pause while waiting for headers cancels the call`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            server.enqueue(
                MockResponse()
                    .setBody("late")
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE),
            )
            val seeded = seedRow()
            val lease = rig.claim()
            val plan = rig.plan()
            val result = withContext(Dispatchers.Default) {
                val work = async(Dispatchers.Default) { transfer(rig, lease, media(url(server))) }
                withContext(Dispatchers.IO) { server.takeRequest() }
                rig.coordinator.pauseAll()
                withTimeout(2_000) { work.await() }
            }

            assertThat(result).isInstanceOf(DownloadTransferResult.Paused::class.java)
            assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)
            assertThat(rig.file(plan.relativePartPath).exists()).isFalse()
        }
    }

    @Test
    fun `cancel during the body deletes the part and the row`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(256 * 1024)
            server.enqueue(
                MockResponse().setBody(Buffer().write(payload)).throttleBody(4_096, 20, TimeUnit.MILLISECONDS),
            )
            val seeded = seedRow()
            val lease = rig.claim()
            val plan = rig.plan()
            val result = withContext(Dispatchers.Default) {
                val work = async(Dispatchers.Default) { transfer(rig, lease, media(url(server))) }
                withTimeout(2_000) {
                    while (rig.file(plan.relativePartPath).length() == 0L) delay(25)
                }
                assertThat(rig.file(plan.relativePartPath).length()).isGreaterThan(0L)
                assertThat(rig.coordinator.cancel(seeded.id)).isTrue()
                withTimeout(2_000) { work.await() }
            }

            assertThat(result).isEqualTo(DownloadTransferResult.Cancelled)
            assertThat(row(seeded.id)).isNull()
            assertThat(rig.file(plan.relativePartPath).exists()).isFalse()
            assertThat(rig.file(plan.relativePath).exists()).isFalse()
        }
    }

    @Test
    fun `coroutine cancellation releases the lease without counting`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            server.enqueue(
                MockResponse()
                    .setBody("never")
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE),
            )
            val seeded = seedRow()
            val lease = rig.claim()
            coroutineScope {
                val work = async(Dispatchers.Default) { transfer(rig, lease, media(url(server))) }
                withContext(Dispatchers.IO) { server.takeRequest() }
                work.cancelAndJoin()
            }

            assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
            assertThat(row(seeded.id)!!.errorCount).isEqualTo(0)
            assertThat(rig.coordinator.activeLeaseCount()).isEqualTo(0)
        }
    }

    @Test
    fun `a root journal read failure blocks and releases to the safe paused state`(): Unit = runDbTest {
        val store = ToggleFailureDataStore()
        val settings = DownloadQueueSettingsStore(store)
        val rig = rig(settings = settings)
        val seeded = seedRow()
        val lease = rig.claim()
        store.failReads = true
        var resolved = false
        val source = DownloadMediaSource {
            resolved = true
            error("media must not resolve")
        }

        val result = transfer(rig, lease, source)

        assertThat(result).isEqualTo(DownloadTransferResult.Blocked(DownloadTransferBlock.STORAGE_UNAVAILABLE))
        assertThat(resolved).isFalse()
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)
        assertThat(row(seeded.id)!!.errorCount).isEqualTo(0)
        assertThat(rig.coordinator.activeLeaseCount()).isEqualTo(0)
    }

    @Test
    fun `low space blocks before any network work`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig(freeBytes = { floor - 1 })
            val seeded = seedRow()
            var resolved = false
            val source = DownloadMediaSource {
                resolved = true
                DownloadMediaResolution.Available(DEFAULT_PROVIDER_PATH, listOf(DownloadMediaEndpoint(url(server), client)))
            }

            val result = transfer(rig, rig.claim(), source)

            assertThat(result).isEqualTo(DownloadTransferResult.Blocked(DownloadTransferBlock.STORAGE_LOW_SPACE))
            assertThat(resolved).isFalse()
            assertThat(server.requestCount).isEqualTo(0)
            assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
            assertThat(row(seeded.id)!!.errorCount).isEqualTo(0)
        }
    }

    @Test
    fun `a fresh content length is preflighted before its body is written`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig(freeBytes = { floor + 10 })
            val seeded = seedRow()
            val plan = rig.plan()
            server.enqueue(MockResponse().setBody(Buffer().write(body(100))))

            val result = transfer(rig, rig.claim(), media(url(server)))

            assertThat(result).isEqualTo(DownloadTransferResult.Blocked(DownloadTransferBlock.STORAGE_LOW_SPACE))
            assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
            assertThat(row(seeded.id)!!.errorCount).isEqualTo(0)
            assertThat(rig.file(plan.relativePartPath).exists()).isFalse()
            assertThat(rig.file(plan.relativePath).exists()).isFalse()
        }
    }

    @Test
    fun `a missing or unsupported provider parks without a retry loop`(): Unit = runDbTest {
        val rig = rig()
        val missingRow = seedRow()

        val missing = transfer(
            rig,
            rig.claim(),
            DownloadMediaSource { DownloadMediaResolution.ProviderMissing },
        )

        assertThat(missing).isEqualTo(DownloadTransferResult.Parked(DownloadTransferPark.PROVIDER_MISSING))
        assertThat(row(missingRow.id)!!.status).isEqualTo(DownloadStatus.FAILED.name)
        assertThat(row(missingRow.id)!!.errorCount).isEqualTo(0)
        assertThat(row(missingRow.id)!!.lastError).contains("provider missing")
        assertThat(rig.coordinator.claimNext(ProfileScope.Unrestricted)).isNull()

        val unsupportedRow = seedRow(bitrate = 128)
        now += 60_000
        val unsupported = transfer(
            rig,
            rig.claim(),
            DownloadMediaSource { DownloadMediaResolution.DownloadUnsupported },
        )
        assertThat(unsupported).isEqualTo(DownloadTransferResult.Parked(DownloadTransferPark.DOWNLOAD_UNSUPPORTED))
        assertThat(row(unsupportedRow.id)!!.status).isEqualTo(DownloadStatus.FAILED.name)
    }

    @Test
    fun `a permanent result that loses to pause releases its lease`(): Unit = runDbTest {
        val rig = rig()
        val seeded = seedRow()
        val lease = rig.claim()
        assertThat(rig.coordinator.pauseOne(seeded.id)).isTrue()

        val result = transfer(rig, lease, DownloadMediaSource { DownloadMediaResolution.ProviderMissing })

        assertThat(result).isEqualTo(DownloadTransferResult.LeaseLost)
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.USER_PAUSED.name)
        assertThat(row(seeded.id)!!.errorCount).isEqualTo(0)
        assertThat(rig.coordinator.activeLeaseCount()).isEqualTo(0)
    }

    @Test
    fun `an unparseable queue key parks instead of guessing an identity`(): Unit = runDbTest {
        val rig = rig()
        val seeded = seedDownloadItem(providerId = 1, mediaKey = "legacy-key")

        val result = transfer(rig, rig.claim(), DownloadMediaSource { error("must not resolve") })

        assertThat(result).isEqualTo(DownloadTransferResult.Parked(DownloadTransferPark.QUEUE_KEY_INVALID))
        assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.FAILED.name)
    }

    @Test
    fun `an existing final file is never overwritten`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val payload = body(200)
            val existing = "already-here".toByteArray()
            val plan = rig.plan()
            rig.file(plan.relativePath).apply {
                parentFile?.mkdirs()
                writeBytes(existing)
            }
            seedPart(rig, plan, payload, totalBytes = payload.size.toLong())
            val seeded = seedRow()

            val result = transfer(rig, rig.claim(), media(url(server)))

            assertThat(result).isEqualTo(DownloadTransferResult.Parked(DownloadTransferPark.FINAL_PATH_CONFLICT))
            assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(existing)
            assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.FAILED.name)
        }
    }

    @Test
    fun `a cancelled row leaves a stale lease without touching files`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val seeded = seedRow()
            val lease = rig.claim()
            rig.coordinator.cancel(seeded.id)

            val result = transfer(rig, lease, media(url(server)))

            assertThat(result).isEqualTo(DownloadTransferResult.LeaseLost)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun `quality variants plan distinct files and both survive`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val low = body(300, salt = 2)
            val high = body(500, salt = 3)
            server.enqueue(MockResponse().setBody(Buffer().write(low)))
            server.enqueue(MockResponse().setBody(Buffer().write(high)))
            seedRow(bitrate = 128)
            val lowPlan = rig.plan(bitrate = 128)
            val first = transfer(rig, rig.claim(), media(url(server)))
            assertThat(first).isInstanceOf(DownloadTransferResult.Completed::class.java)

            seedRow(bitrate = 320)
            val highPlan = rig.plan(bitrate = 320)
            val second = transfer(rig, rig.claim(), media(url(server)))
            assertThat(second).isInstanceOf(DownloadTransferResult.Completed::class.java)

            assertThat(lowPlan.relativePath).isNotEqualTo(highPlan.relativePath)
            assertThat(rig.file(lowPlan.relativePath).readBytes()).isEqualTo(low)
            assertThat(rig.file(highPlan.relativePath).readBytes()).isEqualTo(high)
        }
    }

    @Test
    fun `three slow workers run under the cap without sharing a file`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig(workerCount = 3)
            val payloads = (1..3).associate { index -> "song-$index" to body(32 * 1024, salt = index) }
            val arrivals = CountDownLatch(3)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrivals.countDown()
                    if (!arrivals.await(2, TimeUnit.SECONDS)) return MockResponse().setResponseCode(500)
                    val externalId = request.requestUrl?.queryParameter("id").orEmpty()
                    val payload = payloads.getValue(externalId)
                    return MockResponse()
                        .setBody(Buffer().write(payload))
                        .throttleBody(2_048, 5, TimeUnit.MILLISECONDS)
                }
            }
            payloads.keys.forEachIndexed { index, externalId ->
                seedDownloadItem(
                    providerId = 1,
                    mediaKey = DownloadKeys.mediaKey(1, externalId, null, null),
                    createdAt = 1_000L + index,
                )
            }
            val source = DownloadMediaSource { request ->
                DownloadMediaResolution.Available(
                    providerPath = "/music/${request.externalId}.flac",
                    endpoints = listOf(
                        DownloadMediaEndpoint(
                            server.url("/download?id=${request.externalId}&token=secret-1").toString(),
                            client,
                        ),
                    ),
                )
            }
            val leases = (1..3).map { rig.claim() }
            assertThat(rig.coordinator.claimNext(ProfileScope.Unrestricted)).isNull()

            val results = coroutineScope {
                leases.map { lease ->
                    async(Dispatchers.Default) { rig.engine(source, { now }).transfer(lease) }
                }.awaitAll()
            }

            assertThat(results).hasSize(3)
            results.forEach { assertThat(it).isInstanceOf(DownloadTransferResult.Completed::class.java) }
            val paths = results.map { (it as DownloadTransferResult.Completed).relativePath }
            assertThat(paths).containsNoDuplicates()
            payloads.forEach { (externalId, payload) ->
                val plan = rig.plan(providerPath = "/music/$externalId.flac")
                assertThat(rig.file(plan.relativePath).readBytes()).isEqualTo(payload)
            }
        }
    }

    @Test
    fun `disk exhaustion during a write blocks without counting`(): Unit = runDbTest {
        useServer { server ->
            var free = floor * 10
            val rig = rig(freeBytes = { free })
            val plan = rig.plan()
            rig.file(plan.relativePartPath).mkdirs()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    free = floor - 1
                    return MockResponse().setChunkedBody(Buffer().write(body(64)), 8)
                }
            }
            val seeded = seedRow()

            val result = transfer(rig, rig.claim(), media(url(server)))

            assertThat(result).isEqualTo(DownloadTransferResult.Blocked(DownloadTransferBlock.STORAGE_LOW_SPACE))
            assertThat(row(seeded.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
            assertThat(row(seeded.id)!!.errorCount).isEqualTo(0)
            assertThat(rig.file(plan.relativePath).exists()).isFalse()
        }
    }

    @Test
    fun `a file open failure counts without finalizing`(): Unit = runDbTest {
        useServer { server ->
            val rig = rig()
            val plan = rig.plan()
            rig.file(plan.relativePartPath).mkdirs()
            server.enqueue(MockResponse().setBody(Buffer().write(body(64))))
            val seeded = seedRow()

            val result = transfer(rig, rig.claim(), media(url(server)))

            assertThat(result).isEqualTo(
                DownloadTransferResult.Retry(DownloadTransferFailure.WRITE_FAILED, DownloadStatus.PENDING),
            )
            assertThat(row(seeded.id)!!.errorCount).isEqualTo(1)
            assertThat(rig.file(plan.relativePartPath).isDirectory).isTrue()
            assertThat(DownloadTransferJournal().stateFile(rig.file(plan.relativePartPath)).exists()).isFalse()
            assertThat(rig.file(plan.relativePath).exists()).isFalse()
        }
    }

    private companion object {
        const val DEFAULT_PROVIDER_PATH = "/music/library/track.flac"
    }
}
