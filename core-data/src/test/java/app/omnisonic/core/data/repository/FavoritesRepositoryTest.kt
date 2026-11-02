package app.omnisonic.core.data.repository

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.queue.OutboxQueue
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoritesRepositoryTest : DatabaseTestBase() {

    private fun repository() = FavoritesRepository(
        db = db,
        songRepository = SongRepository(db),
        outboxQueue = OutboxQueue(db, db.outboxDao()),
    )

    private val snapshot = SongRepository.SongSnapshot(title = "Unsynced title")

    @Test
    fun favoritesAnItemWithoutASyncedSong() = runDbTest {
        newProvider(id = 1)

        val songId = repository().setFavorite(1, "song-1", snapshot, favorite = true, now = 1_000)

        val song = songDao.selectById(songId)!!
        assertThat(song.title).isEqualTo("Unsynced title")
        assertThat(song.isFavorite).isTrue()
        assertThat(song.favoriteAt).isEqualTo(1_000)
        assertThat(outboxDao.selectByProvider(1).single().action).isEqualTo(app.omnisonic.provider.api.OutboxActions.FAVORITE)
    }

    @Test
    fun ensuresTheSongOnceAndKeepsLocalPlaybackState() = runDbTest {
        newProvider(id = 1)
        val repository = repository()
        val songId = repository.setFavorite(1, "song-1", snapshot, favorite = true, now = 1_000)
        songDao.addPlayCount(songId, delta = 3, now = 1_100)

        repository.setFavorite(1, "song-1", snapshot.copy(title = "Other"), favorite = false, now = 1_200)

        assertThat(songDao.selectByProvider(1)).hasSize(1)
        val song = songDao.selectById(songId)!!
        assertThat(song.playCount).isEqualTo(3)
        assertThat(song.isFavorite).isFalse()
    }
}
