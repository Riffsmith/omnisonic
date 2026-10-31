package app.omnisonic.playback.player

import androidx.media3.common.audio.AudioProcessor
import app.omnisonic.playback.audio.FadeAudioProcessor
import app.omnisonic.playback.audio.MonoAudioProcessor
import app.omnisonic.playback.audio.PeqAudioProcessor
import app.omnisonic.playback.audio.ReplayGainAudioProcessor
import app.omnisonic.playback.api.equalizer.PeqConfig

/**
 * Session-visible sink processor state. Re-applied to every chain the factory
 * produces so engine swaps keep settings, and pushed to the active chain on
 * change so runtime adjustments are immediate.
 */
data class ProcessorConfig(
    val replayGainLinear: Float = 1f,
    val duckLinear: Float = 1f,
    val monoEnabled: Boolean = false,
    val peq: PeqConfig = PeqConfig(),
)

/**
 * One stateful sink processor set for one engine. Instances are created only
 * through [SinkProcessorChainFactory]; two live engines never share a chain.
 */
class SinkProcessorChain(
    val replayGain: ReplayGainAudioProcessor = ReplayGainAudioProcessor(),
    val peq: PeqAudioProcessor = PeqAudioProcessor(),
    val mono: MonoAudioProcessor = MonoAudioProcessor(),
    val fade: FadeAudioProcessor = FadeAudioProcessor(),
) {
    fun toArray(): Array<AudioProcessor> = arrayOf(replayGain, peq, mono, fade)

    fun apply(config: ProcessorConfig) {
        replayGain.setLinearFactor(config.replayGainLinear)
        replayGain.setDuckFactor(config.duckLinear)
        mono.setEnabled(config.monoEnabled)
        peq.setPeqConfig(config.peq)
    }

    fun snapshotConfig(): ProcessorConfig = ProcessorConfig(
        replayGainLinear = replayGain.currentLinearFactor,
        duckLinear = replayGain.currentDuckFactor,
        monoEnabled = mono.isEnabled,
        peq = peq.currentConfig,
    )
}

/**
 * Produces a NEW [SinkProcessorChain] per engine plus the [ProcessorConfig]
 * that travels with each new chain. [updateConfig] also applies to the active
 * chain so settings changes are immediate; [create] publishes each chain as
 * [active] while the engine factory is building its sink.
 */
class SinkProcessorChainFactory(initialConfig: ProcessorConfig = ProcessorConfig()) {
    @Volatile var config: ProcessorConfig = initialConfig
        private set

    @Volatile var active: SinkProcessorChain? = null
        private set

    fun updateConfig(config: ProcessorConfig) {
        this.config = config
        active?.apply(config)
    }

    fun create(): SinkProcessorChain =
        SinkProcessorChain().apply { apply(config) }.also { active = it }
}
