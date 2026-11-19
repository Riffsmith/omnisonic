package app.omnisonic.playback.history

import app.omnisonic.core.data.history.HistoryPreferences
import app.omnisonic.playback.history.PlayHistoryRules.toRuleValues
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PlayHistoryRulesTest {

    @Test
    fun `a play counts at the configured fraction of the duration`() {
        assertThat(PlayHistoryRules.countsAsPlay(189_999, 200_000)).isFalse()
        assertThat(PlayHistoryRules.countsAsPlay(190_000, 200_000)).isTrue()
        assertThat(PlayHistoryRules.countsAsPlay(190_000, 200_000, thresholdFraction = 0.99)).isFalse()
        assertThat(PlayHistoryRules.countsAsPlay(100_000, 200_000, thresholdFraction = 0.5)).isTrue()
    }

    @Test
    fun `a play never counts without a positive duration`() {
        assertThat(PlayHistoryRules.countsAsPlay(200_000, null)).isFalse()
        assertThat(PlayHistoryRules.countsAsPlay(200_000, 0)).isFalse()
        assertThat(PlayHistoryRules.countsAsPlay(200_000, -1)).isFalse()
    }

    @Test
    fun `a resume point needs the absolute minimum position`() {
        assertThat(PlayHistoryRules.resumePositionMs(9_999, 100_000)).isNull()
        assertThat(PlayHistoryRules.resumePositionMs(10_000, 100_000)).isEqualTo(10_000)
        assertThat(PlayHistoryRules.resumePositionMs(0, 100_000)).isNull()
        assertThat(PlayHistoryRules.resumePositionMs(-5, 100_000)).isNull()
        assertThat(PlayHistoryRules.resumePositionMs(20_000, 600_000)).isNull()
    }

    @Test
    fun `a resume point uses the larger of the absolute and relative floors`() {
        assertThat(
            PlayHistoryRules.resumePositionMs(15_000, 100_000, minPositionMs = 10_000, minFraction = 0.2),
        ).isNull()
        assertThat(
            PlayHistoryRules.resumePositionMs(20_000, 100_000, minPositionMs = 10_000, minFraction = 0.2),
        ).isEqualTo(20_000)
    }

    @Test
    fun `a resume point is not stored once the play counts`() {
        assertThat(PlayHistoryRules.resumePositionMs(190_000, 200_000)).isNull()
        assertThat(PlayHistoryRules.resumePositionMs(100_000, 200_000, playThresholdFraction = 0.4)).isNull()
    }

    @Test
    fun `an unknown duration keeps only the absolute floor`() {
        assertThat(PlayHistoryRules.resumePositionMs(12_000, null)).isEqualTo(12_000)
        assertThat(PlayHistoryRules.resumePositionMs(2_000, 0)).isNull()
    }

    @Test
    fun `persisted history preferences map to rule values`() {
        val values = HistoryPreferences(
            playThresholdPercent = 50,
            resumeMinPositionMs = 20_000L,
        ).toRuleValues()

        assertThat(values.playThresholdFraction).isEqualTo(0.5)
        assertThat(values.resumeMinPositionMs).isEqualTo(20_000L)
        assertThat(values.resumeMinFraction).isEqualTo(PlayHistoryRules.DEFAULT_RESUME_MIN_FRACTION)
    }
}
