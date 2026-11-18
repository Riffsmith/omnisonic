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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        val viewModel = HistoryViewModel(source, FakeHistoryControls())

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
        val viewModel = HistoryViewModel(source, FakeHistoryControls())
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
        val viewModel = HistoryViewModel(FakeHistorySource(total = 0), FakeHistoryControls())

        viewModel.load()

        assertThat(viewModel.state.value).isEqualTo(BrowseUiState.Empty)
    }

    @Test
    fun `initial failure lands on error and load retries`() = runTest {
        val source = FakeHistorySource(total = 5, failPagesAt = setOf(0))
        val viewModel = HistoryViewModel(source, FakeHistoryControls())

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
        val viewModel = HistoryViewModel(source, FakeHistoryControls())
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
        HistoryViewModel(source, FakeHistoryControls())

        source.changeFeed.emit(Unit)
        runCurrent()

        assertThat(source.pageOffsets).isEmpty()
    }

    @Test
    fun `removePlay delegates to the controls`() = runTest {
        val controls = FakeHistoryControls()
        val viewModel = HistoryViewModel(FakeHistorySource(total = 1), controls)

        viewModel.removePlay(42)
        runCurrent()

        assertThat(controls.deletedEventIds).containsExactly(42L)
    }

    @Test
    fun `editPlayCount delegates with provider external id and value`() = runTest {
        val controls = FakeHistoryControls()
        val viewModel = HistoryViewModel(FakeHistorySource(total = 1), controls)

        viewModel.editPlayCount(providerId = 7, externalId = "e-1", count = 5)
        runCurrent()

        assertThat(controls.playCounts).containsExactly(Triple(7L, "e-1", 5))
    }

    @Test
    fun `wipeProvider delegates to the controls`() = runTest {
        val controls = FakeHistoryControls()
        val viewModel = HistoryViewModel(FakeHistorySource(total = 1), controls)

        viewModel.wipeProvider(3)
        runCurrent()

        assertThat(controls.wipedProviders).containsExactly(3L)
    }

    @Test
    fun `setRecordingPaused exposes the gate state`() = runTest {
        val controls = FakeHistoryControls()
        val viewModel = HistoryViewModel(FakeHistorySource(total = 1), controls)
        assertThat(viewModel.pausedProviders.value).isEmpty()

        viewModel.setRecordingPaused(3, paused = true)
        assertThat(controls.paused).containsExactly(3L, true)
        assertThat(viewModel.pausedProviders.value).containsExactly(3L)

        viewModel.setRecordingPaused(3, paused = false)
        assertThat(viewModel.pausedProviders.value).isEmpty()
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

    private class FakeHistoryControls : HistoryControls {

        val deletedEventIds = mutableListOf<Long>()
        val playCounts = mutableListOf<Triple<Long, String, Int>>()
        val wipedProviders = mutableListOf<Long>()
        val paused = mutableMapOf<Long, Boolean>()
        private val gate = MutableStateFlow<Set<Long>>(emptySet())

        override val pausedProviders: StateFlow<Set<Long>>
            get() = gate

        override suspend fun deletePlay(eventId: Long): Boolean {
            deletedEventIds.add(eventId)
            return true
        }

        override suspend fun setPlayCount(providerId: Long, externalId: String, count: Int) {
            playCounts.add(Triple(providerId, externalId, count))
        }

        override suspend fun wipeProvider(providerId: Long) {
            wipedProviders.add(providerId)
        }

        override fun setRecordingPaused(providerId: Long, paused: Boolean) {
            this.paused[providerId] = paused
            gate.value = if (paused) gate.value + providerId else gate.value - providerId
        }
    }
}
