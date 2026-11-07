package app.omnisonic.core.data

import app.omnisonic.core.data.db.aggregation.AlbumCountRule
import app.omnisonic.core.data.db.aggregation.recomputeAlbums
import app.omnisonic.core.data.db.entity.ChapterEntity
import app.omnisonic.core.data.db.entity.SongArtistEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android 10's SQLite 3.22 rejects statements bound with more than 999
 * variables, so every id-list deletion and aggregate must be chunked. A list
 * of 1,200 ids exceeds the limit in one statement and passes only when fed as
 * batches.
 *
 * Robolectric's SDK 34 image bundles a newer SQLite with a raised variable
 * limit, so the 999-bind failure itself cannot be reproduced here; the
 * structural enforcement lives in [DaoSqlBoundedListTest]. These tests pin
 * that the chunked helpers produce correct results for lists that would crash
 * an Android 10 device when bound in one statement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DaoChunkedOperationsTest : DatabaseTestBase() {

    @Test
    fun `song delete by ids removes every row beyond the bind limit`() = runDbTest {
        newProvider(id = 1)
        val ids = (1..1_200L).map { index -> newSong(id = index, providerId = 1).id }

        songDao.deleteByIds(ids)

        assertThat(songDao.selectAll()).isEmpty()
    }

    @Test
    fun `chunked song delete removes rows in every batch of the list`() = runDbTest {
        newProvider(id = 1)
        val ids = (1..1_200L).map { index -> newSong(id = index, providerId = 1).id }

        songDao.deleteByIds(ids)

        listOf(ids.first(), ids[500], ids[999], ids.last()).forEach { id ->
            assertThat(songDao.selectById(id)).isNull()
        }
    }

    @Test
    fun `chunked song delete treats an empty id list as a no-op`() = runDbTest {
        newProvider(id = 1)
        newSong(id = 1, providerId = 1)

        songDao.deleteByIds(emptyList())

        assertThat(songDao.selectAll()).hasSize(1)
    }

    @Test
    fun `album delete by ids removes every row beyond the bind limit`() = runDbTest {
        newProvider(id = 1)
        val ids = (1..1_200L).map { index -> newAlbum(id = index, providerId = 1).id }

        albumDao.deleteByIds(ids)

        assertThat(albumDao.selectAll()).isEmpty()
    }

    @Test
    fun `song-artist link delete removes every row beyond the bind limit`() = runDbTest {
        newProvider(id = 1)
        val artist = newArtist(id = 1, providerId = 1)
        val songIds = (1..1_200L).map { index ->
            val song = newSong(id = index, providerId = 1)
            linkSongArtist(providerId = 1, artistId = artist.id, songId = song.id)
            song.id
        }

        songArtistDao.deleteBySongIds(songIds)

        listOf(songIds.first(), songIds[500], songIds.last()).forEach { songId ->
            assertThat(songArtistDao.selectBySong(songId)).isEmpty()
        }
    }

    @Test
    fun `album-artist link delete removes every row beyond the bind limit`() = runDbTest {
        newProvider(id = 1)
        val artist = newArtist(id = 1, providerId = 1)
        val albumIds = (1..1_200L).map { index ->
            val album = newAlbum(id = index, providerId = 1)
            linkAlbumArtist(providerId = 1, artistId = artist.id, albumId = album.id)
            album.id
        }

        albumArtistDao.deleteByAlbumIds(albumIds)

        listOf(albumIds.first(), albumIds[500], albumIds.last()).forEach { albumId ->
            assertThat(albumArtistDao.selectByAlbum(albumId)).isEmpty()
        }
    }

    @Test
    fun `chapter delete by song ids removes every row beyond the bind limit`() = runDbTest {
        newProvider(id = 1)
        val songIds = (1..1_200L).map { index ->
            val song = newSong(id = index, providerId = 1)
            chapterDao.upsert(
                ChapterEntity(
                    providerId = 1,
                    songId = song.id,
                    chapterIndex = 0,
                    positionMs = 0,
                    origin = "embedded",
                    rank = 0,
                    updatedAt = 1_000,
                ),
            )
            song.id
        }

        chapterDao.deleteBySongIds(songIds)

        listOf(songIds.first(), songIds[500], songIds.last()).forEach { songId ->
            assertThat(chapterDao.selectBySong(songId)).isEmpty()
        }
    }

    @Test
    fun `chapter delete by album ids removes every row beyond the bind limit`() = runDbTest {
        newProvider(id = 1)
        val albumIds = (1..1_200L).map { index ->
            val album = newAlbum(id = index, providerId = 1)
            chapterDao.upsert(
                ChapterEntity(
                    providerId = 1,
                    albumId = album.id,
                    chapterIndex = 0,
                    positionMs = 0,
                    origin = "embedded",
                    rank = 0,
                    updatedAt = 1_000,
                ),
            )
            album.id
        }

        chapterDao.deleteByAlbumIds(albumIds)

        listOf(albumIds.first(), albumIds[500], albumIds.last()).forEach { albumId ->
            assertThat(chapterDao.selectByAlbum(albumId)).isEmpty()
        }
    }

    @Test
    fun `album aggregate recompute survives an id list beyond the bind limit`() = runDbTest {
        newProvider(id = 1)
        val albumIds = (1..1_200L).map { index ->
            val album = newAlbum(id = index, providerId = 1)
            newSong(id = 10_000 + index, providerId = 1, albumId = album.id, playCount = 2)
            album.id
        }

        aggregateDao.recomputeAlbums(albumIds, AlbumCountRule.SUM)

        listOf(albumIds.first(), albumIds[500], albumIds.last()).forEach { albumId ->
            assertThat(requireNotNull(albumDao.selectById(albumId)).playCount).isEqualTo(2)
        }
    }

    @Test
    fun `artist aggregate recompute survives an id list beyond the bind limit`() = runDbTest {
        newProvider(id = 1)
        val artistIds = (1..1_200L).map { index ->
            val artist = newArtist(id = index, providerId = 1)
            val song = newSong(id = 10_000 + index, providerId = 1, playCount = 3)
            songArtistDao.upsert(
                SongArtistEntity(
                    id = 0, providerId = 1, songId = song.id, artistId = artist.id, role = "",
                ),
            )
            artist.id
        }

        aggregateDao.recomputeArtistsById(artistIds, includeSupporting = true)

        listOf(artistIds.first(), artistIds[500], artistIds.last()).forEach { artistId ->
            assertThat(requireNotNull(artistDao.selectById(artistId)).playCount).isEqualTo(3)
        }
    }
}
