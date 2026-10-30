package app.omnisonic.core.data.sync

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.db.entity.SyncStatusEntity
import app.omnisonic.core.data.json.JsonText
import app.omnisonic.provider.api.AlbumListType
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.ProviderAlbum
import app.omnisonic.provider.api.ProviderAlbumRequest
import app.omnisonic.provider.api.ProviderArtist
import app.omnisonic.provider.api.ProviderBookmark
import app.omnisonic.provider.api.ProviderCapabilities
import app.omnisonic.provider.api.ProviderDirectory
import app.omnisonic.provider.api.ProviderGenre
import app.omnisonic.provider.api.ProviderMediaType
import app.omnisonic.provider.api.ProviderPlaylist
import app.omnisonic.provider.api.ProviderPlaylistEntry
import app.omnisonic.provider.api.ProviderPlaylistSummary
import app.omnisonic.provider.api.ProviderRadio
import app.omnisonic.provider.api.ProviderSong
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeMediaProvider : MediaProvider {

    val artists = mutableListOf<ProviderArtist>()
    val albumPages = mutableMapOf<Int, List<ProviderAlbum>>()
    val requestedOffsets = mutableListOf<Int>()
    val albumDetails = mutableMapOf<String, ProviderAlbum>()
    val directories = mutableMapOf<String, ProviderDirectory>()
    val genres = mutableListOf<ProviderGenre>()
    val radios = mutableListOf<ProviderRadio>()
    val playlists = mutableListOf<ProviderPlaylistSummary>()
    val playlistDetails = mutableMapOf<String, ProviderPlaylist>()
    val bookmarks = mutableListOf<ProviderBookmark>()

    override suspend fun probe(): ProviderCapabilities = ProviderCapabilities()

    override suspend fun listArtists(): List<ProviderArtist> = artists.toList()

    override suspend fun browseDirectory(externalId: String): ProviderDirectory =
        directories.getValue(externalId)

    override suspend fun albumDetails(albumExternalId: String): ProviderAlbum? = albumDetails[albumExternalId]

    override suspend fun songDetails(externalId: String): ProviderSong? = null

    override suspend fun listAlbums(request: ProviderAlbumRequest): List<ProviderAlbum> {
        requestedOffsets += request.offset
        return albumPages[request.offset].orEmpty()
    }

    override suspend fun listGenres(): List<ProviderGenre> = genres.toList()

    override suspend fun listRadios(): List<ProviderRadio> = radios.toList()

    override suspend fun listPlaylists(): List<ProviderPlaylistSummary> = playlists.toList()

    override suspend fun getPlaylist(playlistExternalId: String): ProviderPlaylist? = playlistDetails[playlistExternalId]

    override suspend fun listBookmarks(): List<ProviderBookmark> = bookmarks.toList()

    override suspend fun searchArtists(query: String, offset: Int, count: Int): List<ProviderArtist> = emptyList()

    override suspend fun searchAlbums(query: String, offset: Int, count: Int): List<ProviderAlbum> = emptyList()

    override suspend fun searchSongs(query: String, offset: Int, count: Int): List<ProviderSong> = emptyList()

    override fun streamUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? = null

    override fun coverArtUrl(imageId: String?, sizePx: Int?): String? = null

    override fun downloadUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? = null

    override suspend fun submitPlays(externalId: String, playCount: Int, lastPlayedAtMs: Long?): Int = 0

    override suspend fun sendNowPlaying(externalId: String, playedMs: Long): Boolean = false

    override suspend fun setFavorite(mediaType: ProviderMediaType, externalId: String, favorite: Boolean): Boolean = false

    override suspend fun setUserRating(mediaType: ProviderMediaType, externalId: String, rating: Int): Boolean = false

    override suspend fun saveBookmark(externalId: String, positionMs: Long): Boolean = false

    override suspend fun deleteBookmark(externalId: String): Boolean = false

    override suspend fun similarSongs(externalId: String, count: Int): List<ProviderSong> = emptyList()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubsonicLibrarySyncerTest : DatabaseTestBase() {

    private fun syncer(provider: MediaProvider, nowMs: () -> Long = { 7_000L }) =
        SubsonicLibrarySyncer(db, provider, providerId = 1L, nowMs = nowMs)

    @Test
    fun `full sync merges server fields while preserving local playback state`() = runDbTest {
        newProvider(id = 1)
        artistDao.upsert(
            ArtistEntity(
                id = 10, providerId = 1, externalId = "art-1", title = "Old name",
                playCount = 5, isFavorite = true, favoriteAt = 111, updatedAt = 1_000,
            ),
        )
        albumDao.upsert(
            AlbumEntity(
                id = 20, providerId = 1, externalId = "alb-1", title = "Old title",
                playCount = 3, userRating = 4, updatedAt = 1_000,
            ),
        )
        songDao.upsert(
            SongEntity(
                id = 30, providerId = 1, externalId = "song-1", albumId = 20, title = "Old song title",
                playCount = 7, userRating = 4, isFavorite = true, favoriteAt = 222,
                resumePointMs = 1_000, replayGainTrack = -1.5, clipStartMs = 500,
                sampleRate = 44_100, updatedAt = 1_000,
            ),
        )
        songDao.upsert(
            SongEntity(
                id = 31, providerId = 1, externalId = "song-3", albumId = 20, title = "Unbookmarked",
                resumePointMs = 9_000, updatedAt = 1_000,
            ),
        )

        val provider = FakeMediaProvider().apply {
            artists += ProviderArtist(externalId = "art-1", title = "Alpha", albumCount = 2, songCount = 5)
            albumPages[0] = listOf(
                ProviderAlbum(externalId = "alb-1", title = "Nik", artistExternalId = "art-1",
                    artistName = "Alpha", year = 1980, songCount = 2, genres = listOf("rock"), createdAtMs = 1_000),
            )
            albumDetails["alb-1"] = albumPages.getValue(0).single()
            directories["alb-1"] = ProviderDirectory(
                externalId = "alb-1",
                childSongs = listOf(
                    ProviderSong(externalId = "song-1", title = "Bodhisattva", artistExternalId = "art-1",
                        artistName = "Alpha", trackNo = 1, discNo = 1, year = 1973, genre = "rock",
                        durationMs = 191_000, filePath = "sd/b.flac", codec = "flac", bitrateKbps = 1000),
                    ProviderSong(externalId = "song-2", title = "Rikki", artistExternalId = "art-1",
                        trackNo = 2, durationMs = 260_000, filePath = "sd/r.flac", codec = "flac"),
                ),
            )
            genres += ProviderGenre(externalId = "rock", songCount = 9, albumCount = 2)
            radios += ProviderRadio(externalId = "radio-1", name = "Jazz 24", streamUrl = "https://j.example/s")
            playlists += ProviderPlaylistSummary(externalId = "pl-1", name = "Fireside", songCount = 1)
            playlistDetails["pl-1"] = ProviderPlaylist(
                summary = ProviderPlaylistSummary(externalId = "pl-1", name = "Fireside", songCount = 1),
                entries = listOf(
                    ProviderPlaylistEntry(externalId = "song-1", title = "Bodhisattva", durationMs = 191_000),
                ),
            )
            bookmarks += ProviderBookmark(entryExternalId = "song-1", positionMs = 42_000)
        }

        val outcome = syncer(provider).runFullSync()

        assertThat(outcome.albumsProcessed).isEqualTo(1)
        val artist = artistDao.selectByProviderAndExternal(1, "art-1")!!
        assertThat(artist.id).isEqualTo(10)
        assertThat(artist.title).isEqualTo("Alpha")
        assertThat(artist.albumCount).isEqualTo(2)
        assertThat(artist.playCount).isEqualTo(5)
        assertThat(artist.isFavorite).isTrue()

        val album = albumDao.selectByProviderAndExternal(1, "alb-1")!!
        assertThat(album.id).isEqualTo(20)
        assertThat(album.title).isEqualTo("Nik")
        assertThat(album.genresJson?.let { JsonText.decodeStringList(it) }).containsExactly("rock")
        assertThat(album.playCount).isEqualTo(3)
        assertThat(album.userRating).isEqualTo(4)

        val song1 = songDao.selectByProviderAndExternal(1, "song-1")!!
        assertThat(song1.id).isEqualTo(30)
        assertThat(song1.title).isEqualTo("Bodhisattva")
        assertThat(song1.albumId).isEqualTo(20)
        assertThat(song1.playCount).isEqualTo(7)
        assertThat(song1.userRating).isEqualTo(4)
        assertThat(song1.isFavorite).isTrue()
        assertThat(song1.resumePointMs).isEqualTo(42_000)
        assertThat(song1.replayGainTrack).isEqualTo(-1.5)
        assertThat(song1.clipStartMs).isEqualTo(500)
        assertThat(song1.sampleRate).isEqualTo(44_100)
        assertThat(song1.codec).isEqualTo("flac")
        assertThat(song1.bitrateKbps).isEqualTo(1000)
        assertThat(song1.genresJson?.let { JsonText.decodeStringList(it) }).containsExactly("rock")

        val song2 = songDao.selectByProviderAndExternal(1, "song-2")!!
        assertThat(song2.albumId).isEqualTo(20)
        assertThat(song2.trackNo).isEqualTo(2)
        val song3 = songDao.selectByProviderAndExternal(1, "song-3")!!
        assertThat(song3.resumePointMs).isEqualTo(9_000)

        assertThat(songArtistDao.selectBySong(30).single().artistId).isEqualTo(10)
        assertThat(albumArtistDao.selectByAlbum(20).single().artistId).isEqualTo(10)

        val playlist = playlistDao.selectByProvider(1).single()
        assertThat(playlist.title).isEqualTo("Fireside")
        assertThat(playlist.entryCount).isEqualTo(1)
        val entry = playlistEntryDao.selectByPlaylist(playlist.id).single()
        assertThat(entry.entryOrder).isEqualTo(0)
        assertThat(entry.songId).isEqualTo(30)

        assertThat(genreDao.selectByProvider(1).single().songCount).isEqualTo(9)
        assertThat(internetRadioDao.selectByProvider(1).single().streamUrl).isEqualTo("https://j.example/s")

        val status = syncStatusDao.selectByProvider(1)!!
        assertThat(status.lastSyncResult).isEqualTo(SubsonicLibrarySyncer.RESULT_OK)
        assertThat(status.lastFullSyncAt).isEqualTo(7_000)
        assertThat(status.lastSyncCount).isEqualTo(1)
    }

    @Test
    fun `diff sync processes only newer albums and stops at older pages`() = runDbTest {
        newProvider(id = 1)
        syncStatusDao.upsert(
            SyncStatusEntity(id = 0, providerId = 1, lastDiffSyncAt = 5_000, lastSyncResult = 1),
        )

        val provider = FakeMediaProvider().apply {
            albumPages[0] = listOf(
                ProviderAlbum(externalId = "alb-new", title = "Fresh", createdAtMs = 9_000),
                ProviderAlbum(externalId = "alb-old", title = "Stale", createdAtMs = 1_000),
            )
            albumDetails["alb-new"] = ProviderAlbum(externalId = "alb-new", title = "Fresh")
            directories["alb-new"] = ProviderDirectory(externalId = "alb-new")
        }

        val outcome = syncer(provider).runDiffSync()

        assertThat(outcome.albumsProcessed).isEqualTo(1)
        assertThat(provider.requestedOffsets).containsExactly(0)
        assertThat(albumDao.selectByProvider(1).map { it.externalId }).containsExactly("alb-new")

        val status = syncStatusDao.selectByProvider(1)!!
        assertThat(status.lastDiffSyncAt).isEqualTo(7_000)
        assertThat(status.lastSyncResult).isEqualTo(SubsonicLibrarySyncer.RESULT_OK)
    }

    @Test
    fun `diff sync with no prior watermark walks everything like a full sync`() = runDbTest {
        newProvider(id = 1)
        val provider = FakeMediaProvider().apply {
            albumPages[0] = listOf(
                ProviderAlbum(externalId = "alb-a", title = "A", createdAtMs = 1),
                ProviderAlbum(externalId = "alb-b", title = "B", createdAtMs = 2),
            )
            albumDetails["alb-a"] = ProviderAlbum(externalId = "alb-a", title = "A")
            albumDetails["alb-b"] = ProviderAlbum(externalId = "alb-b", title = "B")
            directories["alb-a"] = ProviderDirectory(externalId = "alb-a")
            directories["alb-b"] = ProviderDirectory(externalId = "alb-b")
        }

        val outcome = syncer(provider).runDiffSync()

        assertThat(outcome.albumsProcessed).isEqualTo(2)
        assertThat(provider.requestedOffsets).containsExactly(0)
        assertThat(albumDao.selectByProvider(1)).hasSize(2)
    }

    @Test
    fun `failed sync records failure status and rethrows`() = runDbTest {
        newProvider(id = 1)
        val provider = FakeMediaProvider()
        val failing = object : MediaProvider by provider {
            override suspend fun listArtists(): List<app.omnisonic.provider.api.ProviderArtist> =
                throw IllegalStateException("boom")
        }

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { syncer(failing).runFullSync() }
        }

        val status = syncStatusDao.selectByProvider(1)!!
        assertThat(status.lastSyncResult).isEqualTo(SubsonicLibrarySyncer.RESULT_FAILED)
        assertThat(status.lastSyncMessage).isEqualTo("boom")
        assertThat(status.lastFullSyncAt).isNull()
    }

    @Test
    fun `full sync pages until the server returns a short page`() = runDbTest {
        newProvider(id = 1)
        val pageSize = ProviderAlbumRequest.DEFAULT_PAGE_SIZE
        val pageOne = (0 until pageSize).map { index ->
            ProviderAlbum(externalId = "alb-$index", title = "Album $index")
        }
        val provider = FakeMediaProvider().apply {
            albumPages[0] = pageOne
            albumPages[pageSize] = listOf(ProviderAlbum(externalId = "alb-last", title = "Last"))
            pageOne.forEach { album ->
                albumDetails[album.externalId] = album
                directories[album.externalId] = ProviderDirectory(externalId = album.externalId)
            }
            albumDetails["alb-last"] = ProviderAlbum(externalId = "alb-last", title = "Last")
            directories["alb-last"] = ProviderDirectory(externalId = "alb-last")
        }

        val outcome = syncer(provider).runFullSync()

        assertThat(outcome.albumsProcessed).isEqualTo(pageSize + 1)
        assertThat(provider.requestedOffsets).containsExactly(0, pageSize)
        assertThat(albumDao.selectByProvider(1)).hasSize(pageSize + 1)
    }
}
