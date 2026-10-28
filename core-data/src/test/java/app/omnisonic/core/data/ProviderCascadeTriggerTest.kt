package app.omnisonic.core.data

import androidx.room.withTransaction
import app.omnisonic.core.data.db.ProviderCascadeTriggers
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderCascadeTriggerTest : DatabaseTestBase() {

    @Test
    fun deletingProviderWithinOneTransactionCascadesEveryOwnedTable() = runDbTest {
        val provider = newProvider(id = 1)
        val otherProvider = newProvider(id = 2)
        newProvider(id = 3)
        seedLibrarySource(providerId = 1)
        val song = newSong(id = 10, providerId = 1, albumId = null, title = "orphan")
        val otherSong = newSong(id = 11, providerId = 3)
        linkSongArtist(providerId = 1, artistId = 20, songId = song.id)
        seedPlaylist(providerId = 1)
        seedInternetRadio(providerId = 1)
        seedChildScrollView(providerId = 1, songId = song.id)
        seedPlaybackEvent(providerId = 1)
        seedOutboxItem(providerId = 1)
        seedOfflineFile(providerId = 1)
        seedGenre(providerId = 1)
        newArtist(id = 20, providerId = 1)
        seedSyncStatus(providerId = 1)
        seedSavedFilter()

        db.withTransaction {
            providerDao.deleteById(1)
        }

        assertThat(providerDao.selectAll().map { it.id }).containsExactly(2L, 3L)
        assertThat(songDao.selectAll()).containsExactly(otherSong)
        assertThat(songArtistDao.selectBySong(song.id)).isEmpty()
        assertThat(playlistDao.selectAll()).isEmpty()
        assertThat(playlistEntryDao.selectByPlaylist(0)).isEmpty()
        assertThat(internetRadioDao.selectAll()).isEmpty()
        assertThat(playbackEventDao.selectByProvider(1)).isEmpty()
        assertThat(outboxDao.selectByProvider(1)).isEmpty()
        assertThat(offlineFileDao.selectByProvider(1)).isEmpty()
        assertThat(genreDao.selectAll()).isEmpty()
        assertThat(artistDao.selectAll()).isEmpty()
        assertThat(syncStatusDao.selectByProvider(1)).isNull()
        assertThat(savedFilterDao.selectAll()).isNotEmpty()
        assertThat(providerRow(3).id).isEqualTo(3L)
    }

    @Test
    fun deletingPlaylistRemovesEntriesOnly() = runDbTest {
        val provider = newProvider(id = 1)
        val playlist = seedPlaylist(providerId = provider.id)
        seedPlaylistEntry(playlistId = playlist.id, providerId = provider.id)
        val otherProvider = newProvider(id = 2)

        playlistDao.deleteById(playlist.id)

        assertThat(playlistEntryDao.selectByPlaylist(playlist.id)).isEmpty()
        assertThat(otherProvider.id).isEqualTo(2)
    }

    private suspend fun seedChildScrollView(providerId: Long, songId: Long) {
        seedChapter(providerId = providerId, songId = songId)
    }
}
