package app.omnisonic.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.dao.AggregateDao
import app.omnisonic.core.data.db.dao.AlbumArtistDao
import app.omnisonic.core.data.db.dao.AlbumDao
import app.omnisonic.core.data.db.dao.ArtistDao
import app.omnisonic.core.data.db.dao.ChapterDao
import app.omnisonic.core.data.db.dao.DownloadDao
import app.omnisonic.core.data.db.dao.GenreDao
import app.omnisonic.core.data.db.dao.InternetRadioDao
import app.omnisonic.core.data.db.dao.LibrarySourceDao
import app.omnisonic.core.data.db.dao.OfflineFileDao
import app.omnisonic.core.data.db.dao.OutboxDao
import app.omnisonic.core.data.db.dao.PlaybackEventDao
import app.omnisonic.core.data.db.dao.PlaylistDao
import app.omnisonic.core.data.db.dao.PlaylistEntryDao
import app.omnisonic.core.data.db.dao.ProviderDao
import app.omnisonic.core.data.db.dao.SavedFilterDao
import app.omnisonic.core.data.db.dao.SongArtistDao
import app.omnisonic.core.data.db.dao.SongDao
import app.omnisonic.core.data.db.dao.SyncStatusDao
import app.omnisonic.core.data.db.entity.AlbumArtistEntity
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.ChapterEntity
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.InternetRadioEntity
import app.omnisonic.core.data.db.entity.LibrarySourceEntity
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.db.entity.OutboxItemEntity
import app.omnisonic.core.data.db.entity.PlaybackEventEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.db.entity.SavedFilterEntity
import app.omnisonic.core.data.db.entity.SongArtistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.db.entity.SyncStatusEntity
import kotlinx.coroutines.test.runTest

abstract class DatabaseTestBase {

    protected lateinit var appContext: Context

    protected lateinit var db: OmnisonicDatabase

    @Before
    fun prepareDatabaseContext() {
        appContext = ApplicationProvider.getApplicationContext()
    }

    protected val providerDao: ProviderDao get() = db.providerDao()
    protected val librarySourceDao: LibrarySourceDao get() = db.librarySourceDao()
    protected val artistDao: ArtistDao get() = db.artistDao()
    protected val albumDao: AlbumDao get() = db.albumDao()
    protected val songDao: SongDao get() = db.songDao()
    protected val genreDao: GenreDao get() = db.genreDao()
    protected val albumArtistDao: AlbumArtistDao get() = db.albumArtistDao()
    protected val songArtistDao: SongArtistDao get() = db.songArtistDao()
    protected val playlistDao: PlaylistDao get() = db.playlistDao()
    protected val playlistEntryDao: PlaylistEntryDao get() = db.playlistEntryDao()
    protected val internetRadioDao: InternetRadioDao get() = db.internetRadioDao()
    protected val chapterDao: ChapterDao get() = db.chapterDao()
    protected val playbackEventDao: PlaybackEventDao get() = db.playbackEventDao()
    protected val outboxDao: OutboxDao get() = db.outboxDao()
    protected val downloadDao: DownloadDao get() = db.downloadDao()
    protected val offlineFileDao: OfflineFileDao get() = db.offlineFileDao()
    protected val savedFilterDao: SavedFilterDao get() = db.savedFilterDao()
    protected val syncStatusDao: SyncStatusDao get() = db.syncStatusDao()
    protected val aggregateDao: AggregateDao get() = db.aggregateDao()

    protected fun runDbTest(block: suspend () -> Unit): Unit = runTest {
        db = OmnisonicDatabaseFactory(appContext).openInMemory()
        try {
            block()
        } finally {
            db.close()
        }
    }

    protected suspend fun newProvider(id: Long = 0, name: String = "provider-$id"): ProviderEntity {
        val provider = ProviderEntity(
            id = id,
            name = name,
            api = "api-test",
            primaryScheme = "https",
            primaryHost = "host-$id.example",
            primaryPort = 443,
            primaryPath = "/",
            username = "user-$id",
            createdAt = 1_000,
            updatedAt = 1_000,
        )
        providerDao.upsert(provider)
        return providerDao.selectById(id) ?: error("provider row missing")
    }

    protected suspend fun newArtist(id: Long, providerId: Long, title: String = "artist-$id"): ArtistEntity {
        val artist = ArtistEntity(id = id, providerId = providerId, externalId = "art-$id", title = title, updatedAt = 1_000)
        artistDao.upsert(artist)
        return artistDao.selectById(id) ?: error("artist row missing")
    }

    protected suspend fun newAlbum(id: Long, providerId: Long, title: String = "album-$id"): AlbumEntity {
        val album = AlbumEntity(id = id, providerId = providerId, externalId = "alb-$id", title = title, updatedAt = 1_000)
        albumDao.upsert(album)
        return albumDao.selectById(id) ?: error("album row missing")
    }

    protected suspend fun newSong(
        id: Long,
        providerId: Long,
        albumId: Long? = null,
        title: String = "song-$id",
        playCount: Int = 0,
        lastPlayed: Long? = null,
        resumePointMs: Long? = null,
        filePath: String? = null,
    ): SongEntity {
        val song = SongEntity(
            id = id,
            providerId = providerId,
            externalId = "song-$id",
            albumId = albumId,
            title = title,
            playCount = playCount,
            lastPlayed = lastPlayed,
            resumePointMs = resumePointMs,
            filePath = filePath,
            updatedAt = 1_000,
        )
        songDao.upsert(song)
        return songDao.selectById(id) ?: error("song row missing")
    }

