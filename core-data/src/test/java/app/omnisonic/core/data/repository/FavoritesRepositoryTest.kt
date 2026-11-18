package app.omnisonic.core.data.repository

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.core.data.sync.OutboxSyncControl
import app.omnisonic.core.data.sync.OutboxSyncReport
import app.omnisonic.core.data.sync.OutboxSyncState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoritesRepositoryTest : DatabaseTestBase() {

    private val sync = RecordingOutboxSync()

    private fun repository() = FavoritesRepository(
        db = db,
        songRepository = SongRepository(db),
        outboxQueue = OutboxQueue(db, db.outboxDao()),
        outboxSync = sync,
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
        assertThat(sync.requests).isEqualTo(1)
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

private class RecordingOutboxSync : OutboxSyncControl {

    private val _state = MutableStateFlow(OutboxSyncState())
    override val state: StateFlow<OutboxSyncState> = _state

    var requests = 0

    override fun requestDrain() {
        requests++
    }

    override fun retryNow() = Unit

    override suspend fun drainNow(): OutboxSyncReport? = null

    override suspend fun retryAllNow(): OutboxSyncReport? = null
}
