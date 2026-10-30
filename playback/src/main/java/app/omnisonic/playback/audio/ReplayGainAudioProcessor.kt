package app.omnisonic.playback.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

class ReplayGainAudioProcessor : BaseAudioProcessor() {
    private var configuredFormat: AudioProcessor.AudioFormat? = null
    private var linearFactor = 1f
    private var duckFactor = 1f

    fun setLinearFactor(factor: Float) {
        linearFactor = factor
    }

    fun setDuckFactor(factor: Float) {
        duckFactor = factor.coerceIn(0f, 1f)
    }

    fun isNeutral(): Boolean = linearFactor == 1f && duckFactor == 1f

    internal val currentLinearFactor: Float
        get() = linearFactor

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        PcmOps.requireSupported(inputAudioFormat)
        configuredFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = configuredFormat != null && !isNeutral()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val out = replaceOutputBuffer(inputBuffer.remaining())
        out.put(inputBuffer).flip()
        val factor = linearFactor * duckFactor
        if (factor != 1f) {
            PcmOps.forEachSample(out, configuredFormat!!.encoding) { sample -> sample * factor }
            out.position(0)
        }
    }
}
