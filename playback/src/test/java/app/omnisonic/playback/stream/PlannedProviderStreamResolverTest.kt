package app.omnisonic.playback.stream

import app.omnisonic.core.data.network.NetworkMonitor
import app.omnisonic.core.data.network.NetworkState
import app.omnisonic.core.data.transcode.TranscodeRendererId
import app.omnisonic.core.data.transcode.TranscodeSettings
import app.omnisonic.core.data.transcode.TranscodeSettingsStore
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.stream.StreamKey
import app.omnisonic.playback.api.stream.StreamTransport
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.ProviderTraits
import app.omnisonic.provider.api.TranscodeFallbackReason
import app.omnisonic.provider.api.TranscodeStreamResolution
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private fun plannedEntry(isRadioStation: Boolean = false) = PlaybackQueueEntry(
    providerId = 1,
    externalId = "ext-1",
    title = "T1",
    durationMs = 200_000L,
    isRadioStation = isRadioStation,
)

private data class TranscodeCall(
    val externalId: String,
    val maxBitRateKbps: Int?,
    val format: String?,
    val timeOffsetSeconds: Int?,
)

private class Harness(
    val calls: MutableList<TranscodeCall>,
    val resolver: PlannedProviderStreamResolver,
) {
    companion object {
        suspend fun create(
            settings: TranscodeSettings = TranscodeSettings(),
            network: NetworkState = NetworkState(connected = true, wifi = true, metered = false),
            traits: ProviderTraits = ProviderTraits(),
            streamUri: String? = "http://host/stream?id=ext-1",
            transcodeUrl: String? = "http://host/stream?id=ext-1&transcode=1",
            transcodeResult: TranscodeStreamResolution? = null,
            transcodeThrows: Boolean = false,
            providerAvailable: Boolean = true,
        ): Harness {
            val store = TranscodeSettingsStore.inMemory()
            store.setSettings(TranscodeRendererId.Local, settings)
            val calls = mutableListOf<TranscodeCall>()
            val provider = mockk<MediaProvider> {
                every { streamUrl(any(), any(), any()) } returns streamUri
                coEvery { resolveTranscodeStream(any(), any(), any(), any()) } answers {
                    calls += TranscodeCall(
                        arg<String>(0),
                        arg<Int?>(1),
                        arg<String?>(2),
                        arg<Int?>(3),
                    )
                    when {
                        transcodeThrows -> throw IOException("decision call failed")
                        transcodeResult != null -> transcodeResult
                        else -> TranscodeStreamResolution(
                            url = transcodeUrl,
                            serverTranscoded = true,
                            fallbackReason = null,
                        )
                    }
                }
            }
            val plans = StreamPlanSource(
                settings = store,
                network = NetworkMonitor { network },
                traits = ProviderTraitsLookup { traits },
            )
            val resolver = PlannedProviderStreamResolver(
                providers = MediaProviderLookup { if (providerAvailable) provider else null },
                plans = plans,
            )
            return Harness(calls, resolver)
        }
    }
}

class PlannedProviderStreamResolverTest {

    @Test
    fun `wifi slot sends the wifi bitrate through the transcode seam with a variant cache key`() = runTest {
        val h = Harness.create(
            settings = TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96),
            network = NetworkState(connected = true, wifi = true, metered = false),
        )

        val stream = h.resolver.resolve(plannedEntry())!!

