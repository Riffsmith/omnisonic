package app.omnisonic.core.data.sync

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.ChapterEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.provider.api.AlbumListType
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.ProviderAlbum
import app.omnisonic.provider.api.ProviderAlbumDetails
import app.omnisonic.provider.api.ProviderAlbumRequest
import app.omnisonic.provider.api.ProviderArtist
import app.omnisonic.provider.api.ProviderCapabilities
import app.omnisonic.provider.api.ProviderDirectory
import app.omnisonic.provider.api.ProviderMediaType
import app.omnisonic.provider.api.ProviderPlaylist
import app.omnisonic.provider.api.ProviderPlaylistSummary
import app.omnisonic.provider.api.ProviderSong
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class MutableSyncProvider : MediaProvider {

    val artists = mutableListOf<ProviderArtist>()
    val albums = mutableListOf<ProviderAlbum>()
    val songsByAlbum = mutableMapOf<String, List<ProviderSong>>()
    val failingAlbums = mutableSetOf<String>()
    val playlists = mutableListOf<ProviderPlaylistSummary>()
    val playlistDetails = mutableMapOf<String, ProviderPlaylist>()

    override suspend fun probe(): ProviderCapabilities = ProviderCapabilities()

    override suspend fun listArtists(): List<ProviderArtist> = artists.toList()

    override suspend fun browseDirectory(externalId: String): ProviderDirectory =
        ProviderDirectory(externalId = externalId)

    override suspend fun getAlbum(albumExternalId: String): ProviderAlbumDetails? {
        if (albumExternalId in failingAlbums) return null
        val album = albums.firstOrNull { it.externalId == albumExternalId } ?: return null
        return ProviderAlbumDetails(album, songsByAlbum[albumExternalId].orEmpty())
    }

    override suspend fun songDetails(externalId: String): ProviderSong? = null

    override suspend fun listAlbums(request: ProviderAlbumRequest): List<ProviderAlbum> =
        if (request.type == AlbumListType.ALPHABETICAL_BY_NAME && request.offset == 0) albums.toList() else emptyList()

    override suspend fun listGenres(): List<app.omnisonic.provider.api.ProviderGenre> = emptyList()

    override suspend fun listRadios(): List<app.omnisonic.provider.api.ProviderRadio> = emptyList()

    override suspend fun listPlaylists(): List<ProviderPlaylistSummary> = playlists.toList()

    override suspend fun getPlaylist(playlistExternalId: String): ProviderPlaylist? =
        playlistDetails[playlistExternalId]

    override suspend fun listBookmarks(): List<app.omnisonic.provider.api.ProviderBookmark> = emptyList()

    override suspend fun searchArtists(query: String, offset: Int, count: Int): List<ProviderArtist> = emptyList()

    override suspend fun searchAlbums(query: String, offset: Int, count: Int): List<ProviderAlbum> = emptyList()

    override suspend fun searchSongs(query: String, offset: Int, count: Int): List<ProviderSong> = emptyList()

    override fun streamUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? = null

    override fun coverArtUrl(imageId: String?, sizePx: Int?): String? = null

    override fun downloadUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? = null

    override suspend fun submitPlays(externalId: String, timestamps: List<Long>): Int = timestamps.size

    override suspend fun sendNowPlaying(externalId: String, playedMs: Long): Boolean = false

    override suspend fun setFavorite(mediaType: ProviderMediaType, externalId: String, favorite: Boolean): Boolean = false

    override suspend fun setUserRating(mediaType: ProviderMediaType, externalId: String, rating: Int): Boolean = false

    override suspend fun saveBookmark(externalId: String, positionMs: Long): Boolean = false

    override suspend fun deleteBookmark(externalId: String): Boolean = false

    override suspend fun similarSongs(externalId: String, count: Int): List<ProviderSong> = emptyList()
}

