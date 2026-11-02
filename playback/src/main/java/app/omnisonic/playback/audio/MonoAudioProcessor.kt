package app.omnisonic.playback.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Stereo-to-mono downmix. Stays active after the first configure and passes
 * non-stereo or disabled input through unchanged, so the toggle takes effect
 * on subsequent samples without a sink reconfigure.
 */
@UnstableApi
class MonoAudioProcessor : BaseAudioProcessor() {
    private var configuredFormat: AudioProcessor.AudioFormat? = null
    private var enabled = false

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    internal val isEnabled: Boolean
        get() = enabled

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        PcmOps.requireSupported(inputAudioFormat)
        configuredFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = configuredFormat != null

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputBuffer.remaining() == 0) return
        val encoding = configuredFormat!!.encoding
        if (!enabled || configuredFormat!!.channelCount != 2) {
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
            return
        }
        val bytesPerFrame = PcmOps.bytesPerSample(encoding) * 2
        val out = replaceOutputBuffer((inputBuffer.remaining() / bytesPerFrame) * bytesPerFrame)
        while (inputBuffer.remaining() >= bytesPerFrame) {
            val left = PcmOps.readSample(inputBuffer, encoding)
            val right = PcmOps.readSample(inputBuffer, encoding)
            val mixed = (left + right) / 2f
            PcmOps.writeSample(out, encoding, mixed)
            PcmOps.writeSample(out, encoding, mixed)
        }
        out.flip()
    }
}