        assertThat(h.calls).containsExactly(TranscodeCall("ext-1", 320, null, null))
        assertThat(stream.uri).isEqualTo("http://host/stream?id=ext-1&transcode=1")
        assertThat(stream.cacheKey).isEqualTo("stream:1:ext-1:320:auto:tx")
        assertThat(stream.startPositionMs).isEqualTo(0L)
        assertThat(stream.requiresNetwork).isTrue()
        assertThat(stream.transport).isEqualTo(StreamTransport.PROVIDER_HTTP)
        assertThat(stream.isRadio).isFalse()
    }

    @Test
    fun `cellular network picks the cellular bitrate`() = runTest {
        val h = Harness.create(
            settings = TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96),
            network = NetworkState(connected = true, wifi = false, metered = true),
        )

        h.resolver.resolve(plannedEntry())

        assertThat(h.calls.single().maxBitRateKbps).isEqualTo(96)
    }

    @Test
    fun `vpn takes the cellular slot only while vpnAsCellular is on`() = runTest {
        val vpn = NetworkState(connected = true, wifi = false, metered = false, vpn = true)
        val settings = TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96)

        val asCellular = Harness.create(settings = settings, network = vpn)
        asCellular.resolver.resolve(plannedEntry())
        assertThat(asCellular.calls.single().maxBitRateKbps).isEqualTo(96)

        val asWifi = Harness.create(
            settings = settings.copy(vpnAsCellular = false),
            network = vpn,
        )
        asWifi.resolver.resolve(plannedEntry())
        assertThat(asWifi.calls.single().maxBitRateKbps).isEqualTo(320)
    }

    @Test
    fun `metered wifi takes the cellular slot only while meteredAsCellular is on`() = runTest {
        val meteredWifi = NetworkState(connected = true, wifi = true, metered = true)
        val settings = TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96)

        val asCellular = Harness.create(settings = settings, network = meteredWifi)
        asCellular.resolver.resolve(plannedEntry())
        assertThat(asCellular.calls.single().maxBitRateKbps).isEqualTo(96)

        val asWifi = Harness.create(
            settings = settings.copy(meteredAsCellular = false),
            network = meteredWifi,
        )
        asWifi.resolver.resolve(plannedEntry())
        assertThat(asWifi.calls.single().maxBitRateKbps).isEqualTo(320)
    }

    @Test
    fun `a preferred format rides the request and the cache key`() = runTest {
        val h = Harness.create(
            settings = TranscodeSettings(wifiBitrateKbps = 320, preferredFormat = "opus"),
        )

        val stream = h.resolver.resolve(plannedEntry())!!

        assertThat(h.calls.single().format).isEqualTo("opus")
        assertThat(stream.cacheKey).isEqualTo("stream:1:ext-1:320:opus:tx")
    }

    @Test
    fun `no planned bitrate or format plays the plain stream directly`() = runTest {
        val h = Harness.create(settings = TranscodeSettings())

        val stream = h.resolver.resolve(plannedEntry())!!

        assertThat(h.calls).isEmpty()
        assertThat(stream.uri).isEqualTo("http://host/stream?id=ext-1")
        assertThat(stream.cacheKey).isEqualTo("stream:1:ext-1:auto:auto")
    }

    @Test
    fun `a refused transcode falls back to the plain stream`() = runTest {
        val h = Harness.create(
            settings = TranscodeSettings(mandatory = true),
            traits = ProviderTraits(supportsServerTranscode = false),
        )

        val stream = h.resolver.resolve(plannedEntry())!!

        assertThat(h.calls).isEmpty()
        assertThat(stream.uri).isEqualTo("http://host/stream?id=ext-1")
        assertThat(stream.cacheKey).isEqualTo("stream:1:ext-1:auto:auto")
    }

    @Test
    fun `a positive start position becomes a positive-only time offset and a zero stream start`() = runTest {
        val h = Harness.create(settings = TranscodeSettings(wifiBitrateKbps = 320))

        val stream = h.resolver.resolve(plannedEntry(), 65_400L)!!

        assertThat(h.calls.single().timeOffsetSeconds).isEqualTo(65)
        assertThat(stream.startPositionMs).isEqualTo(0L)
    }

    @Test
    fun `a sub-second or negative start position is not sent as an offset`() = runTest {
        val h = Harness.create(settings = TranscodeSettings(wifiBitrateKbps = 320))

        val subSecond = h.resolver.resolve(plannedEntry(), 500L)!!
        val negative = h.resolver.resolve(plannedEntry(), -1L)!!

        assertThat(h.calls.map { it.timeOffsetSeconds }).containsExactly(null, null)
        assertThat(subSecond.startPositionMs).isEqualTo(500L)
        assertThat(negative.startPositionMs).isEqualTo(0L)
    }

    @Test
    fun `a plain stream keeps the requested start position for the player to seek`() = runTest {
        val h = Harness.create(settings = TranscodeSettings())

        val stream = h.resolver.resolve(plannedEntry(), 42_000L)!!

        assertThat(stream.uri).isEqualTo("http://host/stream?id=ext-1")
        assertThat(stream.startPositionMs).isEqualTo(42_000L)
    }

    @Test
    fun `a transcode resolution without a url resolves to null`() = runTest {
        val h = Harness.create(
            settings = TranscodeSettings(wifiBitrateKbps = 320),
            transcodeResult = TranscodeStreamResolution(url = null, serverTranscoded = false, fallbackReason = null),
        )

        assertThat(h.resolver.resolve(plannedEntry())).isNull()
    }

    @Test
    fun `a server-default transcode gets its own cache identity distinct from the raw stream`() = runTest {
        val h = Harness.create(settings = TranscodeSettings(mandatory = true))

        val stream = h.resolver.resolve(plannedEntry())!!

        assertThat(h.calls.single()).isEqualTo(TranscodeCall("ext-1", null, null, null))
        assertThat(stream.cacheKey).isEqualTo("stream:1:ext-1:auto:auto:tx")
        assertThat(stream.cacheKey).isNotEqualTo(StreamKey(1, "ext-1").cacheKey)
    }

    @Test
    fun `a plain-stream fallback keeps the raw cache identity without the transcode marker`() = runTest {
        val h = Harness.create(
            settings = TranscodeSettings(wifiBitrateKbps = 320),
            transcodeResult = TranscodeStreamResolution(
                url = "http://host/stream?id=ext-1&maxBitRate=320",
                serverTranscoded = false,
                fallbackReason = TranscodeFallbackReason.EXTENSION_ABSENT,
            ),
        )

        val stream = h.resolver.resolve(plannedEntry())!!

        assertThat(stream.cacheKey).isEqualTo("stream:1:ext-1:320:auto")
        assertThat(stream.cacheKey).isNotEqualTo("stream:1:ext-1:320:auto:tx")
    }

    @Test
    fun `a failed transcode request falls back to the plain stream`() = runTest {
        val h = Harness.create(
            settings = TranscodeSettings(wifiBitrateKbps = 320),
            transcodeThrows = true,
        )

        val stream = h.resolver.resolve(plannedEntry())!!

        assertThat(stream.uri).isEqualTo("http://host/stream?id=ext-1")
        assertThat(stream.cacheKey).isEqualTo("stream:1:ext-1:auto:auto")
    }

    @Test
    fun `radio always plays the plain live stream without a transcode request`() = runTest {
        val h = Harness.create(
            settings = TranscodeSettings(wifiBitrateKbps = 320),
            streamUri = "http://host/radio",
        )

        val stream = h.resolver.resolve(plannedEntry(isRadioStation = true), 30_000L)!!

        assertThat(h.calls).isEmpty()
        assertThat(stream.uri).isEqualTo("http://host/radio")
        assertThat(stream.isRadio).isTrue()
        assertThat(stream.startPositionMs).isEqualTo(30_000L)
    }

    @Test
    fun `an unknown provider resolves to null without any request`() = runTest {
        val h = Harness.create(providerAvailable = false)

        assertThat(h.resolver.resolve(plannedEntry())).isNull()
        assertThat(h.calls).isEmpty()
    }

    @Test
    fun `a missing plain url resolves to null`() = runTest {
        val h = Harness.create(streamUri = null)

        assertThat(h.resolver.resolve(plannedEntry())).isNull()
    }

    @Test
    fun `a content uri resolves as a direct offline-capable source`() = runTest {
        val h = Harness.create(
            streamUri = "content://media/external/audio/1",
            traits = ProviderTraits(supportsServerTranscode = false),
        )

        val stream = h.resolver.resolve(plannedEntry(), 12_000L)!!

        assertThat(stream.routedDirect).isTrue()
        assertThat(stream.transport).isEqualTo(StreamTransport.DIRECT_URI)
        assertThat(stream.requiresNetwork).isFalse()
        assertThat(stream.startPositionMs).isEqualTo(12_000L)
    }
}
