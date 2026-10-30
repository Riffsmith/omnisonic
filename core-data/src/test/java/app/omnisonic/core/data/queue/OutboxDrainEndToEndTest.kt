package app.omnisonic.core.data.queue

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.queue.OutboxPayload
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.core.data.db.queue.RoomOutboxStore
import app.omnisonic.core.model.log.ConsoleLogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.core.network.HttpClientFactory
import app.omnisonic.provider.api.ConnectionBlock
import app.omnisonic.provider.api.OutboxDrainer
import app.omnisonic.provider.api.ProviderConnection
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
    fun `play count drain splits into one submission per play with backdated timestamps`() = runDbTest {
        newProvider(id = 1)
        MockWebServer().use { server ->
            server.dispatcher = scriptedDispatcher { request ->
                if (request.path.orEmpty().contains("getOpenSubsonicExtensions")) {
                    MockResponse().setBody(
                        "{\"status\":\"ok\",\"openSubsonicExtensions\":[{\"name\":\"formPost\",\"versions\":[1]}]}",
                    )
                } else {
                    okResponse()
                }
            }
            server.start()
            val client = clientFor(server)
            val queue = OutboxQueue(db, outboxDao)
            queue.enqueueMediaChange(
                providerId = 1,
                mediaType = "SONG",
                externalId = "song-1",
                action = OutboxQueue.ACTION_PLAY_COUNT,
                payload = OutboxPayload(playCountDelta = 3, lastPlayedAt = 1_700_000_000_000),
                now = 1,
            )
            client.probe()
            server.takeRequest()
            server.takeRequest()

            val report = OutboxDrainer(
                store = RoomOutboxStore(outboxDao),
                providerLocator = { client },
                clock = { 2_000L },
            ).drain(providerId = 1)

            assertThat(report.completed).isEqualTo(1)
            val submissions = List(3) { server.takeRequest().requestUrl!! }
            submissions.forEach { request ->
                assertThat(request.encodedPath).isEqualTo("/rest/scrobble.view")
                assertThat(request.queryParameter("id")).isEqualTo("song-1")
                assertThat(request.queryParameter("submission")).isEqualTo("true")
                assertThat(request.queryParameter("time")).isEqualTo("1700000000000")
            }
            assertThat(outboxDao.count()).isEqualTo(0)
        }
    }

    @Test
    fun `resume point clears the server bookmark after the scrobble`() = runDbTest {
        newProvider(id = 1)
        MockWebServer().use { server ->
            server.dispatcher = scriptedDispatcher { _ -> okResponse() }
            server.start()
            val client = clientFor(server)
            val queue = OutboxQueue(db, outboxDao)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = "SONG", externalId = "song-1", playCountDelta = 1, now = 1)
            queue.enqueueMediaChange(
                providerId = 1,
                mediaType = "SONG",
                externalId = "song-1",
                action = OutboxQueue.ACTION_RESUME_POINT,
                payload = OutboxPayload(resumePointMs = 0),
                now = 2,
            )

            val report = OutboxDrainer(
                store = RoomOutboxStore(outboxDao),
                providerLocator = { client },
                clock = { 2_000L },
            ).drain(providerId = 1)

            assertThat(report.completed).isEqualTo(2)
            val scrobble = server.takeRequest().requestUrl!!
            val bookmarkClear = server.takeRequest().requestUrl!!
            assertThat(scrobble.encodedPath).isEqualTo("/rest/scrobble.view")
            assertThat(bookmarkClear.encodedPath).isEqualTo("/rest/deleteBookmark.view")
            assertThat(bookmarkClear.queryParameter("id")).isEqualTo("song-1")
            assertThat(outboxDao.count()).isEqualTo(0)
        }
    }

    @Test
    fun `partial acceptance keeps the remaining delta for the next drain`() = runDbTest {
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
            val client = clientFor(server)
            val store = RoomOutboxStore(outboxDao)
            val queue = OutboxQueue(db, outboxDao)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = "SONG", externalId = "song-1", playCountDelta = 3, now = 1)
            val drainer = OutboxDrainer(store = store, providerLocator = { client }, clock = { 2_000L })

            val first = drainer.drain(providerId = 1)

            assertThat(first.progressed).isEqualTo(1)
            val remaining = outboxDao.selectByProvider(1).single()
            assertThat(remaining.attempts).isEqualTo(0)
            assertThat(remaining.payloadJson).contains("\"playCountDelta\":1")

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
            val client = clientFor(server)
            val store = RoomOutboxStore(outboxDao)
            val queue = OutboxQueue(db, outboxDao)
            queue.enqueuePlayCountDelta(providerId = 1, mediaType = "SONG", externalId = "song-1", playCountDelta = 2, now = 1)

            val report = OutboxDrainer(store = store, providerLocator = { client }, clock = { 2_000L })
                .drain(providerId = 1)

            assertThat(report.deferred).isEqualTo(1)
            val deferred = outboxDao.selectByProvider(1).single()
            assertThat(deferred.attempts).isEqualTo(1)
            assertThat(deferred.nextAttemptAt).isEqualTo(2_000L + OutboxDrainer.BACKOFF_BASE_MS)
            assertThat(deferred.lastError).isEqualTo("server accepted no submissions")
        }
    }

    private fun clientFor(server: MockWebServer): SubsonicClient = SubsonicClient(
        ProviderConnection(
            providerId = 1,
            username = "admin",
            password = "hunter2",
            primary = ConnectionBlock("http", server.hostName, server.port),
        ),
        HttpClientFactory(OmnisonicLogger(ConsoleLogSink())).create(ConnectionSpec()),
    )

    private fun scriptedDispatcher(respond: (RecordedRequest) -> MockResponse): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }

    private fun okResponse(): MockResponse = MockResponse().setBody("{\"status\":\"ok\"}")

    private fun failedResponse(): MockResponse =
        MockResponse().setBody("{\"status\":\"failed\",\"error\":{\"code\":0,\"message\":\"rejected\"}}")
}
