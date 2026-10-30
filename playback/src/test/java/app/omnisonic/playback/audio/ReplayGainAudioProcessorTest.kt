package app.omnisonic.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReplayGainAudioProcessorTest {

    @Test
    fun `half gain halves 16-bit samples`() {
        val processor = ReplayGainAudioProcessor()
        processor.configure(format())
        processor.setLinearFactor(0.5f)
        val out = processor.process(shortsBuffer(shortArrayOf(100, -200, 32767, -32768)))
        assertThat(out.readShorts()).isEqualTo(shortArrayOf(50, -100, 16384, -16384))
    }

    @Test
    fun `neutral factor passes through unchanged`() {
        val processor = ReplayGainAudioProcessor()
        processor.configure(format())
        val out = processor.process(shortsBuffer(shortArrayOf(1, -2, 3, 4)))
        assertThat(out.readShorts()).isEqualTo(shortArrayOf(1, -2, 3, 4))
    }

    @Test
    fun `duck factor reduces volume`() {
        val processor = ReplayGainAudioProcessor()
        processor.configure(format())
        processor.setDuckFactor(0.25f)
        val out = processor.process(shortsBuffer(shortArrayOf(400, -400)))
        assertThat(out.readShorts()).isEqualTo(shortArrayOf(100, -100))
    }

    @Test
    fun `float samples clamp beyond unity`() {
        val processor = ReplayGainAudioProcessor()
        processor.configure(format(encoding = C.ENCODING_PCM_FLOAT))
        processor.setLinearFactor(2f)
        val out = processor.process(floatsBuffer(floatArrayOf(-0.6f, 0.6f)))
        assertThat(out.readFloats()).isEqualTo(floatArrayOf(-1.0f, 1.0f))
    }

    @Test
    fun `24-bit samples scale`() {
        val processor = ReplayGainAudioProcessor()
        processor.configure(format(encoding = C.ENCODING_PCM_24BIT))
        processor.setLinearFactor(0.5f)
        val out = processor.process(signed24Buffer(intArrayOf(0x7FFFFF, -0x800000, 12345)))
        assertThat(out.signed24Values()).isEqualTo(intArrayOf(0x400000, -0x400000, 6173))
    }

    @Test
    fun `32-bit samples scale in normalised domain`() {
        val processor = ReplayGainAudioProcessor()
        processor.configure(format(encoding = C.ENCODING_PCM_32BIT))
        processor.setLinearFactor(0.5f)
        val out = processor.process(ints32Buffer(intArrayOf(0x40000000, -0x40000000)))
        assertThat(out.readInts()).isEqualTo(intArrayOf(0x20000000, -0x20000000))
    }

    @Test
    fun `inactive when neutral and active after factor set`() {
        val processor = ReplayGainAudioProcessor()
        processor.configure(format())
        assertThat(processor.isActive()).isFalse()
        processor.setLinearFactor(0.5f)
        assertThat(processor.isActive()).isTrue()
    }

    @Test
    fun `unsupported encoding rejected`() {
        val processor = ReplayGainAudioProcessor()
        val error = runCatching { processor.configure(format(encoding = C.ENCODING_INVALID)) }
            .exceptionOrNull()
        assertThat(error).isInstanceOf(AudioProcessor.UnhandledAudioFormatException::class.java)
    }
}
