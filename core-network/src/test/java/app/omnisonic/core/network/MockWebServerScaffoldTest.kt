package app.omnisonic.core.network

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test

class MockWebServerScaffoldTest {

    @Test
    fun mockWebServerAnswersAPlainRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            server.start()

            val response = OkHttpClient().newCall(
                Request.Builder().url(server.url("/probe")).build()
            ).execute()

            response.use {
                assertThat(it.body?.string()).isEqualTo("ok")
            }
            assertThat(server.takeRequest().path).isEqualTo("/probe")
        }
    }
}
