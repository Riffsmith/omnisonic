package app.omnisonic.core.data.queue

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.provider.api.OutboxActionPayload
import app.omnisonic.provider.api.OutboxActions
import app.omnisonic.provider.api.ProviderMediaType
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.core.data.db.queue.RoomOutboxStore
import app.omnisonic.core.model.log.ConsoleLogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.core.network.HttpClientFactory
import app.omnisonic.provider.api.ConnectionBlock
import app.omnisonic.provider.api.OutboxDrainer
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderEndpoint
import app.omnisonic.provider.navidrome.SubsonicClient
import com.google.common.truth.Truth.assertThat
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
class OutboxDrainEndToEndTest : DatabaseTestBase() {

    @Test
    fun `three plays drain into three submissions with their exact timestamps`() = runDbTest {
        newProvider(id = 1)
        MockWebServer().use { server ->
            server.dispatcher = scriptedDispatcher { _ -> okResponse() }
            server.start()
            val client = clientFor(server, serverSoftwareHint = "navidrome")
            val queue = OutboxQueue(db, outboxDao)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = ProviderMediaType.SONG, externalId = "song-1", playedAtMs = 1_700_000_000_000, now = 1)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = ProviderMediaType.SONG, externalId = "song-1", playedAtMs = 1_700_000_000_500, now = 2)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = ProviderMediaType.SONG, externalId = "song-1", playedAtMs = 1_700_000_001_000, now = 3)

            val report = OutboxDrainer(
                store = RoomOutboxStore(outboxDao),
                providerLocator = { client },
                clock = { 2_000L },
            ).drain(providerId = 1)

            assertThat(report.completed).isEqualTo(1)
            // The drainer's capability probe (ping + extensions) precedes the scrobbles.
            server.takeRequest()
            server.takeRequest()
            val submissions = List(3) { server.takeRequest().requestUrl!! }
            submissions.forEach { request ->
                assertThat(request.encodedPath).isEqualTo("/rest/scrobble.view")
                assertThat(request.queryParameter("id")).isEqualTo("song-1")
                assertThat(request.queryParameter("submission")).isEqualTo("true")
            }
            assertThat(submissions[0].queryParameter("time")).isEqualTo("1700000000000")
            assertThat(submissions[1].queryParameter("time")).isEqualTo("1700000000500")
            assertThat(submissions[2].queryParameter("time")).isEqualTo("1700000001000")
            assertThat(outboxDao.count()).isEqualTo(0)
        }
    }

    @Test
    fun `plays in the same millisecond submit strictly increasing times`() = runDbTest {
        newProvider(id = 1)
        MockWebServer().use { server ->
            server.dispatcher = scriptedDispatcher { _ -> okResponse() }
            server.start()
            val client = clientFor(server, serverSoftwareHint = "navidrome")
            val queue = OutboxQueue(db, outboxDao)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = ProviderMediaType.SONG, externalId = "song-1", playedAtMs = 1_700_000_000_000, now = 1)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = ProviderMediaType.SONG, externalId = "song-1", playedAtMs = 1_700_000_000_000, now = 2)

            OutboxDrainer(
                store = RoomOutboxStore(outboxDao),
                providerLocator = { client },
                clock = { 2_000L },
            ).drain(providerId = 1)

            server.takeRequest()
            server.takeRequest()
            val first = server.takeRequest().requestUrl!!
            val second = server.takeRequest().requestUrl!!
            assertThat(first.queryParameter("time")).isEqualTo("1700000000000")
            assertThat(second.queryParameter("time")).isEqualTo("1700000000001")
        }
    }

    @Test
    fun `cold drain probes exactly once and then scrobbles with time`() = runDbTest {
        newProvider(id = 1)
        MockWebServer().use { server ->
            var pings = 0
            server.dispatcher = scriptedDispatcher { request ->
                if (request.path.orEmpty().contains("ping")) pings += 1
                okResponse()
            }
            server.start()
            val client = clientFor(server, serverSoftwareHint = "navidrome")
            val queue = OutboxQueue(db, outboxDao)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = ProviderMediaType.SONG, externalId = "song-1", playedAtMs = 1_700_000_000_000, now = 1)

            val drainer = OutboxDrainer(
                store = RoomOutboxStore(outboxDao),
                providerLocator = { client },
                clock = { 2_000L },
            )

            drainer.drain(providerId = 1)
            val firstWasProbe = server.takeRequest().requestUrl!!.encodedPath.endsWith("/ping.view")
            server.takeRequest()
            val scrobbleOne = server.takeRequest().requestUrl!!

            queue.enqueuePlayCountDelta(providerId = 1, mediaType = ProviderMediaType.SONG, externalId = "song-1", playedAtMs = 1_700_000_000_123, now = 3)
            drainer.drain(providerId = 1)
            val scrobbleTwo = server.takeRequest().requestUrl!!

            assertThat(pings).isEqualTo(1)
            assertThat(firstWasProbe).isTrue()
            assertThat(scrobbleOne.queryParameter("time")).isEqualTo("1700000000000")
            assertThat(scrobbleTwo.queryParameter("time")).isEqualTo("1700000000123")
        }
    }

    @Test
    fun `per-provider override off omits the timestamp`() = runDbTest {
        newProvider(id = 1)
        MockWebServer().use { server ->
            server.dispatcher = scriptedDispatcher { _ -> okResponse() }
            server.start()
            val client = clientFor(server, serverSoftwareHint = "navidrome", backdateScrobbles = "off")
            val queue = OutboxQueue(db, outboxDao)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = ProviderMediaType.SONG, externalId = "song-1", playedAtMs = 1_700_000_000_000, now = 1)

            OutboxDrainer(
                store = RoomOutboxStore(outboxDao),
                providerLocator = { client },
                clock = { 2_000L },
            ).drain(providerId = 1)

            server.takeRequest()
            server.takeRequest()
            val scrobble = server.takeRequest().requestUrl!!
            assertThat(scrobble.encodedPath).isEqualTo("/rest/scrobble.view")
            assertThat(scrobble.queryParameter("time")).isNull()
        }
    }

    @Test
    fun `unknown server hint stays conservative without an override`() = runDbTest {
        newProvider(id = 1)
        MockWebServer().use { server ->
            server.dispatcher = scriptedDispatcher { _ -> okResponse() }
            server.start()
            val client = clientFor(server, serverSoftwareHint = "unknown-server")
            val queue = OutboxQueue(db, outboxDao)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = ProviderMediaType.SONG, externalId = "song-1", playedAtMs = 1_700_000_000_000, now = 1)

            OutboxDrainer(
                store = RoomOutboxStore(outboxDao),
                providerLocator = { client },
                clock = { 2_000L },
            ).drain(providerId = 1)

            server.takeRequest()
            server.takeRequest()
            val scrobble = server.takeRequest().requestUrl!!
            assertThat(scrobble.encodedPath).isEqualTo("/rest/scrobble.view")
            assertThat(scrobble.queryParameter("time")).isNull()
        }
    }

    @Test
    fun `resume point clears the server bookmark after the scrobble`() = runDbTest {
        newProvider(id = 1)
        MockWebServer().use { server ->
            server.dispatcher = scriptedDispatcher { _ -> okResponse() }
            server.start()
            val client = clientFor(server, serverSoftwareHint = "navidrome")
            val queue = OutboxQueue(db, outboxDao)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = ProviderMediaType.SONG, externalId = "song-1", playedAtMs = 1_700_000_000_000, now = 1)
            queue.enqueueMediaChange(
                providerId = 1,
                mediaType = ProviderMediaType.SONG,
                externalId = "song-1",
                action = OutboxActions.RESUME_POINT,
                payload = OutboxActionPayload(resumePointMs = 0),
                now = 2,
            )

            val report = OutboxDrainer(
                store = RoomOutboxStore(outboxDao),
                providerLocator = { client },
                clock = { 2_000L },
            ).drain(providerId = 1)

            assertThat(report.completed).isEqualTo(2)
            server.takeRequest()
            server.takeRequest()
            val scrobble = server.takeRequest().requestUrl!!
            val bookmarkClear = server.takeRequest().requestUrl!!
            assertThat(scrobble.encodedPath).isEqualTo("/rest/scrobble.view")
            assertThat(bookmarkClear.encodedPath).isEqualTo("/rest/deleteBookmark.view")
            assertThat(bookmarkClear.queryParameter("id")).isEqualTo("song-1")
            assertThat(outboxDao.count()).isEqualTo(0)
        }
    }

    @Test
    fun `partial acceptance keeps the remaining plays for the next drain`() = runDbTest {
        newProvider(id = 1)
        MockWebServer().use { server ->
            var scrobbles = 0
            server.dispatcher = scriptedDispatcher { request ->
                if (request.path.orEmpty().contains("scrobble")) {
                    scrobbles += 1
                    if (scrobbles == 3) failedResponse() else okResponse()
                } else {
                    okResponse()
                }
            }
            server.start()
            val client = clientFor(server, serverSoftwareHint = "navidrome")
            val store = RoomOutboxStore(outboxDao)
            val queue = OutboxQueue(db, outboxDao)
            repeat(3) { index ->
                queue.enqueuePlayCountDelta(
                    providerId = 1,
                    mediaType = ProviderMediaType.SONG,
                    externalId = "song-1",
                    playedAtMs = 1_700_000_000_000 + index,
                    now = 1,
                )
            }
            val drainer = OutboxDrainer(store = store, providerLocator = { client }, clock = { 2_000L })

            val first = drainer.drain(providerId = 1)

            assertThat(first.progressed).isEqualTo(1)
            val remaining = outboxDao.selectByProvider(1).single()
            assertThat(remaining.attempts).isEqualTo(0)
            assertThat(remaining.payloadJson).contains("1700000000002")

            val second = drainer.drain(providerId = 1)

            assertThat(second.completed).isEqualTo(1)
            assertThat(outboxDao.count()).isEqualTo(0)
        }
    }

    @Test
    fun `rejected submissions back off with an increasing attempt count`() = runDbTest {
        newProvider(id = 1)
        MockWebServer().use { server ->
            server.dispatcher = scriptedDispatcher { _ -> failedResponse() }
            server.start()
            val client = clientFor(server, serverSoftwareHint = "navidrome")
            val store = RoomOutboxStore(outboxDao)
            val queue = OutboxQueue(db, outboxDao)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = ProviderMediaType.SONG, externalId = "song-1", playedAtMs = 1_700_000_000_000, now = 1)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = ProviderMediaType.SONG, externalId = "song-1", playedAtMs = 1_700_000_000_001, now = 1)

            val report = OutboxDrainer(store = store, providerLocator = { client }, clock = { 2_000L })
                .drain(providerId = 1)

            assertThat(report.deferred).isEqualTo(1)
            val deferred = outboxDao.selectByProvider(1).single()
            assertThat(deferred.attempts).isEqualTo(1)
            assertThat(deferred.nextAttemptAt).isEqualTo(2_000L + OutboxDrainer.BACKOFF_BASE_MS)
            assertThat(deferred.lastError).isEqualTo("server accepted no submissions")
        }
    }

    private fun clientFor(
        server: MockWebServer,
        serverSoftwareHint: String? = null,
        backdateScrobbles: String? = null,
    ): SubsonicClient = SubsonicClient(
        ProviderConnection(
            providerId = 1,
            username = "admin",
            password = "hunter2",
            endpoint = ProviderEndpoint.Remote(ConnectionBlock("http", server.hostName, server.port)),
            serverSoftwareHint = serverSoftwareHint,
            backdateScrobbles = decodeBackdateMode(backdateScrobbles),
        ),
        HttpClientFactory(OmnisonicLogger(ConsoleLogSink())).create(ConnectionSpec()),
    )

    private fun decodeBackdateMode(raw: String?): app.omnisonic.provider.api.BackdateScrobbles =
        when (raw?.lowercase()) {
            "on" -> app.omnisonic.provider.api.BackdateScrobbles.ON
            "off" -> app.omnisonic.provider.api.BackdateScrobbles.OFF
            else -> app.omnisonic.provider.api.BackdateScrobbles.AUTO
        }

    private fun scriptedDispatcher(respond: (RecordedRequest) -> MockResponse): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }

    private fun okResponse(): MockResponse = MockResponse().setBody("{\"status\":\"ok\"}")

    private fun failedResponse(): MockResponse =
        MockResponse().setBody("{\"status\":\"failed\",\"error\":{\"code\":0,\"message\":\"rejected\"}}")
}
