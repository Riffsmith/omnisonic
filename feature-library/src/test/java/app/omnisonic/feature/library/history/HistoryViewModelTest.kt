package app.omnisonic.feature.library.history

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.db.RecentPlayRow
import app.omnisonic.feature.library.browse.BrowseUiState
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load lands on content with count and hasMore`() = runTest {
        val source = FakeHistorySource(total = 130)
        val viewModel = HistoryViewModel(source)

        viewModel.load()

        val content = viewModel.state.value as BrowseUiState.Content
        assertThat(content.items).hasSize(60)
        assertThat(content.totalCount).isEqualTo(130)
        assertThat(content.hasMore).isTrue()
        assertThat(content.items.map { it.songTitle }.first()).isEqualTo("Song 0")
    }

    @Test
    fun `loadMore appends the next window newest first without duplicate keys`() = runTest {
        val source = FakeHistorySource(total = 130)
        val viewModel = HistoryViewModel(source)
        viewModel.load()

        viewModel.loadMore()

        val content = viewModel.state.value as BrowseUiState.Content
        assertThat(content.items).hasSize(120)
        assertThat(source.pageOffsets).containsExactly(0, 60).inOrder()
        assertThat(content.items.map { it.eventId }).containsNoDuplicates()
        assertThat(content.hasMore).isTrue()

        viewModel.loadMore()
        val final = viewModel.state.value as BrowseUiState.Content
        assertThat(final.items).hasSize(130)
        assertThat(final.hasMore).isFalse()
    }

    @Test
    fun `empty history lands on the empty state`() = runTest {
        val viewModel = HistoryViewModel(FakeHistorySource(total = 0))

        viewModel.load()

        assertThat(viewModel.state.value).isEqualTo(BrowseUiState.Empty)
    }

    @Test
    fun `initial failure lands on error and load retries`() = runTest {
        val source = FakeHistorySource(total = 5, failPagesAt = setOf(0))
        val viewModel = HistoryViewModel(source)

        viewModel.load()
        assertThat(viewModel.state.value).isEqualTo(BrowseUiState.Error)

        source.failPagesAt = emptySet()
        viewModel.load()
        val state = viewModel.state.value as BrowseUiState.Content
        assertThat(state.items).hasSize(5)
    }

    @Test
    fun `a play-event change reloads the open window with the new count`() = runTest {
        val source = FakeHistorySource(total = 3)
        val viewModel = HistoryViewModel(source)
        viewModel.load()
        assertThat((viewModel.state.value as BrowseUiState.Content).totalCount).isEqualTo(3)

        source.total = 90
        source.changeFeed.emit(Unit)
        runCurrent()

        val content = viewModel.state.value as BrowseUiState.Content
        assertThat(content.totalCount).isEqualTo(90)
        assertThat(content.items).hasSize(60)
        assertThat(source.pageOffsets).containsExactly(0, 0).inOrder()
    }

    @Test
    fun `a change before the first load fetches nothing`() = runTest {
        val source = FakeHistorySource(total = 10)
        HistoryViewModel(source)

        source.changeFeed.emit(Unit)
        runCurrent()

        assertThat(source.pageOffsets).isEmpty()
    }

    private class FakeHistorySource(
        var total: Int = 0,
        var failPagesAt: Set<Int> = emptySet(),
    ) : HistorySource {

        val changeFeed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override val changes: Flow<Unit>
            get() = changeFeed

        val pageOffsets = mutableListOf<Int>()

        override suspend fun recentPlays(offset: Int, limit: Int): BrowsePage<RecentPlayRow> {
            pageOffsets.add(offset)
            if (offset in failPagesAt) throw IOException("history page failed")
            val rows = (offset until minOf(offset + limit, total)).map { n ->
                RecentPlayRow(
                    eventId = (total - n).toLong(),
                    providerId = 1,
                    externalId = "e-$n",
                    playedAtMs = (total - n) * 1_000L,
                    played = true,
                    skipped = false,
                    durationMs = 60_000,
                    songId = null,
                    songTitle = "Song $n",
                    displayArtist = "Artist",
                    albumTitle = "Album",
                    imagePath = null,
                )
            }
            return BrowsePage(
                items = rows,
                offset = offset,
                limit = limit,
                hasMore = offset + limit < total,
            )
        }

        override suspend fun recentCount(): Int = total
    }
}
