package app.omnisonic.core.data

import app.omnisonic.core.data.db.aggregation.AlbumCountRule
import app.omnisonic.core.data.db.aggregation.recomputeAlbumsForProvider
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.SongEntity
import com.google.common.truth.Truth.assertThat
import kotlin.system.measureTimeMillis
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabasePerformanceTest : DatabaseTestBase() {

    @Test
    fun bulkSeedAndAggregateStayWithinDeclaredBudgets() = runDbTest {
        newProvider(id = 1)
        newAlbum(id = 5, providerId = 1)
        val songs = List(SEED_SIZE) { index -> seededSong(index) }

        val seedElapsed = measureTimeMillis { songDao.upsertAll(songs) }
        val recomputeElapsed = measureTimeMillis {
            aggregateDao.recomputeAlbumsForProvider(providerId = 1, rule = AlbumCountRule.SUM)
            aggregateDao.recomputeArtistsByProvider(providerId = 1, includeSupporting = false)
        }
        var mostPlayed: List<AlbumEntity> = emptyList()
        val mostPlayedElapsed = measureTimeMillis {
            mostPlayed = albumDao.selectMostPlayed(providerId = 1, limit = 50)
        }

        val storedRows = db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM songs")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

        assertThat(storedRows).isEqualTo(SEED_SIZE)
        assertThat(mostPlayed).hasSize(1)
        assertThat(mostPlayed.single().playCount).isGreaterThan(0)
        assertThat(seedElapsed).isAtMost(SEED_BUDGET_MS)
        assertThat(recomputeElapsed + mostPlayedElapsed).isAtMost(QUERY_BUDGET_MS)
        println("perf seedMs=$seedElapsed recomputeMs=$recomputeElapsed topMs=$mostPlayedElapsed")
    }

    private fun seededSong(index: Int): SongEntity = SongEntity(
        id = 0,
        providerId = 1,
        externalId = "track-$index",
        albumId = 5,
        title = "track-$index",
        playCount = index % 997,
        lastPlayed = (index % 3).toLong(),
        updatedAt = 999,
    )

    private companion object {
        const val SEED_SIZE = 100_000
        const val SEED_BUDGET_MS = 60_000L
        const val QUERY_BUDGET_MS = 3_000L
    }
}
