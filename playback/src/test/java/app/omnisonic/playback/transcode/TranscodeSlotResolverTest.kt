package app.omnisonic.playback.transcode

import app.omnisonic.core.data.network.NetworkState
import app.omnisonic.core.data.transcode.TranscodeSettings
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TranscodeSlotResolverTest {

    private val wifi = NetworkState(connected = true, wifi = true)
    private val meteredWifi = NetworkState(connected = true, wifi = true, metered = true)
    private val cellular = NetworkState(connected = true, wifi = false)
    private val meteredCellular = NetworkState(connected = true, wifi = false, metered = true)
    private val vpn = NetworkState(connected = true, wifi = false, vpn = true)
    private val meteredVpn = NetworkState(connected = true, wifi = false, metered = true, vpn = true)
    private val wifiAndVpn = NetworkState(connected = true, wifi = true, vpn = true)
    private val offline = NetworkState(connected = false, wifi = false)

    private fun slot(
        network: NetworkState,
        settings: TranscodeSettings = TranscodeSettings(),
    ): TranscodeRequestKind = TranscodeSlotResolver.streamSlot(network, settings)

    @Test
    fun `unmetered wifi takes the wifi slot`() {
        assertThat(slot(wifi)).isEqualTo(TranscodeRequestKind.STREAM_WIFI)
    }

    @Test
    fun `metered wifi takes the cellular slot by default`() {
        assertThat(slot(meteredWifi)).isEqualTo(TranscodeRequestKind.STREAM_CELLULAR)
    }

    @Test
    fun `metered wifi takes the wifi slot when the renderer opts out`() {
        val settings = TranscodeSettings(meteredAsCellular = false)

        assertThat(slot(meteredWifi, settings)).isEqualTo(TranscodeRequestKind.STREAM_WIFI)
    }

    @Test
    fun `cellular takes the cellular slot`() {
        assertThat(slot(cellular)).isEqualTo(TranscodeRequestKind.STREAM_CELLULAR)
    }

    @Test
    fun `metered cellular takes the cellular slot even when metered is opted out`() {
        val settings = TranscodeSettings(meteredAsCellular = false)

        assertThat(slot(meteredCellular, settings)).isEqualTo(TranscodeRequestKind.STREAM_CELLULAR)
    }

    @Test
    fun `a vpn network takes the cellular slot by default`() {
        assertThat(slot(vpn)).isEqualTo(TranscodeRequestKind.STREAM_CELLULAR)
    }

    @Test
    fun `a vpn network takes the wifi slot when the renderer opts out`() {
        val settings = TranscodeSettings(vpnAsCellular = false)

        assertThat(slot(vpn, settings)).isEqualTo(TranscodeRequestKind.STREAM_WIFI)
    }

    @Test
    fun `a metered vpn keeps the cellular slot when only the vpn toggle is off`() {
        val settings = TranscodeSettings(vpnAsCellular = false)

        assertThat(slot(meteredVpn, settings)).isEqualTo(TranscodeRequestKind.STREAM_CELLULAR)
    }

    @Test
    fun `a metered vpn takes the wifi slot only when both toggles opt out`() {
        val settings = TranscodeSettings(vpnAsCellular = false, meteredAsCellular = false)

        assertThat(slot(meteredVpn, settings)).isEqualTo(TranscodeRequestKind.STREAM_WIFI)
    }

    @Test
    fun `an explicit wifi transport wins over the vpn default`() {
        assertThat(slot(wifiAndVpn)).isEqualTo(TranscodeRequestKind.STREAM_WIFI)
    }

    @Test
    fun `an offline snapshot takes the cellular slot`() {
        assertThat(slot(offline)).isEqualTo(TranscodeRequestKind.STREAM_CELLULAR)
    }

    @Test
    fun `the slot does not depend on the bitrate values themselves`() {
        val settings = TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 64)

        assertThat(slot(wifi, settings)).isEqualTo(TranscodeRequestKind.STREAM_WIFI)
        assertThat(slot(meteredWifi, settings)).isEqualTo(TranscodeRequestKind.STREAM_CELLULAR)
        assertThat(slot(cellular, settings)).isEqualTo(TranscodeRequestKind.STREAM_CELLULAR)
    }

    @Test
    fun `the resolver never produces the download slot and is deterministic`() {
        val networks = listOf(wifi, meteredWifi, cellular, meteredCellular, vpn, meteredVpn, wifiAndVpn, offline)
        val settings = listOf(
            TranscodeSettings(),
            TranscodeSettings(vpnAsCellular = false),
            TranscodeSettings(meteredAsCellular = false),
            TranscodeSettings(vpnAsCellular = false, meteredAsCellular = false),
        )

        for (network in networks) {
            for (setting in settings) {
                val first = slot(network, setting)
                assertThat(first).isNotEqualTo(TranscodeRequestKind.DOWNLOAD)
                assertThat(slot(network, setting)).isEqualTo(first)
            }
        }
    }
}
