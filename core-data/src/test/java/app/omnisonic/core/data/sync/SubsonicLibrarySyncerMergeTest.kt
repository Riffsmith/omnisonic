package app.omnisonic.core.data.sync

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.OutboxItemEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.provider.api.OutboxActions
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.ProviderAlbum
import app.omnisonic.provider.api.ProviderAlbumDetails
import app.omnisonic.provider.api.ProviderAlbumRequest
import app.omnisonic.provider.api.ProviderArtist
import app.omnisonic.provider.api.ProviderBookmark
import app.omnisonic.provider.api.ProviderCapabilities
import app.omnisonic.provider.api.ProviderDirectory
import app.omnisonic.provider.api.ProviderGenre
import app.omnisonic.provider.api.ProviderPlaylist
import app.omnisonic.provider.api.ProviderPlaylistSummary
import app.omnisonic.provider.api.ProviderRadio
import app.omnisonic.provider.api.ProviderSong
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubsonicLibrarySyncerMergeTest : DatabaseTestBase() {

    private fun providerOf(
        albums: List<ProviderAlbum> = emptyList(),
        songsByAlbum: Map<String, List<ProviderSong>> = emptyMap(),
        bookmarks: List<ProviderBookmark> = emptyList(),
    ): FakeSyncProvider = FakeSyncProvider(
        albums = albums,
        songsByAlbum = songsByAlbum,
        bookmarks = bookmarks,
    )

    private fun syncer(provider: MediaProvider, nowMs: () -> Long = { 7_000L }) =
        SubsonicLibrarySyncer(db, provider, providerId = 1L, nowMs = nowMs)

    @Test
    fun `album and song local-only fields survive a full sync`() = runDbTest {
        newProvider(id = 1)
        albumDao.upsert(
            AlbumEntity(
                id = 20, providerId = 1, externalId = "alb-1", title = "Old",
                isAudiobook = true, description = "local notes", resumeTrackPath = "/cache/a.m4b",
                discNamesJson = "[\"Disc one\"]", isCompilation = true, updatedAt = 1_000,
            ),
        )
        songDao.upsert(
            SongEntity(
                id = 30, providerId = 1, externalId = "song-1", albumId = 20, title = "Old song",
                isAudiobook = true, windowStartMs = 500, replayGainTrack = -1.5,
                resumeMs = 3_000, updatedAt = 1_000,
            ),
        )

        val provider = providerOf(
            albums = listOf(ProviderAlbum(externalId = "alb-1", title = "Fresh")),
            songsByAlbum = mapOf("alb-1" to listOf(ProviderSong(externalId = "song-1", title = "Fresh song"))),
        )

        syncer(provider).runFullSync()

        val album = albumDao.selectByProviderAndExternal(1, "alb-1")!!
        assertThat(album.title).isEqualTo("Fresh")
        assertThat(album.isAudiobook).isTrue()
        assertThat(album.description).isEqualTo("local notes")
        assertThat(album.resumeTrackPath).isEqualTo("/cache/a.m4b")
        assertThat(album.discNamesJson).isEqualTo("[\"Disc one\"]")
        assertThat(album.isCompilation).isTrue()

        val song = songDao.selectByProviderAndExternal(1, "song-1")!!
        assertThat(song.title).isEqualTo("Fresh song")
        assertThat(song.isAudiobook).isTrue()
        assertThat(song.windowStartMs).isEqualTo(500)
        assertThat(song.replayGainTrack).isEqualTo(-1.5)
        assertThat(song.resumeMs).isEqualTo(3_000)
    }

    @Test
    fun `server bookmark applies only when newer than local resume state`() = runDbTest {
        newProvider(id = 1)
        songDao.upsert(
            SongEntity(
                id = 30, providerId = 1, externalId = "song-1", title = "S",
                resumeMs = 3_000, resumeUpdatedAt = 9_000, updatedAt = 1_000,
            ),
        )
        val provider = providerOf(
            albums = listOf(ProviderAlbum(externalId = "alb-1", title = "A")),
            songsByAlbum = mapOf("alb-1" to listOf(ProviderSong(externalId = "song-1", title = "S"))),
            bookmarks = listOf(ProviderBookmark(entryExternalId = "song-1", positionMs = 42_000, updatedAtMs = 5_000)),
        )

        syncer(provider).runFullSync()

        val song = songDao.selectByProviderAndExternal(1, "song-1")!!
        assertThat(song.resumeMs).isEqualTo(3_000)
    }

    @Test
    fun `newer server bookmark still applies`() = runDbTest {
        newProvider(id = 1)
        songDao.upsert(
            SongEntity(
                id = 30, providerId = 1, externalId = "song-1", title = "S",
                resumeMs = 3_000, resumeUpdatedAt = 1_000, updatedAt = 1_000,
            ),
        )
        val provider = providerOf(
            albums = listOf(ProviderAlbum(externalId = "alb-1", title = "A")),
            songsByAlbum = mapOf("alb-1" to listOf(ProviderSong(externalId = "song-1", title = "S"))),
            bookmarks = listOf(ProviderBookmark(entryExternalId = "song-1", positionMs = 42_000, updatedAtMs = 9_000)),
        )

        syncer(provider).runFullSync()

        val song = songDao.selectByProviderAndExternal(1, "song-1")!!
        assertThat(song.resumeMs).isEqualTo(42_000)
        assertThat(song.resumeUpdatedAt).isEqualTo(9_000)
    }

    @Test
    fun `pending resume outbox row blocks the server bookmark`() = runDbTest {
        newProvider(id = 1)
        songDao.upsert(
            SongEntity(
                id = 30, providerId = 1, externalId = "song-1", title = "S",
                resumeMs = 3_000, resumeUpdatedAt = 1_000, updatedAt = 1_000,
            ),
        )
        outboxDao.upsert(
            OutboxItemEntity(
                id = 0, providerId = 1, mediaType = "SONG", externalId = "song-1",
                action = OutboxActions.RESUME_POINT, nextAttemptAt = 0, createdAt = 500,
            ),
        )
        val provider = providerOf(
            albums = listOf(ProviderAlbum(externalId = "alb-1", title = "A")),
            songsByAlbum = mapOf("alb-1" to listOf(ProviderSong(externalId = "song-1", title = "S"))),
            bookmarks = listOf(ProviderBookmark(entryExternalId = "song-1", positionMs = 42_000, updatedAtMs = 9_000)),
        )

        syncer(provider).runFullSync()

        val song = songDao.selectByProviderAndExternal(1, "song-1")!!
        assertThat(song.resumeMs).isEqualTo(3_000)
    }

    @Test
    fun `full sync deletes a vanished untouched song`() = runDbTest {
        newProvider(id = 1)
        songDao.upsert(
            SongEntity(
                id = 30, providerId = 1, externalId = "song-gone", title = "Gone",
                lastSeenSyncId = 1, updatedAt = 1_000,
            ),
        )
        val provider = providerOf(
            albums = listOf(ProviderAlbum(externalId = "alb-1", title = "A")),
            songsByAlbum = mapOf("alb-1" to listOf(ProviderSong(externalId = "song-1", title = "Kept"))),
        )

        syncer(provider).runFullSync()

        assertThat(songDao.selectByProviderAndExternal(1, "song-gone")).isNull()
        assertThat(songDao.selectByProviderAndExternal(1, "song-1")).isNotNull()
    }

    @Test
    fun `full sync tombstones a vanished played song instead of deleting it`() = runDbTest {
        newProvider(id = 1)
        songDao.upsert(
            SongEntity(
                id = 30, providerId = 1, externalId = "song-gone", title = "Gone",
                playCount = 4, lastSeenSyncId = 1, updatedAt = 1_000,
            ),
        )
        val provider = providerOf(
            albums = listOf(ProviderAlbum(externalId = "alb-1", title = "A")),
            songsByAlbum = mapOf("alb-1" to listOf(ProviderSong(externalId = "song-1", title = "Kept"))),
        )

        syncer(provider).runFullSync()

        val tombstoned = songDao.selectByProviderAndExternal(1, "song-gone")!!
        assertThat(tombstoned.missingOnServer).isTrue()
        assertThat(tombstoned.playCount).isEqualTo(4)
    }

    @Test
    fun `full sync tombstones a vanished song with pending outbox rows`() = runDbTest {
        newProvider(id = 1)
        songDao.upsert(
            SongEntity(
                id = 30, providerId = 1, externalId = "song-gone", title = "Gone",
                lastSeenSyncId = 1, updatedAt = 1_000,
            ),
        )
        outboxDao.upsert(
            OutboxItemEntity(
                id = 0, providerId = 1, mediaType = "SONG", externalId = "song-gone",
                action = OutboxActions.PLAY_COUNT, nextAttemptAt = 0, createdAt = 500,
            ),
        )
        val provider = providerOf(
            albums = listOf(ProviderAlbum(externalId = "alb-1", title = "A")),
            songsByAlbum = mapOf("alb-1" to listOf(ProviderSong(externalId = "song-1", title = "Kept"))),
        )

        syncer(provider).runFullSync()

        assertThat(songDao.selectByProviderAndExternal(1, "song-gone")!!.missingOnServer).isTrue()
    }

    @Test
    fun `diff sync never prunes`() = runDbTest {
        newProvider(id = 1)
        songDao.upsert(SongEntity(id = 30, providerId = 1, externalId = "song-gone", title = "Gone", updatedAt = 1_000))
        val provider = providerOf(
            albums = listOf(ProviderAlbum(externalId = "alb-1", title = "A")),
            songsByAlbum = mapOf("alb-1" to listOf(ProviderSong(externalId = "song-1", title = "Kept"))),
        )

        syncer(provider).runDiffSync()

        assertThat(songDao.selectByProviderAndExternal(1, "song-gone")).isNotNull()
    }

    @Test
    fun `tombstoned songs keep their album alive but empty albums go`() = runDbTest {
        newProvider(id = 1)
        albumDao.upsert(
            AlbumEntity(
                id = 20, providerId = 1, externalId = "alb-gone", title = "Played album",
                lastSeenSyncId = 1, updatedAt = 1_000,
            ),
        )
        albumDao.upsert(
            AlbumEntity(
                id = 21, providerId = 1, externalId = "alb-empty", title = "Empty album",
                lastSeenSyncId = 1, updatedAt = 1_000,
            ),
        )
        songDao.upsert(
            SongEntity(
                id = 30, providerId = 1, externalId = "song-gone", albumId = 20, title = "Gone",
                playCount = 2, lastSeenSyncId = 1, updatedAt = 1_000,
            ),
        )
        val provider = providerOf(
            albums = listOf(ProviderAlbum(externalId = "alb-1", title = "A")),
            songsByAlbum = mapOf("alb-1" to listOf(ProviderSong(externalId = "song-1", title = "Kept"))),
        )

        syncer(provider).runFullSync()

        assertThat(albumDao.selectByProviderAndExternal(1, "alb-gone")).isNotNull()
        assertThat(albumDao.selectByProviderAndExternal(1, "alb-empty")).isNull()
    }

    @Test
    fun `vanished playlists are pruned on full sync`() = runDbTest {
        newProvider(id = 1)
        val provider = providerOf(
            albums = listOf(ProviderAlbum(externalId = "alb-1", title = "A")),
            songsByAlbum = mapOf("alb-1" to listOf(ProviderSong(externalId = "song-1", title = "Kept"))),
        )
        provider.playlists += ProviderPlaylistSummary(externalId = "pl-kept", name = "Kept")
        provider.playlistDetails["pl-kept"] = ProviderPlaylist(provider.playlists.single(), entries = emptyList())
        syncer(provider).runFullSync()
        assertThat(playlistDao.selectByProvider(1).map { it.externalId }).containsExactly("pl-kept")

        provider.playlists.clear()
        provider.playlistDetails.clear()

        syncer(provider).runFullSync()

        assertThat(playlistDao.selectByProvider(1)).isEmpty()
    }

    @Test
    fun `one album costs exactly one provider album request`() = runDbTest {
        newProvider(id = 1)
        val albums = (1..3).map { index -> ProviderAlbum(externalId = "alb-$index", title = "A$index") }
        val provider = providerOf(
            albums = albums,
            songsByAlbum = albums.associate { it.externalId to emptyList() },
        )

        syncer(provider).runFullSync()

        assertThat(provider.albumFetchCount).isEqualTo(albums.size)
        assertThat(provider.albumListCount).isEqualTo(1)
    }

    @Test
    fun `outbox enqueue completes while the sync waits on the network`() = runDbTest {
        newProvider(id = 1)
        val gate = CompletableDeferred<Unit>()
        val provider = object : FakeSyncProvider(
            albums = listOf(ProviderAlbum(externalId = "alb-1", title = "A")),
            songsByAlbum = mapOf("alb-1" to emptyList()),
        ) {
            override suspend fun getAlbum(albumExternalId: String): ProviderAlbumDetails? {
                albumFetchStarted = true
                gate.await()
                return super.getAlbum(albumExternalId)
            }
        }

        var syncDone = false
        var enqueueDone = false
        val deadlineMs = System.currentTimeMillis() + 10_000
        coroutineScope {
            val syncJob = launch { syncer(provider).runFullSync(); syncDone = true }

            // Wait until the sync is parked inside getAlbum.
            while (!provider.albumFetchStarted) {
                if (System.currentTimeMillis() > deadlineMs) fail("sync never reached the album fetch")
                kotlinx.coroutines.yield()
            }

            val enqueueJob = launch {
                outboxDao.upsert(
                    OutboxItemEntity(
                        id = 0, providerId = 1, mediaType = "SONG", externalId = "other",
                        action = OutboxActions.PLAY_COUNT, nextAttemptAt = 0, createdAt = 1,
                    ),
                )
                enqueueDone = true
            }

            while (!enqueueDone) {
                kotlinx.coroutines.yield()
                if (System.currentTimeMillis() > deadlineMs) fail("outbox enqueue blocked behind the sync transaction")
            }

            assertThat(syncDone).isFalse()
            gate.complete(Unit)
            syncJob.join()
            enqueueJob.join()
        }
        assertThat(syncDone).isTrue()
    }
}

