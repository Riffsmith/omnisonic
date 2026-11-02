package app.omnisonic.core.data.repository

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.provider.api.ProviderTraits
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayCounterTest : DatabaseTestBase() {

    private fun counter(traits: ProviderTraits = ProviderTraits()) = PlayCounter(
        db = db,
        songRepository = SongRepository(db),
        outboxQueue = OutboxQueue(db, db.outboxDao(), traitsSource = { traits }, moshi = app.omnisonic.core.data.json.OmnisonicJson.moshi),
    )

    private val snapshot = SongRepository.SongSnapshot(
        title = "Unsynced title",
        artistName = "Unsynced artist",
        coverImageId = "cover-1",
        durationMs = 200_000,
    )

    @Test
    fun recordsAPlayForAnItemWithoutASyncedSong() = runDbTest {
        newProvider(id = 1)

        val songId = counter().recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)

        val song = songDao.selectById(songId)!!
        assertThat(song.title).isEqualTo("Unsynced title")
        assertThat(song.playCount).isEqualTo(1)
        assertThat(song.lastPlayed).isEqualTo(1_000)
        assertThat(playEventDao.selectByProviderAndExternal(1, "song-1")).hasSize(1)
        assertThat(outboxDao.selectByProvider(1)).hasSize(1)
    }

    @Test
    fun secondPlayReusesTheMaterialisedRow() = runDbTest {
        newProvider(id = 1)
        val counter = counter()

        val first = counter.recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)
        val second = counter.recordPlay(1, "song-1", snapshot, playedAtMs = 2_000, durationMs = 2_000)

        assertThat(second).isEqualTo(first)
        assertThat(songDao.selectByProvider(1)).hasSize(1)
        assertThat(songDao.selectById(first)!!.playCount).isEqualTo(2)
        assertThat(playEventDao.selectByProviderAndExternal(1, "song-1")).hasSize(2)
    }

    @Test
    fun skipMarksTheSongWithoutCountingAPlay() = runDbTest {
        newProvider(id = 1)

        val songId = counter().recordSkip(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 500)

        val song = songDao.selectById(songId)!!
        assertThat(song.playCount).isEqualTo(0)
        assertThat(song.skipped).isTrue()
        val event = playEventDao.selectByProviderAndExternal(1, "song-1").single()
        assertThat(event.skipped).isTrue()
        assertThat(event.played).isFalse()
    }

    @Test
    fun localProviderSkipsTheOutboxButKeepsTheLocalPlay() = runDbTest {
        newProvider(id = 1)

        counter(traits = ProviderTraits(reportsUserData = false))
            .recordPlay(1, "song-1", snapshot, playedAtMs = 1_000, durationMs = 2_000)

        assertThat(songDao.selectByProviderAndExternal(1, "song-1")!!.playCount).isEqualTo(1)
        assertThat(outboxDao.selectByProvider(1)).isEmpty()
    }
}
