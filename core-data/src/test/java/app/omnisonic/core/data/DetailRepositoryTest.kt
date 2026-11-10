package app.omnisonic.core.data

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.detail.DetailRepository
import app.omnisonic.core.data.sync.LibraryInvalidation
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetailRepositoryTest : DatabaseTestBase() {

    private val repository: DetailRepository
        get() = DetailRepository(db, LibraryInvalidation(db))

    private suspend fun seedSong(
        id: Long,
        providerId: Long,
        title: String,
        albumId: Long? = null,
        discNo: Int? = null,
        trackNo: Int? = null,
        playCount: Int = 0,
        lastPlayed: Long? = null,
        genresJson: String? = null,
    ) {
        songDao.upsert(
            app.omnisonic.core.data.db.entity.SongEntity(
                id = id,
                providerId = providerId,
                externalId = "song-$id",
                albumId = albumId,
                title = title,
                discNo = discNo,
                trackNo = trackNo,
                playCount = playCount,
                lastPlayed = lastPlayed,
                genresJson = genresJson,
                updatedAt = 1_000,
            ),
        )
    }

    @Test
    fun albumTracksOrderDiscThenTrackWithUnknownNumbersLast() = runDbTest {
        val provider = newProvider(id = 1)
        seedSong(id = 1, providerId = provider.id, title = "b-side", albumId = 10, discNo = null, trackNo = null)
        seedSong(id = 2, providerId = provider.id, title = "closer", albumId = 10, discNo = 2, trackNo = 1)
        seedSong(id = 3, providerId = provider.id, title = "opener", albumId = 10, discNo = 1, trackNo = 1)
        seedSong(id = 4, providerId = provider.id, title = "second", albumId = 10, discNo = 1, trackNo = 2)
        seedSong(id = 5, providerId = provider.id, title = "unnumbered", albumId = 10, discNo = 1, trackNo = null)

        val page = repository.albumTracks(albumId = 10)

        assertThat(page.items.map { it.title })
            .containsExactly("opener", "second", "unnumbered", "closer", "b-side")
            .inOrder()
    }

    @Test
    fun albumTracksExcludeScopedProvidersAndPageWithoutDuplicates() = runDbTest {
        val kept = newProvider(id = 1)
        val excluded = newProvider(id = 2)
        repeat(12) { index ->
            seedSong(id = index + 1L, providerId = kept.id, title = "Kept $index", albumId = 10)
        }
        seedSong(id = 100, providerId = excluded.id, title = "Foreign", albumId = 10)
        val scope = ProfileScope(excludedProviderIds = listOf(excluded.id))

        val page1 = repository.albumTracks(scope = scope, albumId = 10, limit = 5)
        val page2 = repository.albumTracks(scope = scope, albumId = 10, offset = 5, limit = 5)

        assertThat(page1.items).hasSize(5)
        assertThat(page1.hasMore).isTrue()
        assertThat((page1.items + page2.items).map { it.providerId }).doesNotContain(excluded.id)
        assertThat((page1.items + page2.items).map { it.id }).containsNoDuplicates()
    }

    @Test
    fun artistTracksComeFromSongArtistsLinksMostPlayedFirst() = runDbTest {
        val provider = newProvider(id = 1)
        val artist = newArtist(id = 20, providerId = provider.id)
        seedSong(id = 1, providerId = provider.id, title = "hit", playCount = 5, lastPlayed = 900)
        seedSong(id = 2, providerId = provider.id, title = "classic", playCount = 5, lastPlayed = 100)
        seedSong(id = 3, providerId = provider.id, title = "rare")
        seedSong(id = 4, providerId = provider.id, title = "unlinked")
        linkSongArtist(provider.id, artistId = artist.id, songId = 1)
        linkSongArtist(provider.id, artistId = artist.id, songId = 2)
        linkSongArtist(provider.id, artistId = artist.id, songId = 3)

        val page = repository.artistTracks(artistId = artist.id)

        assertThat(page.items.map { it.title }).containsExactly("hit", "classic", "rare").inOrder()
    }

    @Test
    fun artistTracksCollapseMultiRoleCreditsIntoOneRow() = runDbTest {
        val provider = newProvider(id = 1)
        val artist = newArtist(id = 20, providerId = provider.id)
        seedSong(id = 1, providerId = provider.id, title = "duet")
        linkSongArtist(provider.id, artistId = artist.id, songId = 1, role = "main")
        linkSongArtist(provider.id, artistId = artist.id, songId = 1, role = "featured")

        val page = repository.artistTracks(artistId = artist.id)

        assertThat(page.items.map { it.id }).containsExactly(1L)
    }

    @Test
    fun artistAlbumsComeFromAlbumArtistsLinksAlphabetically() = runDbTest {
        val provider = newProvider(id = 1)
        val artist = newArtist(id = 20, providerId = provider.id)
        val zebra = newAlbum(id = 1, providerId = provider.id, title = "zebra")
        val alpha = newAlbum(id = 2, providerId = provider.id, title = "Alpha")
        val orphan = newAlbum(id = 3, providerId = provider.id, title = "Orphan")
        linkAlbumArtist(provider.id, artistId = artist.id, albumId = zebra.id)
        linkAlbumArtist(provider.id, artistId = artist.id, albumId = alpha.id, role = "main")
        linkAlbumArtist(provider.id, artistId = artist.id, albumId = alpha.id, role = "featured")

        val page = repository.artistAlbums(artistId = artist.id)

        assertThat(page.items.map { it.title }).containsExactly("Alpha", "zebra").inOrder()
        assertThat(page.items.map { it.id }).doesNotContain(orphan.id)
    }

    @Test
    fun genreTracksMatchOnlyTheExactQuotedJsonElement() = runDbTest {
        val provider = newProvider(id = 1)
        seedSong(id = 1, providerId = provider.id, title = "plain", genresJson = "[\"Jazz\"]")
        seedSong(id = 2, providerId = provider.id, title = "multi", genresJson = "[\"Rock\",\"Jazz\"]")
        seedSong(id = 3, providerId = provider.id, title = "prefixed", genresJson = "[\"Acid Jazz\"]")
        seedSong(id = 4, providerId = provider.id, title = "suffixed", genresJson = "[\"Jazz Fusion\"]")
        seedSong(id = 5, providerId = provider.id, title = "untagged")
        val genre = genreDao.upsert(
            app.omnisonic.core.data.db.entity.GenreEntity(
                id = 0,
                providerId = provider.id,
                externalId = "Jazz",
                title = "Jazz",
                updatedAt = 1_000,
            ),
        )

        val page = repository.genreTracks(genreId = genre)

        assertThat(page.items.map { it.title }).containsExactly("multi", "plain").inOrder()
    }

    @Test
    fun genreTracksForAMissingGenreYieldAnEmptyPage() = runDbTest {
        val provider = newProvider(id = 1)

        val page = repository.genreTracks(genreId = 999)

        assertThat(page.items).isEmpty()
        assertThat(page.hasMore).isFalse()
    }

    @Test
    fun playlistEntriesKeepPositionOrderAndRespectScope() = runDbTest {
        val kept = newProvider(id = 1)
        val excluded = newProvider(id = 2)
        val playlist = seedPlaylist(providerId = kept.id)
        listOf(2, 0, 1).forEachIndexed { index, position ->
            playlistEntryDao.upsert(
                PlaylistEntryEntity(
                    id = 0,
                    playlistId = playlist.id,
                    providerId = kept.id,
                    position = position,
                    externalId = "entry-$index",
                    title = "Entry $position",
                    addedAt = 1_000,
                ),
            )
        }
        playlistEntryDao.upsert(
            PlaylistEntryEntity(
                id = 0,
                playlistId = playlist.id,
                providerId = excluded.id,
                position = 3,
                externalId = "foreign",
                title = "Foreign",
                addedAt = 1_000,
            ),
        )
        val scope = ProfileScope(excludedProviderIds = listOf(excluded.id))

        val page = repository.playlistEntries(scope = scope, playlistId = playlist.id)

        assertThat(page.items.map { it.title }).containsExactly("Entry 0", "Entry 1", "Entry 2").inOrder()
    }

    @Test
    fun headersResolveByIdAndReturnNullForMissingRows() = runDbTest {
        val provider = newProvider(id = 1)
        val album = newAlbum(id = 1, providerId = provider.id)
        val artist = newArtist(id = 2, providerId = provider.id)
        val genreId = genreDao.upsert(
            app.omnisonic.core.data.db.entity.GenreEntity(id = 0, providerId = provider.id, externalId = "g", title = "Rock", updatedAt = 1_000),
        )
        val playlist = seedPlaylist(providerId = provider.id)

        assertThat(repository.album(album.id)?.id).isEqualTo(album.id)
        assertThat(repository.artist(artist.id)?.id).isEqualTo(artist.id)
        assertThat(repository.genre(genreId)?.id).isEqualTo(genreId)
        assertThat(repository.playlist(playlist.id)?.id).isEqualTo(playlist.id)
        assertThat(repository.album(4242)).isNull()
        assertThat(repository.artist(4242)).isNull()
        assertThat(repository.genre(4242)).isNull()
        assertThat(repository.playlist(4242)).isNull()
    }

    @Test
    fun pagingWindowsProbeOnePastTheWindow() = runDbTest {
        val provider = newProvider(id = 1)
        repeat(11) { index ->
            seedSong(id = index + 1L, providerId = provider.id, title = "Song $index", albumId = 10)
        }

        val exact = repository.albumTracks(albumId = 10, limit = 11)
        val short = repository.albumTracks(albumId = 10, limit = 10)

        assertThat(exact.items).hasSize(11)
        assertThat(exact.hasMore).isFalse()
        assertThat(short.items).hasSize(10)
        assertThat(short.hasMore).isTrue()
    }
}
