package app.omnisonic.core.data

import app.omnisonic.core.data.browse.BrowseRepository
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowsePagingTest : DatabaseTestBase() {

    private val repository: BrowseRepository
        get() = BrowseRepository(db)

    private suspend fun seedAlbum(
        id: Long,
        providerId: Long,
        title: String,
        sortTitle: String? = null,
    ): AlbumEntity {
        albumDao.upsert(
            AlbumEntity(
                id = id,
                providerId = providerId,
                externalId = "alb-$id",
                title = title,
                sortTitle = sortTitle,
                updatedAt = 1_000,
            ),
        )
        return albumDao.selectById(id)!!
    }

    private suspend fun seedSong(id: Long, providerId: Long, title: String): SongEntity {
        songDao.upsert(
            SongEntity(
                id = id,
                providerId = providerId,
                externalId = "song-$id",
                title = title,
                updatedAt = 1_000,
            ),
        )
        return songDao.selectById(id)!!
    }

    private suspend fun seedPlaylist(id: Long, providerId: Long, title: String, hidden: Boolean): PlaylistEntity {
        playlistDao.upsert(
            PlaylistEntity(
                id = id,
                providerId = providerId,
                externalId = "pl-$id",
                title = title,
                type = 0,
                hidden = hidden,
                updatedAt = 1_000,
            ),
        )
        return playlistDao.selectById(id)!!
    }

    @Test
    fun albumsPageAlphabeticallyInWindowsWithoutDuplicateIds() = runDbTest {
        val provider = newProvider(id = 1)
        repeat(25) { index ->
            seedAlbum(id = index + 1L, providerId = provider.id, title = "Album ${index + 1}")
        }
        val repo = repository

        val page1 = repo.albums(offset = 0, limit = 10)
        val page2 = repo.albums(offset = 10, limit = 10)
        val page3 = repo.albums(offset = 20, limit = 10)

        assertThat(page1.items).hasSize(10)
        assertThat(page1.hasMore).isTrue()
        assertThat(page2.items).hasSize(10)
        assertThat(page2.hasMore).isTrue()
        assertThat(page3.items).hasSize(5)
        assertThat(page3.hasMore).isFalse()

        val unionIds = (page1.items + page2.items + page3.items).map { it.id }
        assertThat(unionIds).containsNoDuplicates()
        assertThat(unionIds).containsExactlyElementsIn((1L..25L).toList())

        val unionTitles = (page1.items + page2.items + page3.items).map { it.title }
        assertThat(unionTitles).containsNoDuplicates()
        assertThat(unionTitles).isEqualTo((1..25).map { "Album $it" }.sorted())
    }

    @Test
    fun albumHasMoreProbeNeedsOneRowPastTheWindow() = runDbTest {
        val provider = newProvider(id = 1)
        repeat(11) { index ->
            seedAlbum(id = index + 1L, providerId = provider.id, title = "Album ${index + 1}")
        }
        val repo = repository

        val full = repo.albums(offset = 0, limit = 11)
        val exactPage = repo.albums(offset = 0, limit = 10)

        assertThat(full.items).hasSize(11)
        assertThat(full.hasMore).isFalse()
        assertThat(exactPage.items).hasSize(10)
        assertThat(exactPage.hasMore).isTrue()
    }

    @Test
    fun albumOrderFallsBackToSortTitleThenTitle() = runDbTest {
        val provider = newProvider(id = 1)
        seedAlbum(id = 3, providerId = provider.id, title = "banana")
        seedAlbum(id = 1, providerId = provider.id, title = "Apple", sortTitle = "apple")
        seedAlbum(id = 2, providerId = provider.id, title = "Ápple tree", sortTitle = "apple tree")
        seedAlbum(id = 4, providerId = provider.id, title = "Cherry")

        val page = repository.albums(offset = 0, limit = 10)

        assertThat(page.items.map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun browseExcludesScopedProvidersAcrossEveryPage() = runDbTest {
        val kept = newProvider(id = 1)
        val excluded = newProvider(id = 2)
        repeat(8) { index -> seedAlbum(id = index + 1L, providerId = kept.id, title = "Kept $index") }
        repeat(8) { index -> seedAlbum(id = 100L + index, providerId = excluded.id, title = "Excluded $index") }
        val scope = ProfileScope(excludedProviderIds = listOf(excluded.id))
        val repo = repository

        val page1 = repo.albums(scope = scope, offset = 0, limit = 5)
        val page2 = repo.albums(scope = scope, offset = 5, limit = 5)

        assertThat(page1.items.map { it.providerId }).doesNotContain(excluded.id)
        assertThat(page2.items.map { it.providerId }).doesNotContain(excluded.id)
        assertThat((page1.items + page2.items).map { it.id }).containsNoDuplicates()
        assertThat(repo.albumCount(scope)).isEqualTo(8)
    }

    @Test
    fun songsPageAlphabeticallyAndCountMatches() = runDbTest {
        val provider = newProvider(id = 1)
        seedSong(id = 3, providerId = provider.id, title = "cherry")
        seedSong(id = 1, providerId = provider.id, title = "apple")
        seedSong(id = 2, providerId = provider.id, title = "banana")
        val repo = repository

        val page = repo.songs(offset = 0, limit = 2)

        assertThat(page.items.map { it.title }).containsExactly("apple", "banana").inOrder()
        assertThat(page.hasMore).isTrue()
        assertThat(repo.songCount()).isEqualTo(3)
    }

    @Test
    fun playlistsBrowseSkipsHiddenRows() = runDbTest {
        val provider = newProvider(id = 1)
        seedPlaylist(id = 1, providerId = provider.id, title = "Visible", hidden = false)
        seedPlaylist(id = 2, providerId = provider.id, title = "Hidden", hidden = true)
        val repo = repository

        val page = repo.playlists(offset = 0, limit = 10)

        assertThat(page.items.map { it.title }).containsExactly("Visible")
        assertThat(repo.playlistCount()).isEqualTo(1)
    }

    @Test
    fun artistsAndGenresPageAlphabetically() = runDbTest {
        val provider = newProvider(id = 1)
        artistDao.upsert(
            app.omnisonic.core.data.db.entity.ArtistEntity(id = 2L, providerId = provider.id, externalId = "a2", title = "zebra", updatedAt = 1_000),
        )
        artistDao.upsert(
            app.omnisonic.core.data.db.entity.ArtistEntity(id = 1L, providerId = provider.id, externalId = "a1", title = "alpha", updatedAt = 1_000),
        )
        genreDao.upsert(
            app.omnisonic.core.data.db.entity.GenreEntity(id = 2L, providerId = provider.id, externalId = "g2", title = "Rock", updatedAt = 1_000),
        )
        genreDao.upsert(
            app.omnisonic.core.data.db.entity.GenreEntity(id = 1L, providerId = provider.id, externalId = "g1", title = "Jazz", updatedAt = 1_000),
        )
        val repo = repository

        val artists = repo.artists(offset = 0, limit = 10)
        val genres = repo.genres(offset = 0, limit = 10)

        assertThat(artists.items.map { it.title }).containsExactly("alpha", "zebra").inOrder()
        assertThat(genres.items.map { it.title }).containsExactly("Jazz", "Rock").inOrder()
        assertThat(repo.artistCount()).isEqualTo(2)
        assertThat(repo.genreCount()).isEqualTo(2)
    }

    @Test
    fun offsetBeyondTheEndYieldsAnEmptyPageWithoutError() = runDbTest {
        val provider = newProvider(id = 1)
        repeat(3) { index -> seedAlbum(id = index + 1L, providerId = provider.id, title = "Album $index") }

        val page = repository.albums(offset = 50, limit = 10)

        assertThat(page.items).isEmpty()
        assertThat(page.hasMore).isFalse()
    }
}
