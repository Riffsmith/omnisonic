package app.omnisonic.playback.audio

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FadeAudioProcessorTest {

    @Test
    fun `fade in ramps linearly to full volume`() {
        val processor = FadeAudioProcessor()
        processor.configure(format(channels = 1))
        processor.beginFadeIn(4)
        val frames = 192
        val out = processor.process(shortsBuffer(ShortArray(frames) { 1000 })).readShorts()
        assertThat(out[0]).isEqualTo(0)
        assertThat(out[96]).isEqualTo(500)
        assertThat(out[191]).isEqualTo(995)
        assertThat(processor.isActive()).isFalse()
        assertThat(processor.process(shortsBuffer(shortArrayOf(1000, 1000))).readShorts())
            .isEqualTo(shortArrayOf(1000, 1000))
    }

    @Test
    fun `fade out ramps to silence`() {
        val processor = FadeAudioProcessor()
        processor.configure(format(channels = 1))
        processor.beginFadeOut(4)
        val out = processor.process(shortsBuffer(ShortArray(192) { 1000 })).readShorts()
        assertThat(out[0]).isEqualTo(1000)
        assertThat(out[96]).isEqualTo(500)
        assertThat(out[191]).isEqualTo(5)
        assertThat(processor.isActive()).isFalse()
    }

    @Test
    fun `fade requested before configure materialises on configure`() {
        val processor = FadeAudioProcessor()
        processor.beginFadeIn(4)
        processor.configure(format(channels = 1))
        assertThat(processor.isActive()).isTrue()
    }

    @Test
    fun `flush cancels an active fade`() {
        val processor = FadeAudioProcessor()
        processor.configure(format(channels = 1))
        processor.beginFadeIn(10)
        assertThat(processor.isActive()).isTrue()
        processor.flush()
        assertThat(processor.isActive()).isFalse()
    }

    @Test
    fun `zero duration fade is passthrough`() {
        val processor = FadeAudioProcessor()
        processor.configure(format(channels = 1))
        processor.beginFadeIn(0)
        assertThat(processor.isActive()).isFalse()
        assertThat(processor.process(shortsBuffer(shortArrayOf(500))).readShorts())
            .isEqualTo(shortArrayOf(500))
    }
}
