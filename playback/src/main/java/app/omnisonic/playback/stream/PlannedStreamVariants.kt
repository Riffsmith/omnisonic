package app.omnisonic.playback.stream

import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.transcode.TranscodeRendererId
import app.omnisonic.core.data.transcode.TranscodeSettings
import app.omnisonic.core.data.transcode.TranscodeSettingsStore
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.transcode.RendererCapabilities
import app.omnisonic.playback.transcode.TranscodeDecision
import app.omnisonic.playback.transcode.TranscodePlanInput
import app.omnisonic.playback.transcode.TranscodePlanner
import app.omnisonic.playback.transcode.TranscodeRequestKind
import app.omnisonic.playback.transcode.TranscodeSource
import app.omnisonic.provider.api.ProviderTraits
import javax.inject.Inject

/**
 * Turns the local renderer's transcode settings into the desired offline
 * variant (D-56). Until network-aware resolution exists (TODO 5), a variant is
 * desired only when both stream slots plan the same outcome; differing Wi-Fi
 * and cellular values yield no exact preference rather than silently applying
 * one network's settings to the other. Provider traits are the permissive
 * default on purpose: a stored offline variant stays selectable even when the
 * provider could not transcode it today.
 */
class PlannedStreamVariants @Inject constructor(
    private val settings: TranscodeSettingsStore,
) : DesiredStreamVariants {

    override suspend fun desiredKey(entry: PlaybackQueueEntry): String? {
        val current = settings.current(TranscodeRendererId.Local)
        val wifi = plan(current, TranscodeRequestKind.STREAM_WIFI)
        if (wifi != plan(current, TranscodeRequestKind.STREAM_CELLULAR)) return null
        return when (wifi) {
            TranscodeDecision.DirectPlay ->
                DownloadKeys.mediaKey(entry.providerId, entry.externalId, null, null)
            is TranscodeDecision.ServerTranscode ->
                if (wifi.maxBitRateKbps == null && wifi.format == null) {
                    null
                } else {
                    DownloadKeys.mediaKey(entry.providerId, entry.externalId, wifi.maxBitRateKbps, wifi.format)
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
