package app.omnisonic.core.data

import app.omnisonic.core.data.browse.BrowseRepository
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.history.PlayHistoryRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayHistoryRepositoryTest : DatabaseTestBase() {

    private fun repository() = PlayHistoryRepository(db)

    @Test
    fun `page probe reports hasMore only when a row overflows the window`() = runDbTest {
        repeat(3) { n ->
            playEventDao.recordPlay(1, "song-$n", playedAtMs = (n + 1) * 1_000L, durationMs = 10)
        }
        val repo = repository()

        val first = repo.recentPlays(limit = 2)
        assertThat(first.items.map { it.externalId }).containsExactly("song-2", "song-1").inOrder()
        assertThat(first.hasMore).isTrue()

        val last = repo.recentPlays(offset = 2, limit = 2)
        assertThat(last.items.map { it.externalId }).containsExactly("song-0")
        assertThat(last.hasMore).isFalse()
    }

    @Test
    fun `scope passes through to both the page and the count`() = runDbTest {
        playEventDao.recordPlay(1, "song-1", playedAtMs = 1_000, durationMs = 10)
        playEventDao.recordPlay(2, "song-2", playedAtMs = 2_000, durationMs = 10)
        val repo = repository()
        val scope = ProfileScope(excludedProviderIds = listOf(2))

        assertThat(repo.recentCount(scope)).isEqualTo(1)
        assertThat(repo.recentPlays(scope = scope, limit = 10).items.map { it.providerId })
            .containsExactly(1L)
    }

    @Test
    fun `limit is clamped to the shared page bounds`() = runDbTest {
        repeat(3) { n ->
            playEventDao.recordPlay(1, "song-$n", playedAtMs = (n + 1) * 1_000L, durationMs = 10)
        }

        val page = repository().recentPlays(limit = 10_000)

        assertThat(page.limit).isEqualTo(BrowseRepository.MAX_PAGE_LIMIT)
        assertThat(page.items).hasSize(3)
        assertThat(page.hasMore).isFalse()
    }
}
