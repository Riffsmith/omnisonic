package app.omnisonic.playback.stream

import app.omnisonic.core.data.network.NetworkMonitor
import app.omnisonic.core.data.network.NetworkState
import app.omnisonic.core.data.transcode.TranscodeRendererId
import app.omnisonic.core.data.transcode.TranscodeSettings
import app.omnisonic.core.data.transcode.TranscodeSettingsStore
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private fun variantEntry() = PlaybackQueueEntry(
    providerId = 1,
    externalId = "song-1",
    title = "Queue Title",
    artistName = "Artist",
    coverImageId = "img-1",
    durationMs = 200_000,
    isRadioStation = false,
)

private class FakeNetwork(var state: NetworkState) : NetworkMonitor {
    override fun current(): NetworkState = state
}

private val WIFI = NetworkState(connected = true, wifi = true)
private val CELLULAR = NetworkState(connected = true, wifi = false)
private val VPN = NetworkState(connected = true, wifi = false, vpn = true)
private val METERED_WIFI = NetworkState(connected = true, wifi = true, metered = true)
private val OFFLINE = NetworkState(connected = false, wifi = false)

private suspend fun selectorWith(
    settings: TranscodeSettings,
    network: FakeNetwork = FakeNetwork(WIFI),
): PlannedStreamVariants {
    val store = TranscodeSettingsStore.inMemory()
    store.setSettings(TranscodeRendererId.Local, settings)
    return PlannedStreamVariants(store, network)
}

class PlannedStreamVariantsTest {

    @Test
    fun `all off desires the auto variant`() = runTest {
        val key = selectorWith(TranscodeSettings()).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:auto:auto")
        Unit
    }

    @Test
    fun `wifi planning carries the wifi value`() = runTest {
        val settings = TranscodeSettings(wifiBitrateKbps = 96, cellularBitrateKbps = 128)

        val key = selectorWith(settings, FakeNetwork(WIFI)).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:96:auto")
        Unit
    }

    @Test
    fun `cellular planning carries the cellular value`() = runTest {
        val settings = TranscodeSettings(wifiBitrateKbps = 96, cellularBitrateKbps = 128)

        val key = selectorWith(settings, FakeNetwork(CELLULAR)).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:128:auto")
        Unit
    }

    @Test
    fun `a network transition re-plans with the new slot without leaking the other value`() = runTest {
        val settings = TranscodeSettings(wifiBitrateKbps = 96, cellularBitrateKbps = 128)
        val network = FakeNetwork(WIFI)
        val selector = selectorWith(settings, network)

        assertThat(selector.desiredKey(variantEntry())).isEqualTo("stream:1:song-1:96:auto")
        network.state = CELLULAR
        assertThat(selector.desiredKey(variantEntry())).isEqualTo("stream:1:song-1:128:auto")
        network.state = WIFI
        assertThat(selector.desiredKey(variantEntry())).isEqualTo("stream:1:song-1:96:auto")
        Unit
    }

    @Test
    fun `a vpn network plans the cellular slot by default`() = runTest {
        val settings = TranscodeSettings(wifiBitrateKbps = 96, cellularBitrateKbps = 128)

        val key = selectorWith(settings, FakeNetwork(VPN)).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:128:auto")
        Unit
    }

    @Test
    fun `a vpn network plans the wifi slot when the renderer opts out`() = runTest {
        val settings = TranscodeSettings(wifiBitrateKbps = 96, cellularBitrateKbps = 128, vpnAsCellular = false)

        val key = selectorWith(settings, FakeNetwork(VPN)).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:96:auto")
        Unit
    }

    @Test
    fun `a metered wifi network plans the cellular slot by default`() = runTest {
        val settings = TranscodeSettings(wifiBitrateKbps = 96, cellularBitrateKbps = 128)

        val key = selectorWith(settings, FakeNetwork(METERED_WIFI)).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:128:auto")
        Unit
    }

    @Test
    fun `a metered wifi network plans the wifi slot when the renderer opts out`() = runTest {
        val settings = TranscodeSettings(
            wifiBitrateKbps = 96,
            cellularBitrateKbps = 128,
            meteredAsCellular = false,
        )

        val key = selectorWith(settings, FakeNetwork(METERED_WIFI)).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:96:auto")
        Unit
    }

    @Test
    fun `an offline snapshot plans the cellular slot`() = runTest {
        val settings = TranscodeSettings(wifiBitrateKbps = 96, cellularBitrateKbps = 128)

        val key = selectorWith(settings, FakeNetwork(OFFLINE)).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:128:auto")
        Unit
    }

    @Test
    fun `a format preference without a bitrate desires the format variant`() = runTest {
        val key = selectorWith(TranscodeSettings(preferredFormat = "mp3")).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:auto:mp3")
        Unit
    }

    @Test
    fun `a shared bitrate and format desire the fully qualified variant`() = runTest {
        val settings = TranscodeSettings(
            wifiBitrateKbps = 96,
            cellularBitrateKbps = 96,
            preferredFormat = "mp3",
        )

        val key = selectorWith(settings).desiredKey(variantEntry())

        assertThat(key).isEqualTo("stream:1:song-1:96:mp3")
        Unit
    }

    @Test
    fun `mandatory alone does not alias the raw auto variant`() = runTest {
        val selector = selectorWith(TranscodeSettings(mandatory = true))

        assertThat(selector.desiredKey(variantEntry())).isNull()
        Unit
    }

    @Test
    fun `the download slot never leaks into stream variant selection`() = runTest {
        val settings = TranscodeSettings(downloadBitrateKbps = 320)
        val network = FakeNetwork(WIFI)
        val selector = selectorWith(settings, network)

        assertThat(selector.desiredKey(variantEntry())).isEqualTo("stream:1:song-1:auto:auto")
        network.state = CELLULAR
        assertThat(selector.desiredKey(variantEntry())).isEqualTo("stream:1:song-1:auto:auto")
        Unit
    }

    @Test
    fun `network transitions never leak another renderer's settings`() = runTest {
        val store = TranscodeSettingsStore.inMemory()
        val other = TranscodeRendererId("test-renderer")
        val otherSettings = TranscodeSettings(wifiBitrateKbps = 32, cellularBitrateKbps = 64)
        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(wifiBitrateKbps = 96, cellularBitrateKbps = 128),
        )
        store.setSettings(other, otherSettings)
        val network = FakeNetwork(WIFI)
        val selector = PlannedStreamVariants(store, network)

        assertThat(selector.desiredKey(variantEntry())).isEqualTo("stream:1:song-1:96:auto")
        network.state = CELLULAR
        assertThat(selector.desiredKey(variantEntry())).isEqualTo("stream:1:song-1:128:auto")
        network.state = VPN
        assertThat(selector.desiredKey(variantEntry())).isEqualTo("stream:1:song-1:128:auto")
        assertThat(store.current(other)).isEqualTo(otherSettings)
        Unit
    }
}
