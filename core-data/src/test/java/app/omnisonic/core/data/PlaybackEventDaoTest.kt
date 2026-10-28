package app.omnisonic.core.data

import app.omnisonic.core.data.db.PlaybackDay
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEventDaoTest : DatabaseTestBase() {

    private val dayStart = PlaybackDay.dayStart(1_736_100_000_000)

    @Test
    fun sameDayMergesFlagsAndAccumulatesDurationKeepingBucket() = runDbTest {
        val midAfternoon = dayStart + 3 * 3_600_000

        playbackEventDao.recordDayEvent(1, "song-1", dayStart, played = true, skipped = false, durationMs = 300)
        playbackEventDao.recordDayEvent(1, "song-1", PlaybackDay.dayStart(midAfternoon), played = false, skipped = true, durationMs = 700)

        val events = playbackEventDao.selectByProvider(1)
        assertThat(events).hasSize(1)
        val event = events.single()
        assertThat(event.date).isEqualTo(dayStart)
        assertThat(event.played).isTrue()
        assertThat(event.skipped).isTrue()
        assertThat(event.durationMs).isEqualTo(1_000)
    }

    @Test
    fun differentDaysProduceSeparateRows() = runDbTest {
        playbackEventDao.recordDayEvent(1, "song-1", dayStart, played = true, skipped = false, durationMs = 300)
        playbackEventDao.recordDayEvent(1, "song-1", dayStart + 86_400_000, played = true, skipped = false, durationMs = 100)

        assertThat(playbackEventDao.selectByProviderAndExternal(1, "song-1")).hasSize(2)
    }

    @Test
    fun differentTracksStayIndependent() = runDbTest {
        playbackEventDao.recordDayEvent(1, "song-1", dayStart, played = true, skipped = false, durationMs = 300)
        playbackEventDao.recordDayEvent(1, "song-2", dayStart, played = true, skipped = false, durationMs = 100)

        assertThat(playbackEventDao.selectByProvider(1)).hasSize(2)
        assertThat(playbackEventDao.count()).isEqualTo(2)
    }

    @Test
    fun dayStartBucketsLocalMidnight() {
        val noon = dayStart + 12 * 3_600_000
        assertThat(PlaybackDay.dayStart(noon)).isEqualTo(dayStart)
    }
}
