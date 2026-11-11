package app.omnisonic.core.data.search

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.network.NetworkMonitor
import app.omnisonic.core.data.network.NetworkState
import app.omnisonic.core.data.network.ProviderStatusTracker
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Phase 5 acceptance budget for search: a 100k-song library answers a
 * worst-case (matches-everything) first page inside the Phase 2 query budget.
 * Real-device cold-cache latency stays device-gated [UNKNOWN].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchPerformanceTest : DatabaseTestBase() {

    @Test
    fun firstSearchPageOverHundredThousandSongsStaysWithinBudget() = runDbTest {
        newProvider(id = 1)
        val songs = List(SEED_SIZE) { index -> seededSong(index) }
        val seedElapsed = measureTimeMillis { songDao.upsertRows(songs) }

        val repository = SearchRepository(
            db = db,
            preferences = preferencesStore(),
            providerVisibility = visibilityStore(),
            providerStatuses = ProviderStatusTracker(db, NetworkMonitor { NetworkState(connected = true, wifi = true) }),
        )

        var results: SearchResults? = null
        val searchElapsed = measureTimeMillis {
            results = repository.search(QUERY, categories = setOf(SearchCategory.SONGS))
        }

        val storedRows = db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM songs")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

        assertThat(storedRows).isEqualTo(SEED_SIZE)
        assertThat(results!!.songs.items).hasSize(SearchPreferencesStore.DEFAULT_PAGE_SIZE)
        assertThat(results!!.songs.hasMore).isTrue()
        assertThat(seedElapsed).isAtMost(SEED_BUDGET_MS)
        assertThat(searchElapsed).isAtMost(SEARCH_BUDGET_MS)
        println("search-perf seedMs=$seedElapsed firstPageMs=$searchElapsed")
    }

    private fun seededSong(index: Int): SongEntity = SongEntity(
        id = 0,
        providerId = 1,
        externalId = "track-$index",
        title = "track-$index",
        latinTitle = "track-$index",
        sortTitle = "track-$index",
        displayArtist = "artist-${index % 5_000}",
        playCount = index % 997,
        lastPlayed = (index % 997).toLong(),
        updatedAt = 999,
    )

    private fun preferencesStore(): SearchPreferencesStore = mockk {
        every { preferences } returns flowOf(
            SearchPreferences(
                enabledCategories = SearchCategory.entries.toSet(),
                recentSearches = emptyList(),
                pageSize = SearchPreferencesStore.DEFAULT_PAGE_SIZE,
            ),
        )
    }

    private fun visibilityStore(): ProviderVisibilityStore = mockk {
        every { hiddenProviderIds } returns flowOf(emptySet())
    }

    private companion object {
        const val SEED_SIZE = 100_000
        const val QUERY = "track"
        const val SEED_BUDGET_MS = 60_000L
        const val SEARCH_BUDGET_MS = 3_000L
    }
}
