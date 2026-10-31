package app.omnisonic.playback.api.player

enum class DecoderPreference { MEDIA_FIRST, EXTENSION_FIRST }

enum class WakeMode { LOCAL, NETWORK }

/** Immutable per-engine construction parameters. */
data class EngineConfig(
    val bufferForPlaybackMs: Int = 1_500,
    val bufferForPlaybackAfterRebufferMs: Int = 5_000,
    val minBufferMs: Int = 60_000,
    val maxBufferMs: Int = 60_000,
    val decoderPreference: DecoderPreference = DecoderPreference.MEDIA_FIRST,
    val offloadMode: OffloadMode = OffloadMode.DISABLED,
    val wakeMode: WakeMode = WakeMode.NETWORK,
)

enum class OffloadMode { DISABLED, AUTO, ENABLED }

data class EngineInfo(
    val config: EngineConfig,
    val loudnessCodecAttached: Boolean,
    /**
     * A decoder extension renderer is on the classpath and usable. Without one,
     * the configured decoder preference cannot change renderer selection.
     */
    val extensionAvailable: Boolean = false,
    /**
     * The requested [EngineConfig.offloadMode] survived engine construction.
     * Offload bypasses sink audio processors, so it is disabled automatically
     * while any processor is wired; engagement additionally requires the track
     * selection layer to prefer offload.
     */
    val offloadEffective: Boolean = false,
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
