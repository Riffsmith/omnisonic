package app.omnisonic.playback.stream

import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.stream.StreamTransport
import app.omnisonic.provider.api.MediaProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private const val AUTO_KEY = "stream:1:song-1:auto:auto"

private fun entry(isRadioStation: Boolean = false) = PlaybackQueueEntry(
    providerId = 1,
    externalId = "song-1",
    title = "Queue Title",
    artistName = "Artist",
    coverImageId = "img-1",
    durationMs = 200_000,
    isRadioStation = isRadioStation,
)

private fun candidate(
    id: Long,
    key: String = AUTO_KEY,
    uri: String = "file:///data/offline/$id.mp3",
    downloadedAtMs: Long? = 1_000,
) = OfflineStreamCandidate(id, key, uri, downloadedAtMs)

private fun onlineResolver(uri: String? = "http://host/stream?id=song-1"): ProviderStreamResolver {
    val provider = mockk<MediaProvider> {
        every { streamUrl(any(), any(), any()) } returns uri
    }
    return ProviderStreamResolver({ provider })
}

private fun resolver(
    candidates: List<OfflineStreamCandidate> = emptyList(),
    online: ProviderStreamResolver = onlineResolver(),
    desired: String? = AUTO_KEY,
) = OfflineFirstStreamResolver(online, { _, _ -> candidates }, { desired })

class OfflineFirstStreamResolverTest {

    @Test
    fun `the exact desired variant wins over a newer fallback variant`() = runTest {
        val exact = candidate(id = 1, key = AUTO_KEY, uri = "file:///data/exact.mp3", downloadedAtMs = 1_000)
        val newer = candidate(
            id = 2,
            key = "stream:1:song-1:320:mp3",
            uri = "file:///data/newer.mp3",
            downloadedAtMs = 9_000,
        )

        val stream = resolver(listOf(newer, exact)).resolve(entry())!!

        assertThat(stream.uri).isEqualTo(exact.uri)
        assertThat(stream.cacheKey).isEqualTo(AUTO_KEY)
    }

    @Test
    fun `without an exact variant the newest readable file wins and ties pick the highest id`() = runTest {
        val old = candidate(id = 1, key = "stream:1:song-1:128:mp3", downloadedAtMs = 5_000)
        val tieLow = candidate(id = 2, key = "stream:1:song-1:192:mp3", downloadedAtMs = 8_000)
        val tieHigh = candidate(id = 3, key = "stream:1:song-1:320:mp3", downloadedAtMs = 8_000)
        val undated = candidate(id = 9, key = "stream:1:song-1:auto:flac", downloadedAtMs = null)

        val stream = resolver(listOf(old, tieLow, undated, tieHigh)).resolve(entry())!!

        assertThat(stream.uri).isEqualTo(tieHigh.uri)
        assertThat(stream.cacheKey).isEqualTo("stream:1:song-1:320:mp3")
    }

    @Test
    fun `an offline file plays even when the provider is unavailable`() = runTest {
        val file = candidate(id = 1)

        val stream = resolver(listOf(file), onlineResolver(uri = null)).resolve(entry())!!

        assertThat(stream.uri).isEqualTo(file.uri)
        assertThat(stream.requiresNetwork).isFalse()
    }

    @Test
    fun `offline resolution is a direct source at zero with the file identity`() = runTest {
        val file = candidate(id = 7, key = "stream:1:song-1:192:opus")

        val stream = resolver(listOf(file)).resolve(entry())!!

        assertThat(stream.uri).isEqualTo(file.uri)
        assertThat(stream.routedDirect).isTrue()
        assertThat(stream.transport).isEqualTo(StreamTransport.DIRECT_URI)
        assertThat(stream.requiresNetwork).isFalse()
        assertThat(stream.isRadio).isFalse()
        assertThat(stream.startPositionMs).isEqualTo(0L)
        assertThat(stream.cacheKey).isEqualTo(file.sourceKey)
        assertThat(stream.expiresAtMs).isNull()
    }

    @Test
    fun `the resolved stream keeps the queue entry metadata`() = runTest {
        val queued = entry()

        val stream = resolver(listOf(candidate(id = 1))).resolve(queued)!!

        assertThat(stream.entry).isSameInstanceAs(queued)
        assertThat(stream.entry.title).isEqualTo("Queue Title")
        assertThat(stream.entry.coverImageId).isEqualTo("img-1")
    }

    @Test
    fun `no offline file falls back to the provider stream`() = runTest {
        val stream = resolver().resolve(entry())!!

        assertThat(stream.uri).isEqualTo("http://host/stream?id=song-1")
        assertThat(stream.requiresNetwork).isTrue()
        assertThat(stream.transport).isEqualTo(StreamTransport.PROVIDER_HTTP)
    }

    @Test
    fun `no offline file and no provider resolves to null instead of an empty queue`() = runTest {
        assertThat(resolver(online = onlineResolver(uri = null)).resolve(entry())).isNull()
    }

    @Test
    fun `a lookup failure falls back to the provider instead of failing playback`() = runTest {
        val resolver = OfflineFirstStreamResolver(
            onlineResolver(),
            { _, _ -> throw IOException("db closed") },
            { AUTO_KEY },
        )

        val stream = resolver.resolve(entry())!!

        assertThat(stream.uri).isEqualTo("http://host/stream?id=song-1")
        assertThat(stream.requiresNetwork).isTrue()
    }

    @Test
    fun `radio stays live even when a stored file exists`() = runTest {
        val stream = resolver(listOf(candidate(id = 1))).resolve(entry(isRadioStation = true))!!

        assertThat(stream.uri).isEqualTo("http://host/stream?id=song-1")
        assertThat(stream.isRadio).isTrue()
        assertThat(stream.requiresNetwork).isTrue()
    }

    @Test
    fun `the planned transcode variant wins over a newer auto variant`() = runTest {
        val planned = candidate(
            id = 1,
            key = "stream:1:song-1:96:mp3",
            uri = "file:///data/planned.mp3",
            downloadedAtMs = 1_000,
        )
        val newerAuto = candidate(id = 2, key = AUTO_KEY, uri = "file:///data/auto.mp3", downloadedAtMs = 9_000)

        val stream = resolver(listOf(newerAuto, planned), desired = "stream:1:song-1:96:mp3").resolve(entry())!!

        assertThat(stream.uri).isEqualTo(planned.uri)
        assertThat(stream.cacheKey).isEqualTo("stream:1:song-1:96:mp3")
        assertThat(stream.requiresNetwork).isFalse()
    }

    @Test
    fun `an undetermined desired variant keeps the newest readable fallback`() = runTest {
        val older = candidate(id = 1, key = "stream:1:song-1:96:mp3", downloadedAtMs = 1_000)
        val newer = candidate(id = 2, key = "stream:1:song-1:128:opus", downloadedAtMs = 9_000)

        val stream = resolver(listOf(older, newer), desired = null).resolve(entry())!!

        assertThat(stream.uri).isEqualTo(newer.uri)
        assertThat(stream.cacheKey).isEqualTo("stream:1:song-1:128:opus")
    }

    @Test
    fun `a desired variant failure falls back to the newest readable file`() = runTest {
        val file = candidate(id = 4, key = "stream:1:song-1:96:mp3")
        val resolver = OfflineFirstStreamResolver(
            onlineResolver(),
            { _, _ -> listOf(file) },
            { throw IOException("settings unreadable") },
        )

        val stream = resolver.resolve(entry())!!

        assertThat(stream.uri).isEqualTo(file.uri)
        assertThat(stream.cacheKey).isEqualTo(file.sourceKey)
    }
}
