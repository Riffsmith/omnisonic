package app.omnisonic.core.network

import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private class NetworkRecordingSink {
    val entries = mutableListOf<Pair<LogLevel, String>>()

    val sink = LogSink { level, _tag, message, _error -> entries += level to message }
}

class NetworkLogInterceptorTest {

    private val enabled = NetworkLogGate { true }

    private fun client(sink: NetworkRecordingSink, gate: NetworkLogGate): okhttp3.OkHttpClient =
        HttpClientFactory(
            // General logging stays on its own logger; only the network stream
            // records into the test sink, mirroring production wiring.
            logger = OmnisonicLogger(LogSink { _, _, _, _ -> }),
            networkLog = gate,
            networkLogger = OmnisonicLogger(sink.sink),
        ).create(ConnectionSpec(headers = mapOf("Authorization" to "Bearer super-secret")))

    @Test
    fun gateOffWritesNothing() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.start()
            val sink = NetworkRecordingSink()

            client(sink, NetworkLogGate.DISABLED)
                .newCall(Request.Builder().url(server.url("/rest/ping.view")).build())
                .execute()
                .use { }

            assertThat(sink.entries).isEmpty()
        }
    }

    @Test
    fun gateOnWritesOneMetadataLine() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("payload-body")
                    .setHeader("Content-Type", "application/json"),
            )
            server.start()
            val sink = NetworkRecordingSink()

            client(sink, enabled)
                .newCall(Request.Builder().url(server.url("/rest/ping.view")).build())
                .execute()
                .use { }

            val (level, line) = sink.entries.single()
            assertThat(level).isEqualTo(LogLevel.INFO)
            assertThat(line).contains("GET ")
            assertThat(line).contains("/rest/ping.view")
            assertThat(line).contains("-> 200")
            assertThat(line).contains("12 bytes")
            assertThat(line).contains("application/json")
        }
    }

    @Test
    fun bodiesAndHeadersNeverAppear() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("payload-body")
                    .setHeader("X-Private-Header", "header-value"),
            )
            server.start()
            val sink = NetworkRecordingSink()

            client(sink, enabled)
                .newCall(Request.Builder().url(server.url("/rest/ping.view")).build())
                .execute()
                .use { }

            val line = sink.entries.single().second
            assertThat(line).doesNotContain("payload-body")
            assertThat(line).doesNotContain("header-value")
            assertThat(line).doesNotContain("super-secret")
            assertThat(line).doesNotContain("Authorization")
        }
    }

    @Test
    fun credentialQueryValuesAreRedacted() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.start()
            val sink = NetworkRecordingSink()

            client(sink, enabled)
                .newCall(
                    Request.Builder()
                        .url(server.url("/rest/ping.view?u=admin&p=hunter2&t=tok&s=salt&id=42"))
                        .build(),
                )
                .execute()
                .use { }

            val line = sink.entries.single().second
            assertThat(line).contains("u=[REDACTED]")
            assertThat(line).contains("p=[REDACTED]")
            assertThat(line).contains("t=[REDACTED]")
            assertThat(line).contains("s=[REDACTED]")
            assertThat(line).contains("id=42")
            assertThat(line).doesNotContain("hunter2")
        }
    }

    @Test
    fun ioFailureLogsTheExceptionAndRethrows() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.start()
            val sink = NetworkRecordingSink()

            assertThrows(IOException::class.java) {
                client(sink, enabled)
                    .newCall(Request.Builder().url(server.url("/rest/ping.view?p=hunter2")).build())
                    .execute()
                    .use { }
            }

            val (level, line) = sink.entries.single()
            assertThat(level).isEqualTo(LogLevel.WARN)
            assertThat(line).contains("failed after")
            assertThat(line).contains("p=[REDACTED]")
            assertThat(line).doesNotContain("hunter2")
        }
    }
}
