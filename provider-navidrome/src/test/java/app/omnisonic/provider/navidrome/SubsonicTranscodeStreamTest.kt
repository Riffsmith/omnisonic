package app.omnisonic.provider.navidrome

import app.omnisonic.core.model.log.ConsoleLogSink
import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.core.network.HttpClientFactory
import app.omnisonic.core.network.ZstdProbe
import app.omnisonic.provider.api.ConnectionBlock
import app.omnisonic.provider.api.ProviderAuthException
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderEndpoint
import app.omnisonic.provider.api.ProviderAuthMethod
import app.omnisonic.provider.api.TranscodeFallbackReason
import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private const val STREAM_TEST_PASSWORD = "hunter2"

private class RecordingLogSink : LogSink {
    val lines = mutableListOf<String>()

    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        lines += buildString {
            append("$level/$tag: $message")
            var cause = error
            var depth = 0
            while (cause != null && depth < 10) {
                append(" | ${cause.message}")
                cause = cause.cause
                depth++
            }
        }
    }

    fun fallbackLines(): List<String> = lines.filter { it.contains("transcode fallback") }
}

private fun transcodeTestConnection(primary: ConnectionBlock): ProviderConnection = ProviderConnection(
    providerId = 1L,
    username = "admin",
    password = STREAM_TEST_PASSWORD,
    endpoint = ProviderEndpoint.Remote(primary),
    authMethod = ProviderAuthMethod.TOKEN,
)

private fun transcodeTestClient(connection: ProviderConnection, sink: RecordingLogSink): SubsonicClient =
    SubsonicClient(
        connection,
        HttpClientFactory(OmnisonicLogger(ConsoleLogSink()), zstdProbe = ZstdProbe { true }).create(ConnectionSpec()),
        logger = OmnisonicLogger(sink),
    )

private fun pathDispatcher(responses: Map<String, String>): Dispatcher =
    object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            val endpoint = path.substringBefore("?").substringAfter("/rest/").substringBefore(".view")
            val body = responses[endpoint] ?: "{\"status\":\"ok\"}"
            return MockResponse().setBody(body)
        }
    }

private fun extensionsBody(vararg names: String): String =
    """{"status":"ok","openSubsonicExtensions":[${names.joinToString(",") { """{"name":"$it","versions":[1]}""" }}]}"""

private const val DECISION_CAN_TRANSCODE =
    """{"status":"ok","transcodeDecision":{"canDirectPlay":false,"canTranscode":true,"transcodeReason":[],"errorReason":null,"transcodeParams":"opaque-params-1"}}"""

private const val DECISION_DECLINED =
    """{"status":"ok","transcodeDecision":{"canDirectPlay":true,"canTranscode":false,"transcodeReason":["server says no"],"errorReason":"secret server words 123","transcodeParams":null}}"""

private fun md5hex(value: String): String =
    MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

class SubsonicTranscodeStreamTest {

    @Test
    fun `extension absent lands on the plain stream with rate format and seek offset`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(mapOf("getOpenSubsonicExtensions" to extensionsBody("transcodeOffset")))
            server.start()
            val sink = RecordingLogSink()
            val client = transcodeTestClient(transcodeTestConnection(ConnectionBlock("http", server.hostName, server.port)), sink)

            runBlocking { client.probe() }
            val resolution = client.resolveTranscodeStream("song-1", 96, "mp3", 30)

