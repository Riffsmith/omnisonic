package app.omnisonic.provider.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class TranscodeStreamDefaultTest {

    private class StreamUrlProvider : RecordingProvider() {
        override fun streamUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? =
            "https://media.example.test/stream/$externalId?maxBitRate=$maxBitRateKbps&format=$format"
    }

    @Test
    fun `default resolution keeps the plain stream url and reports no fallback`() = runBlocking {
        val provider = StreamUrlProvider()

        val resolution = provider.resolveTranscodeStream("song-1", 128, "opus", 12)

        assertThat(resolution.url).isEqualTo("https://media.example.test/stream/song-1?maxBitRate=128&format=opus")
        assertThat(resolution.serverTranscoded).isFalse()
        assertThat(resolution.fallbackReason).isNull()
        Unit
    }
}
