package app.omnisonic.core.data.sync

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.core.data.db.queue.RoomOutboxStore
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.model.log.ConsoleLogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.core.network.HttpClientFactory
import app.omnisonic.provider.api.AllowAllRunGate
import app.omnisonic.provider.api.ConnectionBlock
import app.omnisonic.provider.api.OutboxActionPayload
import app.omnisonic.provider.api.OutboxActions
import app.omnisonic.provider.api.OutboxDrainer
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderEndpoint
import app.omnisonic.provider.api.ProviderMediaType
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import app.omnisonic.provider.navidrome.SubsonicClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxSyncTest : DatabaseTestBase() {

    @Test
    fun `merged play delta and a favorite drain in action order`() = withSync() { server, sync, queue ->
        queue.enqueuePlayCountDelta(1, ProviderMediaType.SONG, "song-1", 1_700_000_000_000, now = 1)
        queue.enqueuePlayCountDelta(1, ProviderMediaType.SONG, "song-1", 1_700_000_000_500, now = 2)
        queue.enqueueMediaChange(
            providerId = 1,
            mediaType = ProviderMediaType.SONG,
            externalId = "song-1",
            action = OutboxActions.FAVORITE,
            payload = OutboxActionPayload(isFavorite = true),
            now = 3,
        )

        val report = sync.drainNow()

        assertThat(report).isNotNull()
        assertThat(report!!.completed).isEqualTo(2)
        assertThat(report.deferred).isEqualTo(0)
        server.takeRequest()
        server.takeRequest()
        val scrobbles = List(2) { server.takeRequest().requestUrl!! }
        assertThat(scrobbles).hasSize(2)
        scrobbles.forEach { request ->
            assertThat(request.encodedPath).isEqualTo("/rest/scrobble.view")
            assertThat(request.queryParameter("id")).isEqualTo("song-1")
        }
        assertThat(scrobbles[0].queryParameter("time")).isEqualTo("1700000000000")
        assertThat(scrobbles[1].queryParameter("time")).isEqualTo("1700000000500")
        val favorite = server.takeRequest().requestUrl!!
        assertThat(favorite.encodedPath).isEqualTo("/rest/star.view")
        assertThat(outboxDao.count()).isEqualTo(0)
        assertThat(sync.state.value.lastCompleted).isEqualTo(2)
        assertThat(sync.state.value.running).isFalse()
    }

    @Test
    fun `a failed submission backs off and a manual retry succeeds at the same clock`() =
        withSync(dispatcher = { request, scrobbles ->
            if (request.path.orEmpty().contains("scrobble") && scrobbles == 1) {
                MockResponse().setResponseCode(500)
            } else {
                MockResponse().setBody("{\"status\":\"ok\"}")
            }
        }) { _, sync, queue ->
            queue.enqueuePlayCountDelta(1, ProviderMediaType.SONG, "song-1", 1_700_000_000_000, now = 1)

            val first = sync.drainNow()

            assertThat(first).isNotNull()
            assertThat(first!!.deferred).isEqualTo(1)
            val parked = outboxDao.selectByProvider(1).single()
            assertThat(parked.attempts).isEqualTo(1)
            assertThat(parked.nextAttemptAt).isEqualTo(CLOCK + OutboxDrainer.BACKOFF_BASE_MS)
            assertThat(parked.lastError).isNotNull()

            val retry = sync.retryAllNow()

            assertThat(retry).isNotNull()
            assertThat(retry!!.completed).isEqualTo(1)
            assertThat(outboxDao.count()).isEqualTo(0)
            assertThat(sync.state.value.lastCompleted).isEqualTo(1)
        }

    @Test
    fun `a provider that is offline keeps every row without recording a failure`() =
        withSync(runGate = ProviderRunGate { ProviderStatus.OFFLINE }) { _, sync, queue ->
            queue.enqueuePlayCountDelta(1, ProviderMediaType.SONG, "song-1", 1_700_000_000_000, now = 1)

            val report = sync.drainNow()

            assertThat(report).isNotNull()
            assertThat(report!!.completed).isEqualTo(0)
            assertThat(outboxDao.count()).isEqualTo(1)
            val kept = outboxDao.selectByProvider(1).single()
            assertThat(kept.attempts).isEqualTo(0)
            assertThat(kept.lastError).isNull()
        }

    @Test
    fun `a foreground request runs one drain`() = withSync { _, sync, _ ->
        sync.requestDrain()

        withTimeout(5_000) { sync.state.first { it.lastRunAt != null } }

        assertThat(sync.state.value.running).isFalse()
    }

    private fun withSync(
        runGate: ProviderRunGate = AllowAllRunGate,
        dispatcher: (RecordedRequest, Int) -> MockResponse = { _, _ ->
            MockResponse().setBody("{\"status\":\"ok\"}")
        },
        block: suspend (MockWebServer, OutboxSync, OutboxQueue) -> Unit,
    ): Unit = runBlocking {
        db = OmnisonicDatabaseFactory(appContext).openInMemory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            newProvider(id = 1)
            MockWebServer().use { server ->
                var scrobbles = 0
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path.orEmpty().contains("scrobble")) scrobbles += 1
                        return dispatcher(request, scrobbles)
                    }
                }
                server.start()
                val client = clientFor(server)
                val queue = OutboxQueue(
                    db,
                    outboxDao,
                    moshi = OmnisonicJson.moshi,
                )
                val sync = OutboxSync(
                    store = RoomOutboxStore(db),
                    providerLocator = { client },
                    pending = OutboxPendingRepository(db),
                    scope = scope,
                    logger = OmnisonicLogger(ConsoleLogSink()),
                    runGate = runGate,
                    clock = { CLOCK },
                )
                block(server, sync, queue)
            }
        } finally {
            scope.cancel()
            db.close()
        }
    }

    private fun clientFor(server: MockWebServer): SubsonicClient = SubsonicClient(
        ProviderConnection(
            providerId = 1,
            username = "admin",
            password = "hunter2",
            endpoint = ProviderEndpoint.Remote(ConnectionBlock("http", server.hostName, server.port)),
            serverSoftwareHint = "navidrome",
        ),
        HttpClientFactory(OmnisonicLogger(ConsoleLogSink())).create(ConnectionSpec()),
    )

    private companion object {
        const val CLOCK = 2_000L
    }
}
