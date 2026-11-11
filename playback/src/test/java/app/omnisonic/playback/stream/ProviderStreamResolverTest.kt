package app.omnisonic.playback.stream

import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.stream.StreamTransport
import app.omnisonic.provider.api.MediaProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

private fun entry(
    isRadioStation: Boolean = false,
    durationMs: Long? = 200_000L,
) = PlaybackQueueEntry(
    providerId = 1,
    externalId = "ext-1",
    title = "T1",
    durationMs = durationMs,
    isRadioStation = isRadioStation,
)

private fun resolver(uri: String?): ProviderStreamResolver {
    val provider = mockk<MediaProvider> {
        every { streamUrl(any(), any(), any()) } returns uri
    }
    return ProviderStreamResolver({ provider })
}

class ProviderStreamResolverTest {

    @Test
    fun `resolves a song to the provider stream url`() = runTest {
        val stream = resolver("http://host/stream?id=ext-1").resolve(entry())

        assertThat(stream!!.uri).isEqualTo("http://host/stream?id=ext-1")
        assertThat(stream.requiresNetwork).isTrue()
        assertThat(stream.transport).isEqualTo(StreamTransport.PROVIDER_HTTP)
        assertThat(stream.isRadio).isFalse()
        assertThat(stream.routedDirect).isFalse()
        assertThat(stream.cacheKey).isEqualTo("stream:1:ext-1:auto:auto")
        assertThat(stream.startPositionMs).isEqualTo(0L)
    }

    @Test
    fun `unknown provider resolves to null`() = runTest {
        val resolver = ProviderStreamResolver({ null })

        assertThat(resolver.resolve(entry())).isNull()
    }

    @Test
    fun `missing url resolves to null`() = runTest {
        assertThat(resolver(null).resolve(entry())).isNull()
    }

    @Test
    fun `provider failure resolves to null`() = runTest {
        val provider = mockk<MediaProvider> {
            every { streamUrl(any(), any(), any()) } throws IOException("boom")
        }
        val resolver = ProviderStreamResolver({ provider })

        assertThat(resolver.resolve(entry())).isNull()
    }

    @Test
    fun `a resolved stream always starts at zero`() = runTest {
        val normal = resolver("http://host/s").resolve(entry())
        val radio = resolver("http://host/radio").resolve(entry(isRadioStation = true))
        val content = resolver("content://media/external/audio/1").resolve(entry())
        val unknownDuration = resolver("http://host/s").resolve(entry(durationMs = null))

        assertThat(normal!!.startPositionMs).isEqualTo(0L)
        assertThat(radio!!.startPositionMs).isEqualTo(0L)
        assertThat(content!!.startPositionMs).isEqualTo(0L)
        assertThat(unknownDuration!!.startPositionMs).isEqualTo(0L)
    }

    @Test
    fun `local content uris are direct`() = runTest {
        val stream = resolver("content://media/external/audio/1").resolve(entry())

        assertThat(stream!!.requiresNetwork).isFalse()
        assertThat(stream.transport).isEqualTo(StreamTransport.DIRECT_URI)
        assertThat(stream.routedDirect).isTrue()
    }

    @Test
    fun `radio entries report live`() = runTest {
        val stream = resolver("http://host/radio").resolve(entry(isRadioStation = true))

        assertThat(stream!!.isRadio).isTrue()
        assertThat(stream.startPositionMs).isEqualTo(0L)
        assertThat(stream.requiresNetwork).isTrue()
    }
}
