package app.omnisonic.playback.player

import androidx.media3.common.audio.AudioProcessor
import app.omnisonic.playback.audio.FadeAudioProcessor
import app.omnisonic.playback.audio.MonoAudioProcessor
import app.omnisonic.playback.audio.PeqAudioProcessor
import app.omnisonic.playback.audio.ReplayGainAudioProcessor

/**
 * Shared sink processor instances. Every engine built from the paired
 * [app.omnisonic.playback.api.player.AudioEngineFactory] must wire [toArray]
 * so sink configuration survives engine swaps.
 */
class SinkProcessorChain(
    val replayGain: ReplayGainAudioProcessor = ReplayGainAudioProcessor(),
    val peq: PeqAudioProcessor = PeqAudioProcessor(),
    val mono: MonoAudioProcessor = MonoAudioProcessor(),
    val fade: FadeAudioProcessor = FadeAudioProcessor(),
) {
    fun toArray(): Array<AudioProcessor> = arrayOf(replayGain, peq, mono, fade)
}
