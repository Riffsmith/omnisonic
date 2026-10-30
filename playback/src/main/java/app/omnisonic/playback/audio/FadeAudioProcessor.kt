package app.omnisonic.playback.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

class FadeAudioProcessor : BaseAudioProcessor() {
    private var configuredFormat: AudioProcessor.AudioFormat? = null
    private var fadeDurationMs = 0L
    private var fadingIn = true
    private var framesTotal = 0L
    private var framesRemaining = 0L

    fun beginFadeIn(durationMs: Long) {
        fadeDurationMs = durationMs.coerceAtLeast(0)
        fadingIn = true
        materialize()
    }

    fun beginFadeOut(durationMs: Long) {
        fadeDurationMs = durationMs.coerceAtLeast(0)
        fadingIn = false
        materialize()
    }

    fun isIdle(): Boolean = framesRemaining <= 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        PcmOps.requireSupported(inputAudioFormat)
        configuredFormat = inputAudioFormat
        if (framesTotal == 0L && fadeDurationMs > 0L) materialize()
        return inputAudioFormat
    }

    override fun isActive(): Boolean = framesRemaining > 0L

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = configuredFormat!!
        val encoding = format.encoding
        val channels = format.channelCount
        val bytesPerFrame = PcmOps.bytesPerSample(encoding) * channels
        val out = replaceOutputBuffer((inputBuffer.remaining() / bytesPerFrame) * bytesPerFrame)
        while (inputBuffer.remaining() >= bytesPerFrame) {
            val gain = if (framesRemaining > 0L) {
                val elapsed = framesTotal - framesRemaining
                if (fadingIn) elapsed.toFloat() / framesTotal.toFloat()
                else framesRemaining.toFloat() / framesTotal.toFloat()
            } else 1f
            for (channel in 0 until channels) {
                PcmOps.writeSample(out, encoding, PcmOps.readSample(inputBuffer, encoding) * gain)
            }
            if (framesRemaining > 0L) framesRemaining--
        }
        out.flip()
    }

    private fun materialize() {
        val format = configuredFormat ?: return
        framesTotal = fadeDurationMs * format.sampleRate / 1000
        framesRemaining = framesTotal
    }

    override fun onFlush() {
        cancelFade()
    }

    override fun onReset() {
        cancelFade()
    }

    private fun cancelFade() {
        framesTotal = 0L
        framesRemaining = 0L
    }
}
