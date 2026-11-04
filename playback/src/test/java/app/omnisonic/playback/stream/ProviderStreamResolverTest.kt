package app.omnisonic.playback.stream

import app.omnisonic.core.data.db.entity.SongEntity
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

private fun songRow(resumePointMs: Long? = null) = SongEntity(
    providerId = 1,
    externalId = "ext-1",
    title = "T1",
    resumePointMs = resumePointMs,
    updatedAt = 0,
)

private class FixedRows(private val row: SongEntity?) : SongRowLookup {
    override suspend fun selectByProviderAndExternal(providerId: Long, externalId: String): SongEntity? = row
}

private fun resolver(uri: String?, row: SongEntity? = null): ProviderStreamResolver {
    val provider = mockk<MediaProvider> {
        every { streamUrl(any(), any(), any()) } returns uri
    }
    return ProviderStreamResolver({ provider }, FixedRows(row))
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
        val resolver = ProviderStreamResolver({ null }, FixedRows(songRow()))

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
        val resolver = ProviderStreamResolver({ provider }, FixedRows(null))

        assertThat(resolver.resolve(entry())).isNull()
    }

    @Test
    fun `seeds the start position from the song resume point`() = runTest {
        val stream = resolver("http://host/s", songRow(resumePointMs = 42_000)).resolve(entry())

        assertThat(stream!!.startPositionMs).isEqualTo(42_000L)
    }

    @Test
    fun `drops resume points at or past the end`() = runTest {
        val atEnd = resolver("http://host/s", songRow(resumePointMs = 200_000)).resolve(entry())
        val pastEnd = resolver("http://host/s", songRow(resumePointMs = 250_000)).resolve(entry())

        assertThat(atEnd!!.startPositionMs).isEqualTo(0L)
        assertThat(pastEnd!!.startPositionMs).isEqualTo(0L)
    }

    @Test
    fun `keeps a resume point when the duration is unknown`() = runTest {
        val stream = resolver("http://host/s", songRow(resumePointMs = 42_000))
            .resolve(entry(durationMs = null))

        assertThat(stream!!.startPositionMs).isEqualTo(42_000L)
    }

    @Test
    fun `local content uris are direct`() = runTest {
        val stream = resolver("content://media/external/audio/1").resolve(entry())

        assertThat(stream!!.requiresNetwork).isFalse()
        assertThat(stream.transport).isEqualTo(StreamTransport.DIRECT_URI)
        assertThat(stream.routedDirect).isTrue()
    }

    @Test
    fun `radio entries skip the resume point and report live`() = runTest {
        val stream = resolver("http://host/radio", songRow(resumePointMs = 42_000))
            .resolve(entry(isRadioStation = true))

        assertThat(stream!!.isRadio).isTrue()
        assertThat(stream.startPositionMs).isEqualTo(0L)
        assertThat(stream.requiresNetwork).isTrue()
    }
}
