package app.omnisonic.core.data.repository

import app.omnisonic.core.data.DatabaseTestBase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistRepositoryTest : DatabaseTestBase() {

    private val repository get() = PlaylistRepository(db, SongRepository(db))

    @Test
    fun addsAnItemWithoutASyncedSong() = runDbTest {
        val provider = newProvider(id = 1)
        val playlist = seedPlaylist(provider.id)

        val entryId = repository.addSong(
            playlistId = playlist.id,
            providerId = provider.id,
            externalId = "song-1",
            snapshot = SongRepository.SongSnapshot(
                title = "Queue title",
                artistName = "Queue artist",
                coverImageId = "cover-1",
                durationMs = 200_000,
            ),
            now = 1_000,
        )

        val entry = playlistEntryDao.selectByPlaylist(playlist.id).single()
        assertThat(entry.id).isEqualTo(entryId)
        assertThat(entry.externalId).isEqualTo("song-1")
        assertThat(entry.title).isEqualTo("Queue title")
        assertThat(entry.displayArtist).isEqualTo("Queue artist")
        assertThat(entry.songId).isNotNull()
        assertThat(songDao.selectById(entry.songId!!)!!.title).isEqualTo("Queue title")
        assertThat(playlistDao.selectById(playlist.id)!!.entryCount).isEqualTo(1)
    }

    @Test
    fun appendsAfterTheCurrentLastEntry() = runDbTest {
        val provider = newProvider(id = 1)
        val playlist = seedPlaylist(provider.id)
        seedPlaylistEntry(playlistId = playlist.id, providerId = provider.id)

        repository.addSong(
            playlistId = playlist.id,
            providerId = provider.id,
            externalId = "song-2",
            snapshot = SongRepository.SongSnapshot(title = "Second"),
            now = 2_000,
        )

        assertThat(playlistEntryDao.selectByPlaylist(playlist.id).map { it.position })
            .containsExactly(0, 1).inOrder()
    }
}
