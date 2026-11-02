package app.omnisonic.core.data

import app.omnisonic.core.data.db.PlaybackDay
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayEventDaoTest : DatabaseTestBase() {

    private val dayStart = PlaybackDay.dayStart(1_736_100_000_000)

    @Test
    fun everyPlayKeepsItsOwnTimestampAndRollsUpPerDay() = runDbTest {
        val midAfternoon = dayStart + 3 * 3_600_000

        playEventDao.recordPlay(1, "song-1", dayStart, durationMs = 300)
        playEventDao.recordSkip(1, "song-1", midAfternoon, durationMs = 700)

        val events = playEventDao.selectByProviderAndExternal(1, "song-1")
        assertThat(events).hasSize(2)
        assertThat(events.map { it.playedAtMs }).containsExactly(dayStart, midAfternoon).inOrder()

        val rollup = playEventDao.selectDailyByProviderAndExternal(1, "song-1").single()
        assertThat(rollup.date).isEqualTo(dayStart)
        assertThat(rollup.played).isTrue()
        assertThat(rollup.skipped).isTrue()
        assertThat(rollup.durationMs).isEqualTo(1_000)
        assertThat(rollup.playCount).isEqualTo(2)
    }

    @Test
    fun differentDaysProduceSeparateRollups() = runDbTest {
        playEventDao.recordPlay(1, "song-1", dayStart, durationMs = 300)
        playEventDao.recordPlay(1, "song-1", dayStart + 86_400_000, durationMs = 100)

        assertThat(playEventDao.selectByProviderAndExternal(1, "song-1")).hasSize(2)
        assertThat(playEventDao.selectDailyByProviderAndExternal(1, "song-1")).hasSize(2)
    }

    @Test
    fun differentTracksStayIndependent() = runDbTest {
        playEventDao.recordPlay(1, "song-1", dayStart, durationMs = 300)
        playEventDao.recordPlay(1, "song-2", dayStart, durationMs = 100)

        assertThat(playEventDao.selectByProvider(1)).hasSize(2)
        assertThat(playEventDao.selectDailyByProvider(1)).hasSize(2)
        assertThat(playEventDao.count()).isEqualTo(2)
    }

    @Test
    fun dayStartBucketsLocalMidnight() {
        val noon = dayStart + 12 * 3_600_000
        assertThat(PlaybackDay.dayStart(noon)).isEqualTo(dayStart)
    }
}
