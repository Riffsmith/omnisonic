package app.omnisonic.playback.stream

import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.network.NetworkMonitor
import app.omnisonic.core.data.transcode.TranscodeRendererId
import app.omnisonic.core.data.transcode.TranscodeSettings
import app.omnisonic.core.data.transcode.TranscodeSettingsStore
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.transcode.RendererCapabilities
import app.omnisonic.playback.transcode.TranscodeDecision
import app.omnisonic.playback.transcode.TranscodePlanInput
import app.omnisonic.playback.transcode.TranscodePlanner
import app.omnisonic.playback.transcode.TranscodeRequestKind
import app.omnisonic.playback.transcode.TranscodeSlotResolver
import app.omnisonic.playback.transcode.TranscodeSource
import app.omnisonic.provider.api.ProviderTraits
import javax.inject.Inject

/**
 * Turns the local renderer's transcode settings into the desired offline
 * variant (D-56, D-60). The current network snapshot picks the stream slot
 * through [TranscodeSlotResolver], and only that slot's bitrate feeds the
 * plan, so Wi-Fi/cellular/download values never leak into each other and one
 * renderer's settings never leak into another. Provider traits are the
 * permissive default on purpose: a stored offline variant stays selectable
 * even when the provider could not transcode it today.
 *
 * The queue entry carries no source metadata and the device-reported
 * sample-rate limit is not read here (D-59), so both planner inputs stay
 * unknown on purpose; the device-limit rule therefore cannot force an offline
 * variant at this seam, and a server-default plan keeps no exact preference
 * (D-57).
 */
class PlannedStreamVariants @Inject constructor(
    private val settings: TranscodeSettingsStore,
    private val network: NetworkMonitor,
) : DesiredStreamVariants {

    override suspend fun desiredKey(entry: PlaybackQueueEntry): String? {
        val current = settings.current(TranscodeRendererId.Local)
        val slot = TranscodeSlotResolver.streamSlot(network.current(), current)
        return when (val decision = plan(current, slot)) {
            TranscodeDecision.DirectPlay ->
                DownloadKeys.mediaKey(entry.providerId, entry.externalId, null, null)
            is TranscodeDecision.ServerTranscode ->
                if (decision.maxBitRateKbps == null && decision.format == null) {
                    null
                } else {
                    DownloadKeys.mediaKey(
                        entry.providerId,
                        entry.externalId,
                        decision.maxBitRateKbps,
                        decision.format,
                    )
                }
            is TranscodeDecision.Refused -> null
        }
    }

    private fun plan(settings: TranscodeSettings, kind: TranscodeRequestKind): TranscodeDecision =
        TranscodePlanner.plan(
            TranscodePlanInput(
                source = TranscodeSource(format = null),
                renderer = RendererCapabilities(supportedFormats = null),
                settings = settings,
                providerTraits = ProviderTraits(),
                requestKind = kind,
            ),
        )
}
