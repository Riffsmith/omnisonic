package app.omnisonic.playback.transcode

import app.omnisonic.core.data.transcode.TranscodeSettings
import app.omnisonic.provider.api.ProviderTraits

/**
 * Which per-renderer bitrate slot a plan is computed for. Mapping the current
 * network onto [STREAM_WIFI]/[STREAM_CELLULAR] is a later task; the planner only
 * consumes the already-chosen slot, so the three settings values stay independent.
 */
enum class TranscodeRequestKind { STREAM_WIFI, STREAM_CELLULAR, DOWNLOAD }

/** Source-side facts the planner consumes; every field may be absent. */
data class TranscodeSource(
    val format: String?,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
)

/**
 * Renderer/device capability input. `supportedFormats == null` means the
 * capability is unknown, so no mismatch can be proven. The device sample-rate
 * limit is a separate later policy and is deliberately not read here.
 */
data class RendererCapabilities(
    val supportedFormats: Set<String>?,
)

data class TranscodePlanInput(
    val source: TranscodeSource,
    val renderer: RendererCapabilities,
    val settings: TranscodeSettings,
    val providerTraits: ProviderTraits,
    val requestKind: TranscodeRequestKind,
)

enum class TranscodeRefusalReason {
    /** A transcode is required or explicitly requested, but the provider cannot server-transcode. */
    PROVIDER_CANNOT_TRANSCODE,

    /** The renderer cannot play the source and the provider cannot server-transcode. */
    RENDERER_CANNOT_PLAY_SOURCE,
}

sealed interface TranscodeDecision {
    data object DirectPlay : TranscodeDecision

    /** Explicit server-side parameters; a null field means "server default" (D-53). */
    data class ServerTranscode(
        val maxBitRateKbps: Int?,
        val format: String?,
    ) : TranscodeDecision

    data class Refused(val reason: TranscodeRefusalReason) : TranscodeDecision
}

/**
 * One pure, deterministic function over source metadata, renderer capabilities,
 * the current per-renderer settings and runtime provider traits (D-53). No I/O,
 * clock, randomness, persisted capability flags, URLs or credentials.
 */
object TranscodePlanner {

    fun plan(input: TranscodePlanInput): TranscodeDecision {
        val requestedBitrate = input.settings.bitrateFor(input.requestKind)
        val format = input.settings.preferredFormat
        val rendererCanPlay = rendererCanPlay(input.source, input.renderer)
        val needsTranscode = input.settings.mandatory ||
            !rendererCanPlay ||
            requestedBitrate != null ||
            format != null
        if (!needsTranscode) return TranscodeDecision.DirectPlay
        if (!input.providerTraits.supportsServerTranscode) {
            return TranscodeDecision.Refused(
                if (rendererCanPlay) TranscodeRefusalReason.PROVIDER_CANNOT_TRANSCODE
                else TranscodeRefusalReason.RENDERER_CANNOT_PLAY_SOURCE,
            )
        }
        return TranscodeDecision.ServerTranscode(maxBitRateKbps = requestedBitrate, format = format)
    }

    private fun TranscodeSettings.bitrateFor(kind: TranscodeRequestKind): Int? = when (kind) {
        TranscodeRequestKind.STREAM_WIFI -> wifiBitrateKbps
        TranscodeRequestKind.STREAM_CELLULAR -> cellularBitrateKbps
        TranscodeRequestKind.DOWNLOAD -> downloadBitrateKbps
    }

    private fun rendererCanPlay(source: TranscodeSource, renderer: RendererCapabilities): Boolean {
        val supported = renderer.supportedFormats ?: return true
        val format = source.format?.let(::normalizeFormat) ?: return supported.isNotEmpty()
        return format in supported.mapTo(mutableSetOf(), ::normalizeFormat)
    }

    private fun normalizeFormat(value: String): String = value.trim().lowercase()
}