/**
 * Run-stamp pruning (FIX-01, D-10) with the FIX-02 safety guards: a full sync
 * records the known song count before its walk, collects every song and listed
 * album id the server reports, and skips pruning entirely when the response
 * looks empty or incomplete — empty seen-set, less than half the known songs
 * seen, or a listed album whose fetch failed. With the guards in place, rows
 * without a stamp join the unseen set, and rated, resumed or clipped songs are
 * tombstoned rather than deleted (favorite status alone does not block).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubsonicLibrarySyncerPruneTest : DatabaseTestBase() {

    private fun syncer(provider: MutableSyncProvider) =
        SubsonicLibrarySyncer(db, provider, providerId = 1L)

    @Test
    fun `sync stamps every row it writes with the run id`() = runDbTest {
        newProvider(id = 1)
        // A played local materialisation from before stamping existed.
        songDao.upsert(
            SongEntity(id = 99, providerId = 1, externalId = "song-local", title = "Local", playCount = 1, updatedAt = 1_000),
        )

        val provider = MutableSyncProvider().apply {
            artists += ProviderArtist(externalId = "art-1", title = "Alpha")
            albums += ProviderAlbum(externalId = "alb-1", title = "Album", artistExternalId = "art-1")
            songsByAlbum["alb-1"] = listOf(ProviderSong(externalId = "song-1", title = "One"))
            playlists += ProviderPlaylistSummary(externalId = "pl-1", name = "List")
            playlistDetails["pl-1"] = ProviderPlaylist(
                ProviderPlaylistSummary(externalId = "pl-1", name = "List"),
                entries = emptyList(),
            )
        }

        val outcome = syncer(provider).runFullSync()

        assertThat(outcome.message).isEqualTo("ok")
        assertThat(songDao.selectByProviderAndExternal(1, "song-1")!!.lastSeenSyncId).isEqualTo(1)
        assertThat(albumDao.selectByProviderAndExternal(1, "alb-1")!!.lastSeenSyncId).isEqualTo(1)
        assertThat(artistDao.selectByProviderAndExternal(1, "art-1")!!.lastSeenSyncId).isEqualTo(1)
        assertThat(playlistDao.selectByProviderAndExternal(1, "pl-1")!!.lastSeenSyncId).isEqualTo(1)
        val local = songDao.selectByProviderAndExternal(1, "song-local")!!
        assertThat(local.lastSeenSyncId).isNull()
        assertThat(local.missingOnServer).isTrue()
    }

    @Test
    fun `a second run stamps a fresh id and rows of the older run are prunable`() = runDbTest {
        newProvider(id = 1)
        val provider = MutableSyncProvider().apply {
            albums += ProviderAlbum(externalId = "alb-1", title = "Album")
            songsByAlbum["alb-1"] = listOf(
                ProviderSong(externalId = "song-1", title = "One"),
                ProviderSong(externalId = "song-2", title = "Two"),
            )
        }
        syncer(provider).runFullSync()
        assertThat(songDao.selectByProviderAndExternal(1, "song-1")!!.lastSeenSyncId).isEqualTo(1)

        provider.songsByAlbum["alb-1"] = listOf(ProviderSong(externalId = "song-1", title = "One"))
        val outcome = syncer(provider).runFullSync()

        assertThat(outcome.message).isEqualTo("ok")
        val survivor = songDao.selectByProviderAndExternal(1, "song-1")!!
        assertThat(survivor.lastSeenSyncId).isEqualTo(2)
        assertThat(songDao.selectByProviderAndExternal(1, "song-2")).isNull()
    }

    @Test
    fun `null-stamp rows join the unseen set once the safety guards pass`() = runDbTest {
        newProvider(id = 1)
        songDao.upsert(
            SongEntity(id = 30, providerId = 1, externalId = "song-played", title = "Played", playCount = 5, updatedAt = 1_000),
        )
        songDao.upsert(SongEntity(id = 31, providerId = 1, externalId = "song-plain", title = "Plain", updatedAt = 1_000))
        albumDao.upsert(AlbumEntity(id = 40, providerId = 1, externalId = "alb-orphan", title = "Orphan", updatedAt = 1_000))
        artistDao.upsert(ArtistEntity(id = 50, providerId = 1, externalId = "art-orphan", title = "Orphan", updatedAt = 1_000))
        playlistDao.upsert(PlaylistEntity(id = 60, providerId = 1, externalId = "pl-orphan", title = "Orphan", type = 0, updatedAt = 1_000))

        val provider = MutableSyncProvider().apply {
            albums += ProviderAlbum(externalId = "alb-1", title = "Album")
            songsByAlbum["alb-1"] = listOf(ProviderSong(externalId = "song-1", title = "One"))
        }
        val outcome = syncer(provider).runFullSync()

        assertThat(outcome.message).isEqualTo("ok")
        val played = songDao.selectByProviderAndExternal(1, "song-played")!!
        assertThat(played.missingOnServer).isTrue()
        assertThat(played.playCount).isEqualTo(5)
        assertThat(played.lastSeenSyncId).isNull()
        assertThat(songDao.selectByProviderAndExternal(1, "song-plain")).isNull()
        assertThat(albumDao.selectByProviderAndExternal(1, "alb-orphan")).isNull()
        assertThat(artistDao.selectByProviderAndExternal(1, "art-orphan")).isNull()
        assertThat(playlistDao.selectByProviderAndExternal(1, "pl-orphan")).isNull()
    }

    @Test
    fun `empty album listing skips pruning and the outcome explains why`() = runDbTest {
        newProvider(id = 1)
        repeat(10) { index ->
            songDao.upsert(
                SongEntity(
                    id = 100L + index,
                    providerId = 1,
                    externalId = "song-$index",
                    title = "Song $index",
                    lastSeenSyncId = 1L,
                    updatedAt = 1_000,
                ),
            )
        }

        val outcome = syncer(MutableSyncProvider()).runFullSync()

        assertThat(outcome.message).isEqualTo("prune skipped: server reported no albums or songs")
        val songs = songDao.selectByProvider(1)
        assertThat(songs).hasSize(10)
        assertThat(songs.none { it.missingOnServer }).isTrue()
        assertThat(songs.all { it.lastSeenSyncId == 1L }).isTrue()

        val status = syncStatusDao.selectByProvider(1)!!
        assertThat(status.lastSyncResult).isEqualTo(SubsonicLibrarySyncer.RESULT_OK)
        assertThat(status.lastSyncMessage).isEqualTo("prune skipped: server reported no albums or songs")
    }

    @Test
    fun `one failed album fetch skips pruning`() = runDbTest {
        newProvider(id = 1)
        val provider = MutableSyncProvider().apply {
            albums += listOf(
                ProviderAlbum(externalId = "alb-1", title = "Healthy"),
                ProviderAlbum(externalId = "alb-2", title = "Broken"),
            )
            songsByAlbum["alb-1"] = (0 until 10).map { index ->
                ProviderSong(externalId = "song-$index", title = "Song $index")
            }
            songsByAlbum["alb-2"] = listOf(
                ProviderSong(externalId = "song-a", title = "A"),
                ProviderSong(externalId = "song-b", title = "B"),
            )
        }
        syncer(provider).runFullSync()
        assertThat(songDao.selectByProvider(1)).hasSize(12)

        // The next walk still lists both albums, but alb-2's fetch returns null.
        provider.failingAlbums += "alb-2"

        val outcome = syncer(provider).runFullSync()

        assertThat(outcome.message).isEqualTo("prune skipped: album details unavailable for a listed album")
        assertThat(songDao.selectByProvider(1)).hasSize(12)
        assertThat(songDao.selectByProvider(1).none { it.missingOnServer }).isTrue()
        assertThat(songDao.selectByProviderAndExternal(1, "song-a")).isNotNull()
        assertThat(songDao.selectByProviderAndExternal(1, "song-b")).isNotNull()
    }

    @Test
    fun `a listed album counts as seen even when its fetch fails`() = runDbTest {
        newProvider(id = 1)
        val provider = MutableSyncProvider().apply {
            albums += ProviderAlbum(externalId = "alb-1", title = "Album")
            failingAlbums += "alb-1"
        }

        val outcome = syncer(provider).runFullSync()

        // The listing proves the server responded, so the empty-walk guard must
        // not fire; the failed fetch is the reason pruning is skipped.
        assertThat(outcome.message).isEqualTo("prune skipped: album details unavailable for a listed album")
        assertThat(songDao.selectByProvider(1)).isEmpty()
    }

    @Test
    fun `a shrink below half the known songs skips pruning`() = runDbTest {
        newProvider(id = 1)
        val provider = MutableSyncProvider().apply {
            albums += ProviderAlbum(externalId = "alb-1", title = "Album")
            songsByAlbum["alb-1"] = (0 until 10).map { index ->
                ProviderSong(externalId = "song-$index", title = "Song $index")
            }
        }
        syncer(provider).runFullSync()
        assertThat(songDao.selectByProvider(1)).hasSize(10)

        // Only 4 of the 10 known songs come back: the response looks incomplete.
        provider.songsByAlbum["alb-1"] = (0 until 4).map { index ->
            ProviderSong(externalId = "song-$index", title = "Song $index")
        }

        val outcome = syncer(provider).runFullSync()

        assertThat(outcome.message).isEqualTo("prune skipped: server reported 4 of 10 known songs")
        val songs = songDao.selectByProvider(1)
        assertThat(songs).hasSize(10)
        assertThat(songs.none { it.missingOnServer }).isTrue()
    }

    @Test
    fun `normal shrink where one of two songs disappears still prunes`() = runDbTest {
        newProvider(id = 1)
        val provider = MutableSyncProvider().apply {
            albums += ProviderAlbum(externalId = "alb-1", title = "Album")
            songsByAlbum["alb-1"] = listOf(
                ProviderSong(externalId = "song-1", title = "One"),
                ProviderSong(externalId = "song-2", title = "Two"),
            )
        }
        syncer(provider).runFullSync()

        provider.songsByAlbum["alb-1"] = listOf(ProviderSong(externalId = "song-1", title = "One"))
        val outcome = syncer(provider).runFullSync()

        assertThat(outcome.message).isEqualTo("ok")
        assertThat(songDao.selectByProvider(1).map { it.externalId }).containsExactly("song-1")
        assertThat(songDao.selectByProviderAndExternal(1, "song-1")!!.lastSeenSyncId).isEqualTo(2)
    }

    @Test
    fun `rated resumed and clipped songs are tombstoned instead of deleted`() = runDbTest {
        newProvider(id = 1)
        songDao.upsert(
            SongEntity(
                id = 30, providerId = 1, externalId = "song-rated", title = "Rated",
                userRating = 5, lastSeenSyncId = 1, updatedAt = 1_000,
            ),
        )
        songDao.upsert(
            SongEntity(
                id = 31, providerId = 1, externalId = "song-resumed", title = "Resumed",
                resumeMs = 4_000, lastSeenSyncId = 1, updatedAt = 1_000,
            ),
        )
        songDao.upsert(
            SongEntity(
                id = 32, providerId = 1, externalId = "song-clipped", title = "Clipped",
                windowStartMs = 1_000, windowEndMs = 2_000, lastSeenSyncId = 1, updatedAt = 1_000,
            ),
        )
        val provider = MutableSyncProvider().apply {
            albums += ProviderAlbum(externalId = "alb-1", title = "Album")
            songsByAlbum["alb-1"] = (1..4).map { index ->
                ProviderSong(externalId = "song-new-$index", title = "New $index")
            }
        }

        val outcome = syncer(provider).runFullSync()

        assertThat(outcome.message).isEqualTo("ok")
        val rated = songDao.selectByProviderAndExternal(1, "song-rated")!!
        assertThat(rated.missingOnServer).isTrue()
        assertThat(rated.userRating).isEqualTo(5)
        val resumed = songDao.selectByProviderAndExternal(1, "song-resumed")!!
        assertThat(resumed.missingOnServer).isTrue()
        assertThat(resumed.resumeMs).isEqualTo(4_000)
        val clipped = songDao.selectByProviderAndExternal(1, "song-clipped")!!
        assertThat(clipped.missingOnServer).isTrue()
        assertThat(clipped.windowStartMs).isEqualTo(1_000)
        assertThat(clipped.windowEndMs).isEqualTo(2_000)
    }

    @Test
    fun `a tombstoned song the server reports again is unmarked and keeps its history`() = runDbTest {
        newProvider(id = 1)
        songDao.upsert(
            SongEntity(
                id = 30, providerId = 1, externalId = "song-back", title = "Back",
                playCount = 4, lastPlayed = 900,
                isFavorite = true, favoriteAt = 800,
                userRating = 4,
                resumeMs = 1_500, resumeUpdatedAt = 1_400,
                lastSeenSyncId = 1, updatedAt = 1_000,
            ),
        )
        playEventDao.recordPlay(1, "song-back", playedAtMs = 500, durationMs = 60_000)
        playEventDao.recordPlay(1, "song-back", playedAtMs = 700, durationMs = 60_000)

        val provider = MutableSyncProvider().apply {
            albums += ProviderAlbum(externalId = "alb-1", title = "Album")
            songsByAlbum["alb-1"] = listOf(ProviderSong(externalId = "song-1", title = "One"))
        }

        val first = syncer(provider).runFullSync()
        assertThat(first.message).isEqualTo("ok")
        val tombstoned = songDao.selectByProviderAndExternal(1, "song-back")!!
        assertThat(tombstoned.missingOnServer).isTrue()
        assertThat(tombstoned.playCount).isEqualTo(4)

        // The song reappears on the server under the same externalId.
        provider.songsByAlbum["alb-1"] = listOf(
            ProviderSong(externalId = "song-1", title = "One"),
            ProviderSong(externalId = "song-back", title = "Back again"),
        )
        val second = syncer(provider).runFullSync()

        assertThat(second.message).isEqualTo("ok")
        val returned = songDao.selectByProviderAndExternal(1, "song-back")!!
        assertThat(returned.title).isEqualTo("Back again")
        assertThat(returned.missingOnServer).isFalse()
        assertThat(returned.playCount).isEqualTo(4)
        assertThat(returned.lastPlayed).isEqualTo(900)
        assertThat(returned.isFavorite).isTrue()
        assertThat(returned.favoriteAt).isEqualTo(800)
        assertThat(returned.userRating).isEqualTo(4)
        assertThat(returned.resumeMs).isEqualTo(1_500)
        assertThat(returned.resumeUpdatedAt).isEqualTo(1_400)
        assertThat(playEventDao.selectByProviderAndExternal(1, "song-back")).hasSize(2)
    }

    @Test
    fun `favorite alone does not prevent normal pruning`() = runDbTest {
        newProvider(id = 1)
        songDao.upsert(
            SongEntity(
                id = 30, providerId = 1, externalId = "song-fav", title = "Fav",
                isFavorite = true, favoriteAt = 900, lastSeenSyncId = 1, updatedAt = 1_000,
            ),
        )
        val provider = MutableSyncProvider().apply {
            albums += ProviderAlbum(externalId = "alb-1", title = "Album")
            songsByAlbum["alb-1"] = listOf(ProviderSong(externalId = "song-1", title = "One"))
        }

        val outcome = syncer(provider).runFullSync()

        assertThat(outcome.message).isEqualTo("ok")
        assertThat(songDao.selectByProviderAndExternal(1, "song-fav")).isNull()
    }

    @Test
    fun `chapters are removed with their song and album`() = runDbTest {
        newProvider(id = 1)
        val keptAlbum = albumDao.upsert(
            AlbumEntity(id = 0, providerId = 1, externalId = "alb-kept", title = "Kept", updatedAt = 1_000),
        )
        val goneAlbum = albumDao.upsert(
            AlbumEntity(id = 0, providerId = 1, externalId = "alb-gone", title = "Gone", updatedAt = 1_000),
        )
        val tombAlbum = albumDao.upsert(
            AlbumEntity(id = 0, providerId = 1, externalId = "alb-tomb", title = "Tomb", updatedAt = 1_000),
        )
        val keptSong = songDao.upsert(
            SongEntity(id = 0, providerId = 1, externalId = "song-kept", albumId = keptAlbum, title = "Kept", lastSeenSyncId = 1, updatedAt = 1_000),
        )
        val goneSong = songDao.upsert(
            SongEntity(id = 0, providerId = 1, externalId = "song-gone", albumId = goneAlbum, title = "Gone", lastSeenSyncId = 1, updatedAt = 1_000),
        )
        val tombSong = songDao.upsert(
            SongEntity(id = 0, providerId = 1, externalId = "song-tomb", albumId = tombAlbum, title = "Tomb", playCount = 2, lastSeenSyncId = 1, updatedAt = 1_000),
        )
        fun chapter(songId: Long?, albumId: Long?) = ChapterEntity(
            providerId = 1,
            songId = songId,
            albumId = albumId,
            chapterIndex = 0,
            title = "Intro",
            positionMs = 0,
            endPositionMs = 60_000,
            origin = "embedded",
            rank = 0,
            updatedAt = 1_000,
        )
        chapterDao.upsert(chapter(keptSong, null))
        chapterDao.upsert(chapter(goneSong, null))
        chapterDao.upsert(chapter(tombSong, null))
        chapterDao.upsert(chapter(null, goneAlbum))
        chapterDao.upsert(chapter(null, keptAlbum))

        val provider = MutableSyncProvider().apply {
            albums += ProviderAlbum(externalId = "alb-kept", title = "Kept")
            songsByAlbum["alb-kept"] = listOf(
                ProviderSong(externalId = "song-kept", title = "Kept"),
                ProviderSong(externalId = "song-kept-2", title = "Kept two"),
            )
        }

        val outcome = syncer(provider).runFullSync()

        assertThat(outcome.message).isEqualTo("ok")
        assertThat(songDao.selectByProviderAndExternal(1, "song-gone")).isNull()
        assertThat(songDao.selectByProviderAndExternal(1, "song-tomb")!!.missingOnServer).isTrue()
        assertThat(albumDao.selectByProviderAndExternal(1, "alb-gone")).isNull()
        assertThat(albumDao.selectByProviderAndExternal(1, "alb-tomb")).isNotNull()
        assertThat(chapterDao.selectBySong(goneSong)).isEmpty()
        assertThat(chapterDao.selectBySong(tombSong)).hasSize(1)
        assertThat(chapterDao.selectBySong(keptSong)).hasSize(1)
        assertThat(chapterDao.selectByAlbum(goneAlbum)).isEmpty()
        assertThat(chapterDao.selectByAlbum(keptAlbum)).hasSize(1)
        assertThat(chapterDao.selectByAlbum(tombAlbum)).isEmpty()
    }

    @Test
    fun `a five thousand song library survives a prune of one thousand unseen songs`() = runDbTest {
        newProvider(id = 1)
        val artistCount = 50
        val albumsPerArtist = 10
        val songsPerAlbum = 10
        val provider = MutableSyncProvider()
        (0 until artistCount).forEach { artistIndex ->
            provider.artists += ProviderArtist(externalId = "art-$artistIndex", title = "Artist $artistIndex")
        }
        (0 until artistCount * albumsPerArtist).forEach { albumIndex ->
            val artistIndex = albumIndex / albumsPerArtist
            provider.albums += ProviderAlbum(
                externalId = "alb-$albumIndex",
                title = "Album $albumIndex",
                artistExternalId = "art-$artistIndex",
            )
            provider.songsByAlbum["alb-$albumIndex"] = (0 until songsPerAlbum).map { songIndex ->
                ProviderSong(externalId = "song-$albumIndex-$songIndex", title = "Song $albumIndex-$songIndex")
            }
        }
        provider.playlists += ProviderPlaylistSummary(externalId = "pl-1", name = "List")
        provider.playlistDetails["pl-1"] = ProviderPlaylist(
            ProviderPlaylistSummary(externalId = "pl-1", name = "List"),
            entries = emptyList(),
        )

        val sync = syncer(provider)
        sync.runFullSync()
        assertThat(songDao.selectByProvider(1)).hasSize(5_000)

        // The server drops the first 100 albums (1,000 songs = a fifth of the
        // known library, so the FIX-02 half-guard lets the prune run). The
        // songs of the first 10 albums were played in the meantime, so they
        // must come back as tombstones instead of being deleted.
        val firstDoomedAlbum = 0
        val lastAlbumWithPlays = 9
        (firstDoomedAlbum..lastAlbumWithPlays).forEach { albumIndex ->
            (0 until songsPerAlbum).forEach { songIndex ->
                val song = songDao.selectByProviderAndExternal(1, "song-$albumIndex-$songIndex")!!
                songDao.addPlayCount(song.id, delta = 1, now = 5_000)
            }
        }
        val droppedAlbums = 0..99
        provider.albums.removeAll { it.externalId.substringAfterLast("alb-").toInt() in droppedAlbums }
        provider.songsByAlbum.keys.removeAll { it.substringAfterLast("alb-").toInt() in droppedAlbums }
        provider.artists.removeAll { it.externalId.substringAfterLast("art-").toInt() in 0..9 }
        provider.playlists.clear()
        provider.playlistDetails.clear()

        val outcome = sync.runFullSync()

        assertThat(outcome.message).isEqualTo("ok")

        val songs = songDao.selectByProvider(1)
        // 4,000 reported + 100 tombstoned.
        assertThat(songs).hasSize(4_100)
        val tombstoned = songs.filter { it.missingOnServer }
        assertThat(tombstoned).hasSize(100)
        assertThat(tombstoned.all { it.playCount == 1 }).isTrue()
        val reported = songs.filterNot { it.missingOnServer }
        assertThat(reported).hasSize(4_000)
        assertThat(reported.all { it.lastSeenSyncId == 2L }).isTrue()
        assertThat(songDao.selectByProviderAndExternal(1, "song-99-9")).isNull()
        assertThat(songDao.selectByProviderAndExternal(1, "song-100-0")!!.lastSeenSyncId).isEqualTo(2)

        val albums = albumDao.selectByProvider(1)
        // 400 reported + the 10 albums whose songs survived as tombstones.
        assertThat(albums).hasSize(410)
        assertThat(albumDao.selectByProviderAndExternal(1, "alb-99")).isNull()
        assertThat(albumDao.selectByProviderAndExternal(1, "alb-9")).isNotNull()
        assertThat(albumDao.selectByProviderAndExternal(1, "alb-100")!!.lastSeenSyncId).isEqualTo(2)

        val artists = artistDao.selectByProvider(1)
        // 40 reported (art-10..49) + art-0, whose albums survived through
        // tombstoned songs; art-1..9 lost every album and link and are pruned.
        assertThat(artists).hasSize(41)
        assertThat(artistDao.selectByProviderAndExternal(1, "art-1")).isNull()
        assertThat(artistDao.selectByProviderAndExternal(1, "art-9")).isNull()
        assertThat(artistDao.selectByProviderAndExternal(1, "art-0")!!.lastSeenSyncId).isEqualTo(1)
        assertThat(artistDao.selectByProviderAndExternal(1, "art-30")!!.lastSeenSyncId).isEqualTo(2)

        assertThat(playlistDao.selectByProvider(1)).isEmpty()
    }
}
