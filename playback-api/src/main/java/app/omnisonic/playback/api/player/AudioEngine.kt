package app.omnisonic.playback.api.player

enum class DecoderPreference { MEDIA_FIRST, EXTENSION_FIRST }

/** Immutable per-engine construction parameters. */
data class EngineConfig(
    val bufferForPlaybackMs: Int = 1_500,
    val bufferForPlaybackAfterRebufferMs: Int = 5_000,
    val minBufferMs: Int = 60_000,
    val maxBufferMs: Int = 60_000,
    val decoderPreference: DecoderPreference = DecoderPreference.MEDIA_FIRST,
    val offloadMode: OffloadMode = OffloadMode.DISABLED,
)

enum class OffloadMode { DISABLED, AUTO, ENABLED }

data class EngineInfo(
    val config: EngineConfig,
    val loudnessCodecAttached: Boolean,
)

/** One wrapped engine instance; a handle above media3, below OmniPlayer. */
interface AudioEngine {
    /** Engine viewer/aggregator handle both OmniPlayer and the session layer.
     * Always the same instance for this engine's lifetime. */
    val player: androidx.media3.common.Player

    fun info(): EngineInfo

    fun release()
}

interface AudioEngineFactory {
    fun create(config: EngineConfig): AudioEngine
}
