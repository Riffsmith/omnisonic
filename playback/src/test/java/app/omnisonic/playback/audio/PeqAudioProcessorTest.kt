package app.omnisonic.playback.audio

import app.omnisonic.playback.api.equalizer.PeqBand
import app.omnisonic.playback.api.equalizer.PeqBandType
import app.omnisonic.playback.api.equalizer.PeqConfig
import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.jupiter.api.Test

class PeqAudioProcessorTest {

    @Test
    fun `zero gain peaking band is exact identity on 16-bit`() {
        val processor = PeqAudioProcessor()
        processor.setPeqConfig(
            PeqConfig(bands = listOf(PeqBand(PeqBandType.PEAKING, frequencyHz = 1000f, gainDb = 0f, q = 1f))),
        )
        processor.configure(format())
        val input = shortArrayOf(100, -500, 32767, -32768, 7, 9999)
        val out = processor.process(shortsBuffer(input)).readShorts()
        assertThat(out).isEqualTo(input)
    }

    @Test
    fun `filter response invariants match RBJ cookbook`() {
        fun dcGain(b: Biquad) = (b.b0 + b.b1 + b.b2) / (1.0 + b.a1 + b.a2)
        fun nyquistGain(b: Biquad) = (b.b0 - b.b1 + b.b2) / (1.0 - b.a1 + b.a2)

        val peaking = Biquad.coefficients(PeqBand(PeqBandType.PEAKING, 1000f, 6f, 1f), 48000)
        assertThat(dcGain(peaking)).isWithin(1e-9).of(1.0)
        assertThat(nyquistGain(peaking)).isWithin(1e-9).of(1.0)

        val lowShelf = Biquad.coefficients(PeqBand(PeqBandType.LOW_SHELF, 200f, 12f, 0.7f), 48000)
        assertThat(dcGain(lowShelf)).isWithin(1e-6).of(10.0.pow(12.0 / 20.0))
        assertThat(nyquistGain(lowShelf)).isWithin(1e-6).of(1.0)

        val highShelf = Biquad.coefficients(PeqBand(PeqBandType.HIGH_SHELF, 200f, 12f, 0.7f), 48000)
        assertThat(dcGain(highShelf)).isWithin(1e-6).of(1.0)
        assertThat(nyquistGain(highShelf)).isWithin(1e-6).of(10.0.pow(12.0 / 20.0))

        val lowPass = Biquad.coefficients(PeqBand(PeqBandType.LOW_PASS, 1000f, 0f, 0.707f), 48000)
        assertThat(dcGain(lowPass)).isWithin(1e-9).of(1.0)
        assertThat(nyquistGain(lowPass)).isWithin(1e-9).of(0.0)

        val highPass = Biquad.coefficients(PeqBand(PeqBandType.HIGH_PASS, 1000f, 0f, 0.707f), 48000)
        assertThat(dcGain(highPass)).isWithin(1e-9).of(0.0)
        assertThat(nyquistGain(highPass)).isWithin(1e-9).of(1.0)

        val bandPass = Biquad.coefficients(PeqBand(PeqBandType.BAND_PASS, 1000f, 0f, 2f), 48000)
        assertThat(dcGain(bandPass)).isWithin(1e-9).of(0.0)
        assertThat(nyquistGain(bandPass)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `low shelf boosts low frequency content`() {
        val processor = PeqAudioProcessor()
        processor.setPeqConfig(
            PeqConfig(bands = listOf(PeqBand(PeqBandType.LOW_SHELF, frequencyHz = 200f, gainDb = 12f, q = 0.7f))),
        )
        processor.configure(format(channels = 1))
        val frames = 4800
        val input = ShortArray(frames) {
            (6000.0 * sin(2.0 * PI * 100.0 * it / 48000.0)).roundToInt().toShort()
        }
        val out = processor.process(shortsBuffer(input)).readShorts()
        val inRms = sqrt(input.map { it * it.toDouble() }.average())
        val outRms = sqrt(out.map { it * it.toDouble() }.average())
        assertThat(outRms / inRms).isGreaterThan(2.5)
    }

    @Test
    fun `preamp alone scales output`() {
        val processor = PeqAudioProcessor()
        processor.setPeqConfig(PeqConfig(preampDb = -6f))
        processor.configure(format(channels = 1))
        val out = processor.process(shortsBuffer(shortArrayOf(1000, 1000))).readShorts()
        assertThat(out[0].toInt()).isAnyOf(501, 502)
        assertThat(out[1].toInt()).isAnyOf(501, 502)
    }

    @Test
    fun `inactive with empty config and active with preamp`() {
        val processor = PeqAudioProcessor()
        processor.configure(format())
        assertThat(processor.isActive()).isFalse()
        processor.setPeqConfig(PeqConfig(preampDb = -3f))
        assertThat(processor.isActive()).isTrue()
    }

    @Test
    fun `flush clears filter state`() {
        val processor = PeqAudioProcessor()
        processor.setPeqConfig(
            PeqConfig(bands = listOf(PeqBand(PeqBandType.PEAKING, 1000f, 6f, 1f))),
        )
        processor.configure(format())
        val first = processor.process(shortsBuffer(shortArrayOf(1000, 1000, 1000, 1000))).readShorts()
        processor.flush()
        val second = processor.process(shortsBuffer(shortArrayOf(1000, 1000, 1000, 1000))).readShorts()
        assertThat(second).isEqualTo(first)
    }
}
