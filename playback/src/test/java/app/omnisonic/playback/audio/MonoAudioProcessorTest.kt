package app.omnisonic.playback.audio

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MonoAudioProcessorTest {

    @Test
    fun `stereo 16-bit mixes to averaged mono`() {
        val processor = MonoAudioProcessor()
        processor.setEnabled(true)
        processor.configure(format())
        val out = processor.process(shortsBuffer(shortArrayOf(1000, 2000, -1000, -2000)))
        assertThat(out.readShorts()).isEqualTo(shortArrayOf(1500, 1500, -1500, -1500))
    }

    @Test
    fun `mono input passes through untouched`() {
        val processor = MonoAudioProcessor()
        processor.setEnabled(true)
        processor.configure(format(channels = 1))
        val out = processor.process(shortsBuffer(shortArrayOf(100, 200)))
        assertThat(out.readShorts()).isEqualTo(shortArrayOf(100, 200))
    }

    @Test
    fun `stereo float mixes`() {
        val processor = MonoAudioProcessor()
        processor.setEnabled(true)
        processor.configure(format(encoding = C.ENCODING_PCM_FLOAT))
        val out = processor.process(floatsBuffer(floatArrayOf(0.5f, 0.25f)))
        assertThat(out.readFloats()[0]).isEqualTo(0.375f)
    }

    @Test
    fun `extremes saturate correctly`() {
        val processor = MonoAudioProcessor()
        processor.setEnabled(true)
        processor.configure(format())
        val out = processor.process(shortsBuffer(shortArrayOf(32767, 32767, -32768, -32768)))
        assertThat(out.readShorts()).isEqualTo(shortArrayOf(32767, 32767, -32768, -32768))
    }

    @Test
    fun `inactive for non-stereo`() {
        val processor = MonoAudioProcessor()
        processor.setEnabled(true)
        processor.configure(format(channels = 1))
        assertThat(processor.isActive()).isFalse()
        processor.configure(format(channels = 2))
        assertThat(processor.isActive()).isTrue()
    }

    @Test
    fun `disabled by default and toggles without reconfiguring`() {
        val processor = MonoAudioProcessor()
        processor.configure(format())
        assertThat(processor.isEnabled).isFalse()
        assertThat(processor.isActive()).isFalse()

        processor.setEnabled(true)
        assertThat(processor.isEnabled).isTrue()
        assertThat(processor.isActive()).isTrue()

        processor.setEnabled(false)
        assertThat(processor.isActive()).isFalse()
    }
}