    protected suspend fun linkSongArtist(providerId: Long, artistId: Long, songId: Long, role: String = "") {
        songArtistDao.upsert(SongArtistEntity(id = 0, providerId = providerId, songId = songId, artistId = artistId, role = role))
    }

    protected suspend fun linkAlbumArtist(providerId: Long, artistId: Long, albumId: Long, role: String = "") {
        albumArtistDao.upsert(AlbumArtistEntity(id = 0, providerId = providerId, albumId = albumId, artistId = artistId, role = role))
    }

    protected suspend fun seedLibrarySource(providerId: Long): LibrarySourceEntity {
        val source = LibrarySourceEntity(
            id = 0,
            providerId = providerId,
            externalId = "src-1",
            mediaType = "music",
            title = "Music library",
            pathsJson = "[\"/music\"]",
            updatedAt = 1_000,
        )
        librarySourceDao.upsert(source)
        return librarySourceDao.selectByProvider(providerId).first()
    }

    protected suspend fun seedPlaylist(providerId: Long): PlaylistEntity {
        val playlist = PlaylistEntity(
            id = 0,
            providerId = providerId,
            externalId = "pl-1",
            title = "Favorites",
            type = 0,
            updatedAt = 1_000,
        )
        return playlistDao.selectById(playlistDao.upsert(playlist)) ?: error("playlist row missing")
    }

    protected suspend fun seedPlaylistEntry(playlistId: Long, providerId: Long): PlaylistEntryEntity {
        val entry = PlaylistEntryEntity(
            id = 0,
            playlistId = playlistId,
            providerId = providerId,
            entryOrder = 0,
            externalId = "e-1",
            title = "Entry",
            addedAt = 1_000,
        )
        playlistEntryDao.upsert(entry)
        return playlistEntryDao.selectByPlaylist(playlistId).first()
    }

    protected suspend fun seedGenre(providerId: Long): GenreEntity {
        genreDao.upsert(GenreEntity(id = 0, providerId = providerId, externalId = "jin", title = "Jazz", updatedAt = 1_000))
        return genreDao.selectAll().first()
    }

    protected suspend fun seedInternetRadio(providerId: Long): InternetRadioEntity {
        internetRadioDao.upsert(
            InternetRadioEntity(
                id = 0,
                providerId = providerId,
                externalId = "radio-1",
                title = "Jazz FM",
                streamUrl = "https://jazz.fm/stream.m3u",
                updatedAt = 1_000,
            ),
        )
        return internetRadioDao.selectAll().first()
    }

    protected suspend fun seedChapter(providerId: Long, songId: Long?, albumId: Long? = null): ChapterEntity {
        val chapter = ChapterEntity(
            id = 0,
            providerId = providerId,
            albumId = albumId,
            songId = songId,
            chapterIndex = 0,
            title = "Intro",
            positionMs = 0,
            endPositionMs = 60_000,
            sourceKind = "embedded",
            sourcePriority = 0,
            updatedAt = 1_000,
        )
        chapterDao.upsert(chapter)
        return chapterDao.selectBySong(songId!!).first()
    }

    protected suspend fun seedPlaybackEvent(providerId: Long): PlaybackEventEntity {
        playbackEventDao.recordDayEvent(providerId, "song-1", 1, played = true, skipped = false, durationMs = 1)
        return playbackEventDao.selectByProvider(providerId).first()
    }

    protected suspend fun seedOutboxItem(providerId: Long): OutboxItemEntity {
        outboxDao.upsert(
            OutboxItemEntity(
                id = 0,
                providerId = providerId,
                mediaType = "track",
                externalId = "song-1",
                action = OutboxQueueActions.PLAY_COUNT,
                nextAttemptAt = 0,
                createdAt = 1_000,
            ),
        )
        return outboxDao.selectByProvider(providerId).first()
    }

    protected suspend fun seedDownloadItem(): DownloadItemEntity {
        downloadDao.upsert(
            DownloadItemEntity(
                id = 0,
                mediaKey = "key-1",
                status = "pending",
                title = "Track one",
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
        return downloadDao.selectByMediaKey("key-1") ?: error("download row missing")
    }

    protected suspend fun seedOfflineFile(providerId: Long): OfflineFileEntity {
        offlineFileDao.upsert(
            OfflineFileEntity(
                id = 0,
                providerId = providerId,
                mediaType = "track",
                sourceKey = "src-key-1",
                offlinePath = "/cache/key-1",
                sizeBytes = 1_024,
                downloadedAt = 1_000,
            ),
        )
        return offlineFileDao.selectBySourceKey("src-key-1") ?: error("offline row missing")
    }

    protected suspend fun seedSavedFilter(): SavedFilterEntity {
        savedFilterDao.upsert(
            SavedFilterEntity(
                id = 0,
                name = "Rock",
                mediaType = "music",
                filterJson = "[{\"field\":\"genre\",\"value\":\"Rock\"}]",
                updatedAt = 1_000,
            ),
        )
        return savedFilterDao.selectByMediaTypeAndName("music", "Rock") ?: error("filter row missing")
    }

    protected suspend fun seedSyncStatus(providerId: Long): SyncStatusEntity {
        syncStatusDao.upsert(
            SyncStatusEntity(
                id = 0,
                providerId = providerId,
                lastFullSyncAt = 1_000,
                lastSyncResult = 0,
                lastSyncMessage = "ok",
            ),
        )
        return syncStatusDao.selectByProvider(providerId) ?: error("sync row missing")
    }

    protected suspend fun countOf(query: suspend () -> List<*>): Int = query().size

    protected suspend fun providerRow(providerId: Long): ProviderEntity =
        providerDao.selectById(providerId) ?: error("provider $providerId not found")
}

object OutboxQueueActions {
    const val PLAY_COUNT = "play_count"
}
