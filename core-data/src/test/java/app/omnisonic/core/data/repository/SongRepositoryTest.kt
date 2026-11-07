package app.omnisonic.core.data.repository

import app.omnisonic.core.data.DatabaseTestBase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongRepositoryTest : DatabaseTestBase() {

    private val repository get() = SongRepository(db)

    private fun snapshot(
        title: String = "Queue title",
        artistName: String? = "Queue artist",
        albumExternalId: String? = null,
        coverImageId: String? = "cover-1",
        durationMs: Long? = 200_000,
    ) = SongRepository.SongSnapshot(
        title = title,
        artistName = artistName,
        albumExternalId = albumExternalId,
        coverImageId = coverImageId,
        durationMs = durationMs,
    )

    @Test
    fun ensureSongMaterialisesARowWithoutASyncedSong() = runDbTest {
        val provider = newProvider(id = 1)

        val id = repository.ensureSong(provider.id, "song-1", snapshot())

        val row = songDao.selectById(id)!!
        assertThat(row.providerId).isEqualTo(1)
        assertThat(row.externalId).isEqualTo("song-1")
        assertThat(row.title).isEqualTo("Queue title")
        assertThat(row.displayArtist).isEqualTo("Queue artist")
        assertThat(row.imagePath).isEqualTo("cover-1")
        assertThat(row.durationMs).isEqualTo(200_000)
    }

    @Test
    fun ensureSongTwiceReturnsTheSameId() = runDbTest {
        val provider = newProvider(id = 1)

        val first = repository.ensureSong(provider.id, "song-1", snapshot())
        val second = repository.ensureSong(provider.id, "song-1", snapshot(title = "Different"))

        assertThat(second).isEqualTo(first)
        assertThat(songDao.selectByProvider(1)).hasSize(1)
    }

    @Test
    fun ensureSongNeverResetsLocallyOwnedPlaybackState() = runDbTest {
        val provider = newProvider(id = 1)

        val id = repository.ensureSong(provider.id, "song-1", snapshot())
        songDao.addPlayCount(id, delta = 2, now = 1_500)
        songDao.setFavorite(id, favorite = true, now = 1_600)
        songDao.setResumePoint(id, resumeMs = 45_000, now = 1_700)

        repository.ensureSong(provider.id, "song-1", snapshot(title = "Other", coverImageId = "cover-2"))

        val row = songDao.selectById(id)!!
        assertThat(row.playCount).isEqualTo(2)
        assertThat(row.isFavorite).isTrue()
        assertThat(row.resumeMs).isEqualTo(45_000)
        // Fill-NULL-only: existing values are never downgraded by a snapshot.
        assertThat(row.title).isEqualTo("Queue title")
        assertThat(row.imagePath).isEqualTo("cover-1")
    }

    @Test
    fun ensureSongFillsMissingColumnsOnAnExistingRow() = runDbTest {
        val provider = newProvider(id = 1)
        songDao.insertOrIgnore(
            app.omnisonic.core.data.db.entity.SongEntity(
                providerId = 1,
                externalId = "song-1",
                title = "Synced title",
                updatedAt = 0,
            ),
        )
        val existing = songDao.selectByProviderAndExternal(1, "song-1")!!

        val id = repository.ensureSong(provider.id, "song-1", snapshot())

        assertThat(id).isEqualTo(existing.id)
        val row = songDao.selectById(id)!!
        assertThat(row.title).isEqualTo("Synced title")
        assertThat(row.displayArtist).isEqualTo("Queue artist")
        assertThat(row.durationMs).isEqualTo(200_000)
    }

    @Test
    fun ensureSongLinksTheAlbumWhenItsRowExists() = runDbTest {
        val provider = newProvider(id = 1)
        val album = newAlbum(id = 5, providerId = 1)

        val id = repository.ensureSong(provider.id, "song-1", snapshot(albumExternalId = "alb-5"))

        assertThat(songDao.selectById(id)!!.albumId).isEqualTo(album.id)
    }

    @Test
    fun serverRefreshAfterEnsureSongKeepsLocallyOwnedColumns() = runDbTest {
        val provider = newProvider(id = 1)
        val id = repository.ensureSong(provider.id, "song-1", snapshot())
        songDao.addPlayCount(id, delta = 2, now = 1_500)
        songDao.setFavorite(id, favorite = true, now = 1_600)
        songDao.setResumePoint(id, resumeMs = 45_000, now = 1_700)

        songDao.updateServerFields(
            providerId = 1,
            externalId = "song-1",
            albumId = null,
            title = "Server title",
            latinTitle = null,
            sortTitle = null,
            displayArtist = "Server artist",
            filePath = "/music/song.flac",
            durationMs = 201_000,
            trackNo = 4,
            discNo = 1,
            year = 2020,
            genresJson = "[\"Rock\"]",
            imagePath = "server-cover",
            codec = "flac",
            bitrateKbps = 900,
            lastSeenSyncId = 1,
            updatedAt = 2_000,
        )

        val row = songDao.selectById(id)!!
        assertThat(row.title).isEqualTo("Server title")
        assertThat(row.displayArtist).isEqualTo("Server artist")
        assertThat(row.playCount).isEqualTo(2)
        assertThat(row.isFavorite).isTrue()
        assertThat(row.favoriteAt).isEqualTo(1_600)
        assertThat(row.resumeMs).isEqualTo(45_000)
        assertThat(row.resumeUpdatedAt).isEqualTo(1_700)
    }

    @Test
    fun providerDeleteCascadesMaterialisedRows() = runDbTest {
        val provider = newProvider(id = 1)
        val id = repository.ensureSong(provider.id, "song-1", snapshot())
        songDao.addPlayCount(id, delta = 1, now = 1_500)

        providerDao.deleteById(1)

        assertThat(songDao.selectById(id)).isNull()
    }
}