private open class FakeSyncProvider(
    private val albums: List<ProviderAlbum>,
    private val songsByAlbum: Map<String, List<ProviderSong>>,
    private val bookmarks: List<ProviderBookmark> = emptyList(),
) : MediaProvider {

    val artists = mutableListOf<ProviderArtist>()
    val genres = mutableListOf<ProviderGenre>()
    val radios = mutableListOf<ProviderRadio>()
    val playlists = mutableListOf<ProviderPlaylistSummary>()
    val playlistDetails = mutableMapOf<String, ProviderPlaylist>()

    var albumFetchCount = 0
    var albumListCount = 0
    var albumFetchStarted = false

    override suspend fun probe(): ProviderCapabilities = ProviderCapabilities()

    override suspend fun listArtists(): List<ProviderArtist> = artists.toList()

    override suspend fun browseDirectory(externalId: String): ProviderDirectory = error("unused in these tests")

    override suspend fun getAlbum(albumExternalId: String): ProviderAlbumDetails? {
        albumFetchCount += 1
        albumFetchStarted = true
        val album = albums.firstOrNull { it.externalId == albumExternalId } ?: return null
        return ProviderAlbumDetails(album, songsByAlbum[albumExternalId].orEmpty())
    }

    override suspend fun songDetails(externalId: String): ProviderSong? = null

    override suspend fun listAlbums(request: ProviderAlbumRequest): List<ProviderAlbum> {
        albumListCount += 1
        return if (request.offset == 0) albums else emptyList()
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

    override suspend fun submitPlays(externalId: String, timestamps: List<Long>): Int = timestamps.size

    override suspend fun sendNowPlaying(externalId: String, playedMs: Long): Boolean = false

    override suspend fun setFavorite(mediaType: app.omnisonic.provider.api.ProviderMediaType, externalId: String, favorite: Boolean): Boolean = false

    override suspend fun setUserRating(mediaType: app.omnisonic.provider.api.ProviderMediaType, externalId: String, rating: Int): Boolean = false

    override suspend fun saveBookmark(externalId: String, positionMs: Long): Boolean = false

    override suspend fun deleteBookmark(externalId: String): Boolean = false

    override suspend fun similarSongs(externalId: String, count: Int): List<ProviderSong> = emptyList()
}
