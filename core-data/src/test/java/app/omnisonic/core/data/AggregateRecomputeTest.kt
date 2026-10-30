package app.omnisonic.core.data

import app.omnisonic.core.data.db.aggregation.AlbumCountRule
import app.omnisonic.core.data.db.aggregation.recomputeAlbums
import app.omnisonic.core.data.db.aggregation.recomputeAlbumsForProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AggregateRecomputeTest : DatabaseTestBase() {

    private suspend fun seedDataset(providerId: Long = 1): Triple<Long, Long, Long> {
        newProvider(id = providerId)
        val album = newAlbum(id = 10, providerId = providerId)
        newArtist(id = 99, providerId = providerId)
        newArtist(id = 98, providerId = providerId, title = "supporting-artist")
        val song1 = newSong(
            id = 11,
            providerId = providerId,
            albumId = album.id,
            playCount = 10,
            lastPlayed = 5_000,
            resumePointMs = 120,
            filePath = "/a/one.m4a",
        )
        newSong(
            id = 12,
            providerId = providerId,
            albumId = album.id,
            playCount = 20,
            lastPlayed = 100,
            resumePointMs = 800,
            filePath = "/a/two.m4a",
        )
        linkSongArtist(providerId = providerId, artistId = 99, songId = song1.id, role = "")
        linkSongArtist(providerId = providerId, artistId = 98, songId = song1.id, role = "feat.")
        return Triple(album.id, 99L, 98L)
    }

    @Test
    fun albumCountRuleSumSumsPlayCounts() = runDbTest {
        val (albumId, _, _) = seedDataset()

        aggregateDao.recomputeAlbums(listOf(albumId), AlbumCountRule.SUM)
        val album = requireNotNull(albumDao.selectById(albumId))
        assertThat(album.playCount).isEqualTo(30)
        assertThat(album.lastPlayed).isEqualTo(5_000)
        assertThat(album.resumeFilePath).isEqualTo("/a/one.m4a")
    }

    @Test
    fun albumCountRuleAverageRoundsHalfUp() = runDbTest {
        val (albumId, _, _) = seedDataset()

        aggregateDao.recomputeAlbums(listOf(albumId), AlbumCountRule.AVERAGE)

        assertThat(requireNotNull(albumDao.selectById(albumId)).playCount).isEqualTo(15)
    }

    @Test
    fun albumCountRuleMinimumTakesLowestPlayCount() = runDbTest {
        val (albumId, _, _) = seedDataset()

        aggregateDao.recomputeAlbums(listOf(albumId), AlbumCountRule.MINIMUM)

        assertThat(requireNotNull(albumDao.selectById(albumId)).playCount).isEqualTo(10)
    }

    @Test
    fun artistRecomputeGatesSupportingRoles() = runDbTest {
        val (albumId, primary, supporting) = seedDataset()

        aggregateDao.recomputeArtistsById(listOf(primary, supporting), includeSupporting = false)
        val primaryArtist = artistDao.selectById(primary) ?: error("artist row missing")
        val supportingArtist = artistDao.selectById(supporting) ?: error("artist row missing")
        assertThat(primaryArtist.playCount).isEqualTo(10)
        assertThat(primaryArtist.lastPlayed).isEqualTo(5_000)
        assertThat(supportingArtist.playCount).isEqualTo(0)

        aggregateDao.recomputeArtistsById(listOf(primary, supporting), includeSupporting = true)
        assertThat(artistDao.selectById(supporting)!!.playCount).isEqualTo(10)
    }

    @Test
    fun providerRecomputeMatchesByIdSemantics() = runDbTest {
        val (albumId, primary, supporting) = seedDataset()

        aggregateDao.recomputeAlbumsForProvider(providerId = 1, rule = AlbumCountRule.SUM)
        aggregateDao.recomputeArtistsByProvider(providerId = 1, includeSupporting = true)

        val album = requireNotNull(albumDao.selectById(albumId))
        assertThat(album.playCount).isEqualTo(30)
        assertThat(album.lastPlayed).isEqualTo(5_000)
        assertThat(album.resumeFilePath).isEqualTo("/a/one.m4a")
        assertThat(artistDao.selectById(primary)!!.playCount).isEqualTo(10)
        assertThat(artistDao.selectById(supporting)!!.playCount).isEqualTo(10)
    }

    @Test
    fun providerRecomputeClearsAlbumsWithoutSongs() = runDbTest {
        newProvider(id = 1)
        val emptyAlbum = newAlbum(id = 20, providerId = 1)

        aggregateDao.recomputeAlbums(listOf(emptyAlbum.id), AlbumCountRule.SUM)

        val album = requireNotNull(albumDao.selectById(emptyAlbum.id))
        assertThat(album.playCount).isEqualTo(0)
        assertThat(album.lastPlayed).isNull()
        assertThat(album.resumeFilePath).isNull()
    }

    @Test
    fun averageOnEmptyAlbumByIdKeepsZeroPlayCount() = runDbTest {
        newProvider(id = 1)
        val emptyAlbum = newAlbum(id = 21, providerId = 1)

        aggregateDao.recomputeAlbums(listOf(emptyAlbum.id), AlbumCountRule.AVERAGE)

        assertEmptyAlbum(emptyAlbum.id)
    }

    @Test
    fun minimumOnEmptyAlbumByIdKeepsZeroPlayCount() = runDbTest {
        newProvider(id = 1)
        val emptyAlbum = newAlbum(id = 22, providerId = 1)

        aggregateDao.recomputeAlbums(listOf(emptyAlbum.id), AlbumCountRule.MINIMUM)

        assertEmptyAlbum(emptyAlbum.id)
    }

    @Test
    fun averageOnEmptyAlbumByProviderKeepsZeroPlayCount() = runDbTest {
        newProvider(id = 1)
        val emptyAlbum = newAlbum(id = 23, providerId = 1)

        aggregateDao.recomputeAlbumsForProvider(providerId = 1, rule = AlbumCountRule.AVERAGE)

        assertEmptyAlbum(emptyAlbum.id)
    }

    @Test
    fun minimumOnEmptyAlbumByProviderKeepsZeroPlayCount() = runDbTest {
        newProvider(id = 1)
        val emptyAlbum = newAlbum(id = 24, providerId = 1)

        aggregateDao.recomputeAlbumsForProvider(providerId = 1, rule = AlbumCountRule.MINIMUM)

        assertEmptyAlbum(emptyAlbum.id)
    }

    private suspend fun assertEmptyAlbum(albumId: Long) {
        val album = requireNotNull(albumDao.selectById(albumId))
        assertThat(album.playCount).isEqualTo(0)
        assertThat(album.lastPlayed).isNull()
        assertThat(album.resumeFilePath).isNull()
    }
}
