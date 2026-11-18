package app.omnisonic.playback.history

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
        assertThat(PlayHistoryRules.resumePointMs(9_999, 100_000)).isNull()
        assertThat(PlayHistoryRules.resumePointMs(10_000, 100_000)).isEqualTo(10_000)
        assertThat(PlayHistoryRules.resumePointMs(0, 100_000)).isNull()
        assertThat(PlayHistoryRules.resumePointMs(-5, 100_000)).isNull()
        assertThat(PlayHistoryRules.resumePointMs(20_000, 600_000)).isNull()
    }

    @Test
    fun `a resume point uses the larger of the absolute and relative floors`() {
        assertThat(
            PlayHistoryRules.resumePointMs(15_000, 100_000, minPositionMs = 10_000, minFraction = 0.2),
        ).isNull()
        assertThat(
            PlayHistoryRules.resumePointMs(20_000, 100_000, minPositionMs = 10_000, minFraction = 0.2),
        ).isEqualTo(20_000)
    }

    @Test
    fun `a resume point is not stored once the play counts`() {
        assertThat(PlayHistoryRules.resumePointMs(190_000, 200_000)).isNull()
        assertThat(PlayHistoryRules.resumePointMs(100_000, 200_000, playThresholdFraction = 0.4)).isNull()
    }

    @Test
    fun `an unknown duration keeps only the absolute floor`() {
        assertThat(PlayHistoryRules.resumePointMs(12_000, null)).isEqualTo(12_000)
        assertThat(PlayHistoryRules.resumePointMs(2_000, 0)).isNull()
    }
}