            assertThat(resolution.serverTranscoded).isFalse()
            assertThat(resolution.fallbackReason).isEqualTo(TranscodeFallbackReason.EXTENSION_ABSENT)
            val url = resolution.url!!.toHttpUrl()
            assertThat(url.encodedPath).isEqualTo("/rest/stream.view")
            assertThat(url.queryParameter("id")).isEqualTo("song-1")
            assertThat(url.queryParameter("maxBitRate")).isEqualTo("96")
            assertThat(url.queryParameter("format")).isEqualTo("mp3")
            assertThat(url.queryParameter("timeOffset")).isEqualTo("30")
            assertThat(url.queryParameter("u")).isEqualTo("admin")
            val salt = url.queryParameter("s")!!
            assertThat(url.queryParameter("t")).isEqualTo(md5hex(STREAM_TEST_PASSWORD + salt))
            // No decision call is made when the extension is absent.
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(sink.fallbackLines().single()).contains("reason=extension_absent")
            assertThat(sink.lines.joinToString("\n")).doesNotContain(STREAM_TEST_PASSWORD)
            Unit
        }
    }

    @Test
    fun `seek offset is omitted without the offset extension or when not positive`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(emptyMap())
            server.start()
            val sink = RecordingLogSink()
            val client = transcodeTestClient(transcodeTestConnection(ConnectionBlock("http", server.hostName, server.port)), sink)

            runBlocking { client.probe() }
            val withOffset = client.resolveTranscodeStream("song-1", null, null, 30).url!!.toHttpUrl()
            assertThat(withOffset.queryParameter("timeOffset")).isNull()
            assertThat(withOffset.queryParameter("maxBitRate")).isNull()
            assertThat(withOffset.queryParameter("format")).isNull()
            Unit
        }
    }

    @Test
    fun `non-positive offsets are omitted even with the offset extension probed`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(mapOf("getOpenSubsonicExtensions" to extensionsBody("transcodeOffset")))
            server.start()
            val sink = RecordingLogSink()
            val client = transcodeTestClient(transcodeTestConnection(ConnectionBlock("http", server.hostName, server.port)), sink)

            runBlocking { client.probe() }
            listOf(0, -30).forEach { offset ->
                val resolution = client.resolveTranscodeStream("song-1", 64, null, offset)

                assertThat(resolution.fallbackReason).isEqualTo(TranscodeFallbackReason.EXTENSION_ABSENT)
                val url = resolution.url!!.toHttpUrl()
                assertThat(url.queryParameter("timeOffset")).isNull()
                assertThat(url.queryParameter("maxBitRate")).isEqualTo("64")
            }
            Unit
        }
    }

    @Test
    fun `decision success returns the transcode stream with opaque params and seek offset`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getOpenSubsonicExtensions" to extensionsBody("transcoding", "transcodeOffset"),
                    "getTranscodeDecision" to DECISION_CAN_TRANSCODE,
                ),
            )
            server.start()
            val sink = RecordingLogSink()
            val client = transcodeTestClient(transcodeTestConnection(ConnectionBlock("http", server.hostName, server.port)), sink)

            runBlocking { client.probe() }
            val resolution = client.resolveTranscodeStream("song-1", 96, "mp3", 45)

            assertThat(resolution.serverTranscoded).isTrue()
            assertThat(resolution.fallbackReason).isNull()
            val url = resolution.url!!.toHttpUrl()
            assertThat(url.encodedPath).isEqualTo("/rest/getTranscodeStream.view")
            assertThat(url.queryParameter("mediaId")).isEqualTo("song-1")
            assertThat(url.queryParameter("mediaType")).isEqualTo("song")
            assertThat(url.queryParameter("transcodeParams")).isEqualTo("opaque-params-1")
            assertThat(url.queryParameter("offset")).isEqualTo("45")
            assertThat(url.queryParameter("maxBitRate")).isNull()
            assertThat(url.queryParameter("format")).isNull()
            val salt = url.queryParameter("s")!!
            assertThat(url.queryParameter("t")).isEqualTo(md5hex(STREAM_TEST_PASSWORD + salt))
            assertThat(resolution.url).doesNotContain(STREAM_TEST_PASSWORD)

            server.takeRequest()
            server.takeRequest()
            val decision = server.takeRequest().requestUrl!!
            assertThat(decision.encodedPath).isEqualTo("/rest/getTranscodeDecision.view")
            assertThat(decision.queryParameter("mediaId")).isEqualTo("song-1")
            assertThat(decision.queryParameter("mediaType")).isEqualTo("song")
            assertThat(decision.queryParameter("maxBitRate")).isNull()
            assertThat(decision.queryParameter("format")).isNull()
            assertThat(server.requestCount).isEqualTo(3)

            assertThat(sink.fallbackLines()).isEmpty()
            assertThat(sink.lines.joinToString("\n")).doesNotContain(STREAM_TEST_PASSWORD)
            assertThat(sink.lines.joinToString("\n")).doesNotContain("getTranscodeStream.view")
            Unit
        }
    }

    @Test
    fun `decision stream offset is positive only and independent of the plain offset extension`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getOpenSubsonicExtensions" to extensionsBody("transcoding"),
                    "getTranscodeDecision" to DECISION_CAN_TRANSCODE,
                ),
            )
            server.start()
            val sink = RecordingLogSink()
            val client = transcodeTestClient(transcodeTestConnection(ConnectionBlock("http", server.hostName, server.port)), sink)

            runBlocking { client.probe() }
            listOf(0, -30, 45).forEach { offset ->
                val resolution = client.resolveTranscodeStream("song-1", null, null, offset)

                assertThat(resolution.serverTranscoded).isTrue()
                val url = resolution.url!!.toHttpUrl()
                assertThat(url.queryParameter("offset")).isEqualTo(offset.takeIf { it > 0 }?.toString())
                assertThat(url.queryParameter("transcodeParams")).isEqualTo("opaque-params-1")
            }
            Unit
        }
    }

    @Test
    fun `server declined lands on the plain stream with a redacted logged reason`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getOpenSubsonicExtensions" to extensionsBody("transcoding", "transcodeOffset"),
                    "getTranscodeDecision" to DECISION_DECLINED,
                ),
            )
            server.start()
            val sink = RecordingLogSink()
            val client = transcodeTestClient(transcodeTestConnection(ConnectionBlock("http", server.hostName, server.port)), sink)

            runBlocking { client.probe() }
            val resolution = client.resolveTranscodeStream("song-1", 128, "opus", 12)

            assertThat(resolution.serverTranscoded).isFalse()
            assertThat(resolution.fallbackReason).isEqualTo(TranscodeFallbackReason.SERVER_DECLINED)
            val url = resolution.url!!.toHttpUrl()
            assertThat(url.encodedPath).isEqualTo("/rest/stream.view")
            assertThat(url.queryParameter("maxBitRate")).isEqualTo("128")
            assertThat(url.queryParameter("format")).isEqualTo("opus")
            assertThat(url.queryParameter("timeOffset")).isEqualTo("12")

            val logged = sink.fallbackLines().joinToString("\n")
            assertThat(logged).contains("reason=server_declined")
            assertThat(logged).doesNotContain("secret server words 123")
            assertThat(logged).doesNotContain("server says no")
            val allLogged = sink.lines.joinToString("\n")
            assertThat(allLogged).doesNotContain(STREAM_TEST_PASSWORD)
            assertThat(allLogged).doesNotContain("stream.view?")
            Unit
        }
    }

    @Test
    fun `decision error response lands on the plain stream with a redacted reason`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getOpenSubsonicExtensions" to extensionsBody("transcoding"),
                    "getTranscodeDecision" to """{"status":"failed","error":{"code":70,"message":"Requested data not found"}}""",
                ),
            )
            server.start()
            val sink = RecordingLogSink()
            val client = transcodeTestClient(transcodeTestConnection(ConnectionBlock("http", server.hostName, server.port)), sink)

            runBlocking { client.probe() }
            val resolution = client.resolveTranscodeStream("song-1", 96, "mp3", null)

            assertThat(resolution.fallbackReason).isEqualTo(TranscodeFallbackReason.DECISION_FAILED)
            assertThat(resolution.serverTranscoded).isFalse()
            val url = resolution.url!!.toHttpUrl()
            assertThat(url.encodedPath).isEqualTo("/rest/stream.view")
            assertThat(url.queryParameter("maxBitRate")).isEqualTo("96")
            assertThat(url.queryParameter("format")).isEqualTo("mp3")

            val logged = sink.fallbackLines().single()
            assertThat(logged).contains("reason=decision_failed")
            val allLogged = sink.lines.joinToString("\n")
            assertThat(allLogged).doesNotContain("Requested data not found")
            assertThat(allLogged).doesNotContain(STREAM_TEST_PASSWORD)
            assertThat(allLogged).doesNotContain("stream.view?")
            Unit
        }
    }

    @Test
    fun `decision malformed json lands on the plain stream with a redacted reason`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return if (path.contains("getTranscodeDecision")) {
                        MockResponse().setBody("<html>rendered</html>")
                    } else if (path.contains("getOpenSubsonicExtensions")) {
                        MockResponse().setBody(extensionsBody("transcoding"))
                    } else {
                        MockResponse().setBody("{\"status\":\"ok\"}")
                    }
                }
            }
            server.start()
            val sink = RecordingLogSink()
            val client = transcodeTestClient(transcodeTestConnection(ConnectionBlock("http", server.hostName, server.port)), sink)

            runBlocking { client.probe() }
            val resolution = client.resolveTranscodeStream("song-1", null, null, null)

            assertThat(resolution.fallbackReason).isEqualTo(TranscodeFallbackReason.DECISION_FAILED)
            assertThat(resolution.url!!.toHttpUrl().encodedPath).isEqualTo("/rest/stream.view")
            val allLogged = sink.lines.joinToString("\n")
            assertThat(sink.fallbackLines().single()).contains("reason=decision_failed")
            assertThat(allLogged).doesNotContain("rendered")
            assertThat(allLogged).doesNotContain(STREAM_TEST_PASSWORD)
            Unit
        }
    }

    @Test
    fun `decision http error lands on the plain stream with a redacted reason`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return if (path.contains("getTranscodeDecision")) {
                        MockResponse().setResponseCode(500).setBody("boom")
                    } else if (path.contains("getOpenSubsonicExtensions")) {
                        MockResponse().setBody(extensionsBody("transcoding"))
                    } else {
                        MockResponse().setBody("{\"status\":\"ok\"}")
                    }
                }
            }
            server.start()
            val sink = RecordingLogSink()
            val client = transcodeTestClient(transcodeTestConnection(ConnectionBlock("http", server.hostName, server.port)), sink)

            runBlocking { client.probe() }
            val resolution = client.resolveTranscodeStream("song-1", 32, "aac", 7)

            assertThat(resolution.fallbackReason).isEqualTo(TranscodeFallbackReason.DECISION_FAILED)
            val url = resolution.url!!.toHttpUrl()
            assertThat(url.encodedPath).isEqualTo("/rest/stream.view")
            assertThat(url.queryParameter("maxBitRate")).isEqualTo("32")
            assertThat(sink.fallbackLines().single()).contains("reason=decision_failed")
            Unit
        }
    }

    @Test
    fun `decision auth failure still throws instead of falling back`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getOpenSubsonicExtensions" to extensionsBody("transcoding"),
                    "getTranscodeDecision" to """{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}""",
                ),
            )
            server.start()
            val sink = RecordingLogSink()
            val client = transcodeTestClient(transcodeTestConnection(ConnectionBlock("http", server.hostName, server.port)), sink)

            runBlocking { client.probe() }

            assertThrows(ProviderAuthException::class.java) {
                runBlocking { client.resolveTranscodeStream("song-1") }
            }
            assertThat(sink.fallbackLines()).isEmpty()
            Unit
        }
    }

    @Test
    fun `canTranscode without usable params is treated as declined`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getOpenSubsonicExtensions" to extensionsBody("transcoding"),
                    "getTranscodeDecision" to """{"status":"ok","transcodeDecision":{"canDirectPlay":true,"canTranscode":true,"transcodeParams":""}}""",
                ),
            )
            server.start()
            val sink = RecordingLogSink()
            val client = transcodeTestClient(transcodeTestConnection(ConnectionBlock("http", server.hostName, server.port)), sink)

            runBlocking { client.probe() }
            val resolution = client.resolveTranscodeStream("song-1", 96, "mp3", null)

            assertThat(resolution.fallbackReason).isEqualTo(TranscodeFallbackReason.SERVER_DECLINED)
            assertThat(resolution.serverTranscoded).isFalse()
            assertThat(resolution.url!!.toHttpUrl().encodedPath).isEqualTo("/rest/stream.view")
            assertThat(sink.fallbackLines().single()).contains("reason=server_declined")
            Unit
        }
    }
}
