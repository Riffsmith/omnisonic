package app.omnisonic.playback.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import app.omnisonic.playback.api.equalizer.PeqBand
import app.omnisonic.playback.api.equalizer.PeqBandType
import app.omnisonic.playback.api.equalizer.PeqConfig
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class PeqAudioProcessor : BaseAudioProcessor() {
    private var configuredFormat: AudioProcessor.AudioFormat? = null
    private var config: PeqConfig = PeqConfig()
    private var bands: List<BandFilter> = emptyList()
    private var configuredSampleRate = -1

    fun setPeqConfig(newConfig: PeqConfig) {
        if (newConfig == config) return
        config = newConfig
        rebuild()
    }

    internal val currentConfig: PeqConfig
        get() = config

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        PcmOps.requireSupported(inputAudioFormat)
        if (inputAudioFormat.channelCount > MAX_CHANNELS) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        configuredFormat = inputAudioFormat
        if (inputAudioFormat.sampleRate != configuredSampleRate) {
            configuredSampleRate = inputAudioFormat.sampleRate
            rebuild()
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        val format = configuredFormat ?: return false
        return format.channelCount <= MAX_CHANNELS &&
            (config.bands.isNotEmpty() || config.preampDb != 0f)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = configuredFormat!!
        val encoding = format.encoding
        val channels = format.channelCount
        val bytesPerFrame = PcmOps.bytesPerSample(encoding) * channels
        val out = replaceOutputBuffer((inputBuffer.remaining() / bytesPerFrame) * bytesPerFrame)
        val preamp = if (config.preampDb == 0f) 1f else 10f.pow(config.preampDb / 20f)
        val frame = FloatArray(channels)
        while (inputBuffer.remaining() >= bytesPerFrame) {
            for (channel in 0 until channels) {
                frame[channel] = PcmOps.readSample(inputBuffer, encoding) * preamp
            }
            for (band in bands) {
                for (channel in 0 until channels) {
                    frame[channel] = band.next(frame[channel].toDouble(), channel).toFloat()
                }
            }
            for (channel in 0 until channels) {
                PcmOps.writeSample(out, encoding, frame[channel])
            }
        }
        out.flip()
    }

    private fun rebuild() {
        val sampleRate = if (configuredSampleRate > 0) configuredSampleRate else DEFAULT_SAMPLE_RATE
        bands = config.bands.map { BandFilter(Biquad.coefficients(it, sampleRate), MAX_CHANNELS) }
    }

    override fun onFlush() {
        bands.forEach(BandFilter::clear)
    }

    override fun onReset() {
        bands.forEach(BandFilter::clear)
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val MAX_CHANNELS = 8
    }
}

