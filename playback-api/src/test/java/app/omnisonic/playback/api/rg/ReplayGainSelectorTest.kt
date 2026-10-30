package app.omnisonic.playback.api.rg

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReplayGainSelectorTest {
    private val values = ReplayGainValues(trackGainDb = -6.0, albumGainDb = -9.0, tagType = RgTagType.RG2)

    @Test
    fun `off mode is silent`() {
        assertThat(ReplayGainSelector.gainDb(values, ReplayGainMode.OFF, RgNormalisation.RG2)).isEqualTo(0.0)
        assertThat(ReplayGainSelector.gainDb(null, ReplayGainMode.TRACK, RgNormalisation.RG2)).isEqualTo(0.0)
    }

    @Test
    fun `track uses track gain and skips when absent`() {
        assertThat(ReplayGainSelector.gainDb(values, ReplayGainMode.TRACK, RgNormalisation.RG2)).isEqualTo(-6.0)
        val noTrack = ReplayGainValues(trackGainDb = null, albumGainDb = -9.0)
        assertThat(ReplayGainSelector.gainDb(noTrack, ReplayGainMode.TRACK, RgNormalisation.RG2)).isEqualTo(0.0)
    }

    @Test
    fun `prefer track falls back to album`() {
        val noTrack = values.copy(trackGainDb = null)
        assertThat(ReplayGainSelector.gainDb(noTrack, ReplayGainMode.PREFER_TRACK, RgNormalisation.RG2)).isEqualTo(-9.0)
        assertThat(ReplayGainSelector.gainDb(values, ReplayGainMode.PREFER_TRACK, RgNormalisation.RG2)).isEqualTo(-6.0)
    }

    @Test
    fun `album uses album gain and skips when absent`() {
        assertThat(ReplayGainSelector.gainDb(values, ReplayGainMode.ALBUM, RgNormalisation.RG2)).isEqualTo(-9.0)
        val noAlbum = values.copy(albumGainDb = null)
        assertThat(ReplayGainSelector.gainDb(noAlbum, ReplayGainMode.ALBUM, RgNormalisation.RG2)).isEqualTo(0.0)
    }

    @Test
    fun `prefer album falls back to track`() {
        val noAlbum = values.copy(albumGainDb = null)
        assertThat(ReplayGainSelector.gainDb(noAlbum, ReplayGainMode.PREFER_ALBUM, RgNormalisation.RG2)).isEqualTo(-6.0)
        assertThat(ReplayGainSelector.gainDb(values, ReplayGainMode.PREFER_ALBUM, RgNormalisation.RG2)).isEqualTo(-9.0)
    }

    @Test
    fun `automatic prefers album then track`() {
        assertThat(ReplayGainSelector.gainDb(values, ReplayGainMode.AUTOMATIC, RgNormalisation.RG2)).isEqualTo(-9.0)
        val bothNull = ReplayGainValues(trackGainDb = null, albumGainDb = null)
        assertThat(ReplayGainSelector.gainDb(bothNull, ReplayGainMode.AUTOMATIC, RgNormalisation.RG2)).isEqualTo(0.0)
    }

    @Test
    fun `normalisation off silences gain`() {
        assertThat(ReplayGainSelector.gainDb(values, ReplayGainMode.TRACK, RgNormalisation.OFF)).isEqualTo(0.0)
    }

    @Test
    fun `ebu uses measured loudness against target when available`() {
        val gain = ReplayGainSelector.gainDb(
            values,
            ReplayGainMode.TRACK,
            RgNormalisation.EBU,
            targetLoudnessLufs = -18.0,
            measuredLoudnessLufs = -21.0,
        )
        assertThat(gain).isEqualTo(-18.0 - (-21.0))
    }

    @Test
    fun `ebu falls back to rg math without a measured loudness`() {
        val gain = ReplayGainSelector.gainDb(
            values,
            ReplayGainMode.TRACK,
            RgNormalisation.EBU,
        )
        assertThat(gain).isEqualTo(-6.0)
    }

    @Test
    fun `db to linear multiplier is power of ten scaling`() {
        assertThat(ReplayGainSelector.linearMultiplier(0.0)).isEqualTo(1f)
        assertThat(ReplayGainSelector.linearMultiplier(20.0)).isEqualTo(10f)
        assertThat(ReplayGainSelector.linearMultiplier(-20.0)).isWithin(1e-4f).of(0.1f)
        assertThat(ReplayGainSelector.linearMultiplier(-6.0)).isWithin(1e-3f).of(0.5012f)
    }
}
