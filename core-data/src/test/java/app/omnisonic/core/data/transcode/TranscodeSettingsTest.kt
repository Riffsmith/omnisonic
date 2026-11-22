package app.omnisonic.core.data.transcode

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TranscodeSettingsTest {
    @Test
    fun `defaults leave every bitrate off and mandatory disabled`() {
        val settings = TranscodeSettings()

        assertThat(settings.wifiBitrateKbps).isNull()
        assertThat(settings.cellularBitrateKbps).isNull()
        assertThat(settings.downloadBitrateKbps).isNull()
        assertThat(settings.mandatory).isFalse()
        assertThat(settings.preferredFormat).isNull()
    }

    @Test
    fun `off and explicit positive bitrates are distinct`() {
        val off = TranscodeSettings()
        val explicit = TranscodeSettings(
            wifiBitrateKbps = 1,
            cellularBitrateKbps = 192,
            downloadBitrateKbps = Int.MAX_VALUE,
        )

        assertThat(off.wifiBitrateKbps).isNotEqualTo(explicit.wifiBitrateKbps)
        assertThat(explicit.wifiBitrateKbps).isEqualTo(1)
        assertThat(explicit.cellularBitrateKbps).isEqualTo(192)
        assertThat(explicit.downloadBitrateKbps).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `non-positive bitrate boundaries are rejected for every network`() {
        listOf(0, -1, Int.MIN_VALUE).forEach { value ->
            assertThrows<IllegalArgumentException> { TranscodeSettings(wifiBitrateKbps = value) }
            assertThrows<IllegalArgumentException> { TranscodeSettings(cellularBitrateKbps = value) }
            assertThrows<IllegalArgumentException> { TranscodeSettings(downloadBitrateKbps = value) }
        }
    }

    @Test
    fun `format preference accepts lowercase provider-neutral tokens`() {
        listOf("mp3", "opus", "flac", "aac", "vorbis", "mp4.audio", "audio_1", "format-2").forEach {
            assertThat(TranscodeSettings(preferredFormat = it).preferredFormat).isEqualTo(it)
        }
    }

    @Test
    fun `format preference rejects invalid token boundaries`() {
        listOf("", "auto", "MP3", " mp3", "mp3 ", "audio/mp3", ".mp3", "mp3.", "mp3..raw", "a".repeat(33))
            .forEach { value ->
                assertThrows<IllegalArgumentException> { TranscodeSettings(preferredFormat = value) }
            }
    }

    @Test
    fun `local renderer has a stable independent identity`() {
        val other = TranscodeRendererId("test-renderer")

        assertThat(TranscodeRendererId.Local.value).isEqualTo("local")
        assertThat(other).isNotEqualTo(TranscodeRendererId.Local)
        assertThrows<IllegalArgumentException> { TranscodeRendererId("Invalid Renderer") }
    }
}
