package app.omnisonic.core.network

import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import com.github.luben.zstd.Zstd
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private class RecordingSink : LogSink {
    val entries = mutableListOf<String>()

    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        entries += "$tag: $message"
    }
}

class HttpClientFactoryTest {

    private fun gzipped(payload: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(payload) }
        output.toByteArray()
    }

    @Test
    fun requestCarriesCustomHeadersAndAdvertisingOnlyZstdAndGzip() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.start()
            val client = HttpClientFactory(OmnisonicLogger(RecordingSink())).create(
                ConnectionSpec(headers = mapOf("X-Client-Marker" to "omnisonic-test")),
            )

            client.newCall(Request.Builder().url(server.url("/rest/ping.view")).build()).execute().use { }

            val request = server.takeRequest()
            assertThat(request.getHeader("Accept-Encoding")).isEqualTo("zstd, gzip")
            assertThat(request.getHeader("X-Client-Marker")).isEqualTo("omnisonic-test")
        }
    }

    @Test
    fun zstdEncodedResponsesAreDecoded() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Encoding", "zstd").setBody(Buffer().write(Zstd.compress("hello-navidrome".toByteArray()))),
            )
            server.start()
            val client = HttpClientFactory(OmnisonicLogger(RecordingSink())).create(ConnectionSpec())

            val body = client.newCall(Request.Builder().url(server.url("/probe")).build()).execute().use { it.body!!.string() }

            assertThat(body).isEqualTo("hello-navidrome")
        }
    }

    @Test
    fun gzipEncodedResponsesAreDecoded() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(gzipped("hello-gzip".toByteArray()))))
            server.start()
            val client = HttpClientFactory(OmnisonicLogger(RecordingSink())).create(ConnectionSpec())

            val body = client.newCall(Request.Builder().url(server.url("/probe")).build()).execute().use { it.body!!.string() }

            assertThat(body).isEqualTo("hello-gzip")
        }
    }

    @Test
    fun mediaRequestsMustReturnRawBodies() {
        MockWebServer().use { server ->
            val compressed = Zstd.compress("raw-media".toByteArray())
            server.enqueue(
                MockResponse().setHeader("Content-Encoding", "zstd").setBody(Buffer().write(compressed)),
            )
            server.start()
            val client = HttpClientFactory(OmnisonicLogger(RecordingSink())).create(
                ConnectionSpec(profile = ClientProfile.MEDIA, headers = mapOf("X-Media-Marker" to "raw")),
            )

            val body = client.newCall(Request.Builder().url(server.url("/media")).build()).execute().use { it.body!!.bytes() }

            assertThat(body).isEqualTo(compressed)
            val request = server.takeRequest()
            assertThat(request.getHeader("Accept-Encoding")).isNull()
            assertThat(request.getHeader("X-Media-Marker")).isEqualTo("raw")
            assertThat(client.readTimeoutMillis).isAtLeast(ConnectionSpec.MEDIA_READ_TIMEOUT_MS.toInt())
        }
    }

    @Test
    fun nativeDecoderLoadFailureIsWrappedAsIoException() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Encoding", "zstd").setBody(Buffer().write(Zstd.compress("x".toByteArray()))),
            )
            server.start()
            val factory = HttpClientFactory(
                OmnisonicLogger(RecordingSink()),
                ResponseDecoder { _, _ -> throw UnsatisfiedLinkError("missing libzstd-jni") },
            )
            val client = factory.create(ConnectionSpec())

            val error = assertThrows(java.io.IOException::class.java) {
                client.newCall(Request.Builder().url(server.url("/probe")).build()).execute().use { it.body!!.string() }
            }

            assertThat(error).hasCauseThat().isInstanceOf(UnsatisfiedLinkError::class.java)
        }
    }

    @Test
    fun nativeDecoderReadFailureIsWrappedAsIoException() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Encoding", "zstd").setBody(Buffer().write(Zstd.compress("x".toByteArray()))),
            )
            server.start()
            val factory = HttpClientFactory(
                OmnisonicLogger(RecordingSink()),
                ResponseDecoder { _, _ ->
                    object : java.io.InputStream() {
                        override fun read(): Int = throw UnsatisfiedLinkError("missing native read")
                    }
                },
            )
            val client = factory.create(ConnectionSpec())

            val error = assertThrows(java.io.IOException::class.java) {
                client.newCall(Request.Builder().url(server.url("/probe")).build()).execute().use { it.body!!.string() }
            }

            assertThat(error).hasCauseThat().isInstanceOf(UnsatisfiedLinkError::class.java)
        }
    }

    @Test
    fun readTimeoutMissesAreSurfaced() {
        MockWebServer().use { server ->
            // server accepts the connection but then stalls before responding — read timeout path
            server.enqueue(MockResponse().setBodyDelay(1, TimeUnit.SECONDS).setBody("late"))
            server.start()
            val client = HttpClientFactory(OmnisonicLogger(RecordingSink())).create(
                ConnectionSpec(readTimeoutMs = 50L),
            )

            assertThrows(java.net.SocketTimeoutException::class.java) {
                client.newCall(Request.Builder().url(server.url("/probe")).build()).execute().use { it.body?.string() }
            }
        }
    }

    @Test
    fun requestLoggingHidesCredentialQueryValues() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.start()
            val sink = RecordingSink()
            val client = HttpClientFactory(OmnisonicLogger(sink)).create(ConnectionSpec())

            client.newCall(
                Request.Builder().url(server.url("/rest/ping.view?u=admin&t=abc123&p=hunter2&s=salted&v=1.13.0&c=Omnisonic&f=json")).build(),
            ).execute().use { }

            val logged = sink.entries.joinToString(" ")
            assertThat(logged).doesNotContain("admin")
            assertThat(logged).doesNotContain("abc123")
            assertThat(logged).doesNotContain("hunter2")
            assertThat(logged).doesNotContain("salted")
            assertThat(logged).contains("[REDACTED]")
        }
    }
}
