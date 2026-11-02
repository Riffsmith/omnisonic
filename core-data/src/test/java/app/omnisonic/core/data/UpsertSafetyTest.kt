package app.omnisonic.core.data

import app.omnisonic.core.data.db.entity.AlbumArtistEntity
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.InternetRadioEntity
import app.omnisonic.core.data.db.entity.LibrarySourceEntity
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.db.entity.OutboxItemEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.SavedFilterEntity
import app.omnisonic.core.data.db.entity.SongArtistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.db.entity.SyncStatusEntity
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpsertSafetyTest : DatabaseTestBase() {

    @Test
    fun `editing a provider keeps its songs when recursive triggers are on`() = runDbTest {
        val provider = newProvider(id = 1)
        val song = newSong(id = 10, providerId = 1, title = "Keep me")
        linkSongArtist(providerId = 1, artistId = 20, songId = song.id)

        db.openHelper.writableDatabase.execSQL("PRAGMA recursive_triggers = ON")
        assertThat(recursiveTriggers()).isEqualTo(1)

        providerDao.upsert(provider.copy(name = "renamed", updatedAt = 2_000))

        assertThat(providerRow(1).name).isEqualTo("renamed")
        assertThat(songDao.selectById(10)?.title).isEqualTo("Keep me")
        assertThat(songArtistDao.selectBySong(10)).hasSize(1)
    }

    @Test
    fun `re-upserting the same song keeps its id and join rows`() = runDbTest {
        newProvider(id = 1)
        newArtist(id = 20, providerId = 1)
        val album = newAlbum(id = 30, providerId = 1)
        val firstId = songDao.upsert(
            SongEntity(
                providerId = 1,
                externalId = "song-1",
                albumId = album.id,
                title = "First title",
                updatedAt = 1_000,
            ),
        )
        linkSongArtist(providerId = 1, artistId = 20, songId = firstId)
        seedChapter(providerId = 1, songId = firstId)
        val playlist = seedPlaylist(providerId = 1)
        playlistEntryDao.upsert(
            PlaylistEntryEntity(
                playlistId = playlist.id,
                providerId = 1,
                entryOrder = 0,
                externalId = "song-1",
                songId = firstId,
                title = "Entry",
                addedAt = 1_000,
            ),
        )

        val secondId = songDao.upsert(
            SongEntity(
                providerId = 1,
                externalId = "song-1",
                albumId = album.id,
                title = "Second title",
                updatedAt = 2_000,
            ),
        )

        assertThat(secondId).isEqualTo(firstId)
        assertThat(songDao.selectById(firstId)?.title).isEqualTo("Second title")
        assertThat(songArtistDao.selectBySong(firstId).single().songId).isEqualTo(firstId)
        assertThat(chapterDao.selectBySong(firstId)).hasSize(1)
        assertThat(playlistEntryDao.selectByPlaylist(playlist.id).single().songId).isEqualTo(firstId)
    }

    @Test
    fun `natural-key upserts update the existing row instead of inserting a new one`() = runDbTest {
        newProvider(id = 1)
        val artistId = artistDao.upsert(
            ArtistEntity(providerId = 1, externalId = "art-1", title = "Old artist", updatedAt = 1_000),
        )
        val albumId = albumDao.upsert(
            AlbumEntity(providerId = 1, externalId = "alb-1", title = "Old album", updatedAt = 1_000),
        )
        val genreId = genreDao.upsert(
            GenreEntity(providerId = 1, externalId = "rock", title = "Rock", updatedAt = 1_000),
        )
        val radioId = internetRadioDao.upsert(
            InternetRadioEntity(
                providerId = 1,
                externalId = "radio-1",
                title = "Old radio",
                streamUrl = "https://radio.example/old",
                updatedAt = 1_000,
            ),
        )
        val sourceId = librarySourceDao.upsert(
            LibrarySourceEntity(
                providerId = 1,
                externalId = "src-1",
                mediaType = "music",
                pathsJson = "[]",
                updatedAt = 1_000,
            ),
        )

        assertThat(
            artistDao.upsert(
                ArtistEntity(providerId = 1, externalId = "art-1", title = "New artist", updatedAt = 2_000),
            ),
        ).isEqualTo(artistId)
        assertThat(
            albumDao.upsert(
                AlbumEntity(providerId = 1, externalId = "alb-1", title = "New album", updatedAt = 2_000),
            ),
        ).isEqualTo(albumId)
        assertThat(
            genreDao.upsert(
                GenreEntity(providerId = 1, externalId = "rock", title = "Rock 2", updatedAt = 2_000),
            ),
        ).isEqualTo(genreId)
        assertThat(
            internetRadioDao.upsert(
                InternetRadioEntity(
                    providerId = 1,
                    externalId = "radio-1",
                    title = "New radio",
                    streamUrl = "https://radio.example/new",
                    updatedAt = 2_000,
                ),
            ),
        ).isEqualTo(radioId)
        assertThat(
            librarySourceDao.upsert(
                LibrarySourceEntity(
                    providerId = 1,
                    externalId = "src-1",
                    mediaType = "music",
                    pathsJson = "[\"/music\"]",
                    updatedAt = 2_000,
                ),
            ),
        ).isEqualTo(sourceId)

        assertThat(artistDao.selectById(artistId)?.title).isEqualTo("New artist")
        assertThat(albumDao.selectById(albumId)?.title).isEqualTo("New album")
        assertThat(genreDao.selectByProviderAndExternal(1, "rock")?.title).isEqualTo("Rock 2")
        assertThat(internetRadioDao.selectByProviderAndExternal(1, "radio-1")?.title).isEqualTo("New radio")
        assertThat(librarySourceDao.selectByProviderAndExternal(1, "src-1")?.pathsJson).isEqualTo("[\"/music\"]")
    }

    @Test
    fun `link upserts keep the existing join row`() = runDbTest {
        newProvider(id = 1)
        newArtist(id = 10, providerId = 1)
        val song = newSong(id = 20, providerId = 1)
        val album = newAlbum(id = 30, providerId = 1)

        val songLinkId = songArtistDao.upsert(
            SongArtistEntity(providerId = 1, songId = song.id, artistId = 10),
        )
        val albumLinkId = albumArtistDao.upsert(
            AlbumArtistEntity(providerId = 1, albumId = album.id, artistId = 10),
        )

        assertThat(
            songArtistDao.upsert(SongArtistEntity(providerId = 1, songId = song.id, artistId = 10)),
        ).isEqualTo(songLinkId)
        assertThat(
            albumArtistDao.upsert(AlbumArtistEntity(providerId = 1, albumId = album.id, artistId = 10)),
        ).isEqualTo(albumLinkId)
        assertThat(songArtistDao.selectBySong(song.id)).hasSize(1)
        assertThat(albumArtistDao.selectByAlbum(album.id)).hasSize(1)
    }

    @Test
    fun `playlist download offline and saved-filter upserts reuse natural identities`() = runDbTest {
        newProvider(id = 1)
        val playlistId = playlistDao.upsert(
            PlaylistEntity(providerId = 1, externalId = "pl-1", title = "Old", type = 0, updatedAt = 1_000),
        )
        val downloadId = downloadDao.upsert(
            DownloadItemEntity(
                mediaKey = "key-1",
                providerId = 1,
                status = "pending",
                title = "Old download",
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
        val offlineId = offlineFileDao.upsert(
            OfflineFileEntity(
                providerId = 1,
                mediaType = "track",
                sourceKey = "src-1",
                offlinePath = "/cache/one",
                downloadedAt = 1_000,
            ),
        )
        val filterId = savedFilterDao.upsert(
            SavedFilterEntity(name = "Rock", mediaType = "music", filterJson = "[]", updatedAt = 1_000),
        )

        assertThat(
            playlistDao.upsert(
                PlaylistEntity(providerId = 1, externalId = "pl-1", title = "New", type = 0, updatedAt = 2_000),
            ),
        ).isEqualTo(playlistId)
        assertThat(
            downloadDao.upsert(
                DownloadItemEntity(
                    mediaKey = "key-1",
                    providerId = 1,
                    status = "completed",
                    title = "New download",
                    createdAt = 1_000,
                    updatedAt = 2_000,
                ),
            ),
        ).isEqualTo(downloadId)
        assertThat(
            offlineFileDao.upsert(
                OfflineFileEntity(
                    providerId = 1,
                    mediaType = "track",
                    sourceKey = "src-1",
                    offlinePath = "/cache/two",
                    downloadedAt = 2_000,
                ),
            ),
        ).isEqualTo(offlineId)
        assertThat(
            savedFilterDao.upsert(
                SavedFilterEntity(name = "Rock", mediaType = "music", filterJson = "[1]", updatedAt = 2_000),
            ),
        ).isEqualTo(filterId)

        assertThat(playlistDao.selectById(playlistId)?.title).isEqualTo("New")
        assertThat(downloadDao.selectByMediaKey("key-1")?.title).isEqualTo("New download")
        assertThat(offlineFileDao.selectBySourceKey("src-1")?.offlinePath).isEqualTo("/cache/two")
        assertThat(savedFilterDao.selectByMediaTypeAndName("music", "Rock")?.filterJson).isEqualTo("[1]")
    }

    @Test
    fun `outbox and sync-status upserts update the unique row`() = runDbTest {
        newProvider(id = 1)
        outboxDao.upsert(
            OutboxItemEntity(
                providerId = 1,
                mediaType = "SONG",
                externalId = "song-1",
                action = "play_count",
                payloadJson = "{\"n\":1}",
                nextAttemptAt = 0,
                createdAt = 1_000,
            ),
        )
        outboxDao.upsert(
            OutboxItemEntity(
                providerId = 1,
                mediaType = "SONG",
                externalId = "song-1",
                action = "play_count",
                payloadJson = "{\"n\":2}",
                attempts = 3,
                nextAttemptAt = 5_000,
                createdAt = 1_000,
            ),
        )
        syncStatusDao.upsert(SyncStatusEntity(providerId = 1, lastSyncResult = 1))
        syncStatusDao.upsert(SyncStatusEntity(providerId = 1, lastSyncResult = 2))

        val outbox = outboxDao.selectByProvider(1).single()
        assertThat(outbox.payloadJson).isEqualTo("{\"n\":2}")
        assertThat(outbox.attempts).isEqualTo(3)
        assertThat(syncStatusDao.count()).isEqualTo(1)
        assertThat(syncStatusDao.selectByProvider(1)?.lastSyncResult).isEqualTo(2)
    }

    @Test
    fun `dao sources contain no REPLACE conflict strategy`() {
        val daoDir = listOf(
            File("src/main/java/app/omnisonic/core/data/db/dao"),
            File("core-data/src/main/java/app/omnisonic/core/data/db/dao"),
        ).firstOrNull { it.isDirectory } ?: error("DAO source directory not found")

        val offenders = daoDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                "OnConflictStrategy.REPLACE" in text ||
                    "INSERT OR REPLACE" in text.uppercase()
            }
            .map { it.name }
            .toList()

        assertThat(offenders).isEmpty()
    }

    private fun recursiveTriggers(): Int =
        db.openHelper.writableDatabase.query("PRAGMA recursive_triggers").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
