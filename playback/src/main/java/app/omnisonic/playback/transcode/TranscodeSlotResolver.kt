package app.omnisonic.playback.transcode

import app.omnisonic.core.data.network.NetworkState
import app.omnisonic.core.data.transcode.TranscodeSettings

/**
 * Maps the current network snapshot onto a stream request slot (D-60). Pure
 * caller-side policy: [TranscodePlanner] only consumes the chosen slot, so
 * the Wi-Fi/cellular/download settings values stay independent (D-53). The
 * download slot is never produced here. User-confirmed defaults: a VPN
 * transport and a metered network take the cellular slot unless the
 * renderer's settings opt out. Anything not effectively Wi-Fi — including an
 * offline snapshot — takes the cellular slot, so one network's settings are
 * never applied where the other belongs.
 */
object TranscodeSlotResolver {

    fun streamSlot(network: NetworkState, settings: TranscodeSettings): TranscodeRequestKind {
        val wifiLike = network.wifi || (network.vpn && !settings.vpnAsCellular)
        val meteredDown = network.metered && settings.meteredAsCellular
        return if (wifiLike && !meteredDown) {
            TranscodeRequestKind.STREAM_WIFI
        } else {
            TranscodeRequestKind.STREAM_CELLULAR
        }
    }
}