internal class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    companion object {
        fun coefficients(band: PeqBand, sampleRate: Int): Biquad {
            val w0 = 2.0 * PI * band.frequencyHz.toDouble() / sampleRate
            val cosW0 = cos(w0)
            val gainA = 10.0.pow(band.gainDb.toDouble() / 40.0)
            return when (band.type) {
                PeqBandType.PEAKING -> {
                    val alpha = sin(w0) / (2.0 * band.q.toDouble())
                    val a0 = 1.0 + alpha / gainA
                    Biquad(
                        b0 = (1.0 + alpha * gainA) / a0,
                        b1 = (-2.0 * cosW0) / a0,
                        b2 = (1.0 - alpha * gainA) / a0,
                        a1 = (-2.0 * cosW0) / a0,
                        a2 = (1.0 - alpha / gainA) / a0,
                    )
                }
                PeqBandType.LOW_SHELF -> {
                    val alpha = sin(w0) / 2.0 * sqrt((gainA + 1.0 / gainA) * (1.0 / SHELF_SLOPE - 1.0) + 2.0)
                    val boost = 2.0 * sqrt(gainA) * alpha
                    val a0 = (gainA + 1.0) + (gainA - 1.0) * cosW0 + boost
                    Biquad(
                        b0 = gainA * ((gainA + 1.0) - (gainA - 1.0) * cosW0 + boost) / a0,
                        b1 = 2.0 * gainA * ((gainA - 1.0) - (gainA + 1.0) * cosW0) / a0,
                        b2 = gainA * ((gainA + 1.0) - (gainA - 1.0) * cosW0 - boost) / a0,
                        a1 = -2.0 * ((gainA - 1.0) + (gainA + 1.0) * cosW0) / a0,
                        a2 = ((gainA + 1.0) + (gainA - 1.0) * cosW0 - boost) / a0,
                    )
                }
                PeqBandType.HIGH_SHELF -> {
                    val alpha = sin(w0) / 2.0 * sqrt((gainA + 1.0 / gainA) * (1.0 / SHELF_SLOPE - 1.0) + 2.0)
                    val boost = 2.0 * sqrt(gainA) * alpha
                    val a0 = (gainA + 1.0) - (gainA - 1.0) * cosW0 + boost
                    Biquad(
                        b0 = gainA * ((gainA + 1.0) + (gainA - 1.0) * cosW0 + boost) / a0,
                        b1 = -2.0 * gainA * ((gainA - 1.0) + (gainA + 1.0) * cosW0) / a0,
                        b2 = gainA * ((gainA + 1.0) + (gainA - 1.0) * cosW0 - boost) / a0,
                        a1 = 2.0 * ((gainA - 1.0) - (gainA + 1.0) * cosW0) / a0,
                        a2 = ((gainA + 1.0) - (gainA - 1.0) * cosW0 - boost) / a0,
                    )
                }
                PeqBandType.LOW_PASS -> {
                    val alpha = sin(w0) / (2.0 * band.q.toDouble())
                    val a0 = 1.0 + alpha
                    Biquad(
                        b0 = (1.0 - cosW0) / 2.0 / a0,
                        b1 = (1.0 - cosW0) / a0,
                        b2 = (1.0 - cosW0) / 2.0 / a0,
                        a1 = (-2.0 * cosW0) / a0,
                        a2 = (1.0 - alpha) / a0,
                    )
                }
                PeqBandType.HIGH_PASS -> {
                    val alpha = sin(w0) / (2.0 * band.q.toDouble())
                    val a0 = 1.0 + alpha
                    Biquad(
                        b0 = (1.0 + cosW0) / 2.0 / a0,
                        b1 = -(1.0 + cosW0) / a0,
                        b2 = (1.0 + cosW0) / 2.0 / a0,
                        a1 = (-2.0 * cosW0) / a0,
                        a2 = (1.0 - alpha) / a0,
                    )
                }
                PeqBandType.BAND_PASS -> {
                    val alpha = sin(w0) / (2.0 * band.q.toDouble())
                    val a0 = 1.0 + alpha
                    Biquad(
                        b0 = alpha / a0,
                        b1 = 0.0,
                        b2 = -alpha / a0,
                        a1 = (-2.0 * cosW0) / a0,
                        a2 = (1.0 - alpha) / a0,
                    )
                }
            }
        }

        private const val SHELF_SLOPE = 1.0
    }
}

internal class BandFilter(coefficients: Biquad, channelCount: Int) {
    private val b0 = coefficients.b0
    private val b1 = coefficients.b1
    private val b2 = coefficients.b2
    private val a1 = coefficients.a1
    private val a2 = coefficients.a2
    private val x1 = DoubleArray(channelCount)
    private val x2 = DoubleArray(channelCount)
    private val y1 = DoubleArray(channelCount)
    private val y2 = DoubleArray(channelCount)

    fun next(sample: Double, channel: Int): Double {
        val result = b0 * sample + b1 * x1[channel] + b2 * x2[channel] - a1 * y1[channel] - a2 * y2[channel]
        x2[channel] = x1[channel]
        x1[channel] = sample
        y2[channel] = y1[channel]
        y1[channel] = result
        return result
    }

    fun clear() {
        x1.fill(0.0)
        x2.fill(0.0)
        y1.fill(0.0)
        y2.fill(0.0)
    }
}
