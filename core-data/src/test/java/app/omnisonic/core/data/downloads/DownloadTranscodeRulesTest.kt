package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.DownloadStreamKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadTranscodeRulesTest {

    @Test
    fun `a raw auto variant requests no bitrate and is not transcoded`() {
        val key = DownloadStreamKey(providerId = 1, externalId = "song-1", transcodeBitrateKbps = null, format = null)

        assertThat(DownloadTranscodeRules.isTranscoded(key)).isFalse()
        assertThat(DownloadTranscodeRules.requestBitrateKbps(key)).isNull()
    }

    @Test
    fun `an explicit bitrate is kept on the wire request`() {
        val key = DownloadStreamKey(providerId = 1, externalId = "song-1", transcodeBitrateKbps = 96, format = null)

        assertThat(DownloadTranscodeRules.isTranscoded(key)).isTrue()
        assertThat(DownloadTranscodeRules.requestBitrateKbps(key)).isEqualTo(96)
    }

    @Test
    fun `a format-only transcode defaults to 192 on the wire request`() {
        val key = DownloadStreamKey(providerId = 1, externalId = "song-1", transcodeBitrateKbps = null, format = "mp3")

        assertThat(DownloadTranscodeRules.isTranscoded(key)).isTrue()
        assertThat(DownloadTranscodeRules.requestBitrateKbps(key)).isEqualTo(DownloadTranscodeRules.DEFAULT_TRANSCODE_BITRATE_KBPS)
        assertThat(DownloadTranscodeRules.DEFAULT_TRANSCODE_BITRATE_KBPS).isEqualTo(192)
    }

    @Test
    fun `an explicit bitrate and format are both kept`() {
        val key = DownloadStreamKey(providerId = 1, externalId = "song-1", transcodeBitrateKbps = 128, format = "opus")

        assertThat(DownloadTranscodeRules.isTranscoded(key)).isTrue()
        assertThat(DownloadTranscodeRules.requestBitrateKbps(key)).isEqualTo(128)
    }
}
