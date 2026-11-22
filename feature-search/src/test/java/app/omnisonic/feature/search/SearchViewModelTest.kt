package app.omnisonic.feature.search

import app.omnisonic.core.data.search.RecentPick
import app.omnisonic.core.data.search.RecentPickKind
import app.omnisonic.core.data.search.SearchCategory
import app.omnisonic.feature.search.search.SearchPhase
import app.omnisonic.feature.search.search.SearchViewModel
import app.omnisonic.playback.api.controller.PlaybackController
import app.omnisonic.playback.api.controller.PlayerCoreState
import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.events.PlaybackEventBus
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.renderer.PlaybackRenderer
import app.omnisonic.playback.api.settings.NotificationAction
import app.omnisonic.playback.api.source.QueueSources
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Records `play` calls; everything else is a no-op. */
    private class RecordingController : PlaybackController {

        val plays = mutableListOf<Pair<List<PlaybackQueueEntry>, Int>>()

        override val queueState: StateFlow<QueueState> = MutableStateFlow(QueueState())
        override val playerState: StateFlow<PlayerCoreState> = MutableStateFlow(PlayerCoreState())
        override val events: PlaybackEventBus = object : PlaybackEventBus {
            override val events: SharedFlow<PlaybackEvent> = MutableSharedFlow()
            override fun emit(event: PlaybackEvent) = Unit
        }
        override val queueSources: QueueSources = QueueSources(emptyList())

        override suspend fun play(
            entries: List<PlaybackQueueEntry>,
            startEntriesIndex: Int,
            sourceType: String?,
            sourceData: String?,
        ) {
            plays += entries to startEntriesIndex
        }

        override fun playOrPause() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun replanCurrentStream() = Unit
        override fun setPlaybackSpeed(scale: Float) = Unit
        override fun setPlaybackPitch(pitch: Float) = Unit
        override fun applyAction(action: NotificationAction) = Unit
        override val renderer: PlaybackRenderer = object : PlaybackRenderer {
            override val rendererId: String = "test"
            override val isLocal: Boolean = true
            override suspend fun activate(): Nothing = error("not used")
            override fun deactivate() = Unit
            override fun isActive(): Boolean = false
        }
        override suspend fun restoreQueue(): Boolean = false
        override fun release() = Unit
        override fun skipNext() = Unit
        override fun skipPrevious() = Unit
        override fun jumpToOrderIndex(orderIndex: Int) = Unit
        override fun setRepeatMode(mode: RepeatMode) = Unit
        override fun setShuffleEnabled(enabled: Boolean) = Unit
        override fun removeFromQueue(queueItemId: Long) = Unit
        override fun insertIntoQueue(entry: PlaybackQueueEntry, entriesIndex: Int, orderIndex: Int) = Unit
        override fun moveInQueue(queueItemId: Long, toEntriesIndex: Int) = Unit
        override fun clearQueueExceptCurrent() = Unit
        override fun onPlayWhenReady(playWhenReady: Boolean): Boolean = false
    }

    private fun viewModel(
        source: FakeSearchSource,
        controller: RecordingController = RecordingController(),
    ) = SearchViewModel(source, controller)

    private fun pick(
        kind: RecentPickKind = RecentPickKind.SONG,
        id: Long = 1,
        title: String = "Pick $id",
    ) = RecentPick(kind, id, providerId = 1, externalId = "e-$id", title = title)

    @Test
    fun `initial state is idle and shows persisted preferences`() {
        val source = FakeSearchSource(
            recentPicks = listOf(pick(id = 1, title = "Doomsday")),
            categories = setOf(SearchCategory.SONGS),
        )
        val state = viewModel(source).state.value

        assertThat(state.phase).isEqualTo(SearchPhase.IDLE)
        assertThat(state.query).isEmpty()
        assertThat(state.recentPicks.map { it.title }).containsExactly("Doomsday")
        assertThat(state.enabledCategories).containsExactly(SearchCategory.SONGS)
        assertThat(state.pageSize).isEqualTo(2)
    }

    @Test
    fun `submit searches immediately and does not record the query`() {
        val source = FakeSearchSource()
        val viewModel = viewModel(source)

        viewModel.onQueryChange("  bowie  ")
        viewModel.submit()

        val state = viewModel.state.value
        assertThat(source.calls).hasSize(1)
        assertThat(source.calls.single().query).isEqualTo("bowie")
        assertThat(source.calls.single().offset).isEqualTo(0)
        assertThat(state.phase).isEqualTo(SearchPhase.RESULTS)
        assertThat(state.submittedQuery).isEqualTo("bowie")
        assertThat(state.results?.songs?.items).hasSize(2)
        assertThat(state.hasMore).isTrue()
        assertThat(source.recordedPicks).isEmpty()
    }

    @Test
    fun `blank submit stays idle and never searches`() {
        val source = FakeSearchSource()
        val viewModel = viewModel(source)

        viewModel.onQueryChange("   ")
        viewModel.submit()

        assertThat(source.calls).isEmpty()
        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.IDLE)
    }

    @Test
    fun `typing searches once after the debounce`() = kotlinx.coroutines.test.runTest(testDispatcher.scheduler) {
        val source = FakeSearchSource()
        val viewModel = viewModel(source)

        viewModel.onQueryChange("bowie")
        testDispatcher.scheduler.advanceTimeBy(299)
        assertThat(source.calls).isEmpty()
        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.IDLE)

        testDispatcher.scheduler.advanceTimeBy(1)
        testDispatcher.scheduler.runCurrent()
        assertThat(source.calls).hasSize(1)
        assertThat(source.calls.single().query).isEqualTo("bowie")
        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.RESULTS)
    }

    @Test
    fun `keystrokes coalesce into one search for the last query`() =
        kotlinx.coroutines.test.runTest(testDispatcher.scheduler) {
            val source = FakeSearchSource()
            val viewModel = viewModel(source)

            viewModel.onQueryChange("b")
            testDispatcher.scheduler.advanceTimeBy(100)
            viewModel.onQueryChange("bo")
            testDispatcher.scheduler.advanceTimeBy(100)
            viewModel.onQueryChange("bowie")
            testDispatcher.scheduler.advanceUntilIdle()

            assertThat(source.calls.map { it.query }).containsExactly("bowie")
        }

    @Test
    fun `typing with previous results keeps them visible and marks refreshing`() =
        kotlinx.coroutines.test.runTest(testDispatcher.scheduler) {
            val source = FakeSearchSource()
            val viewModel = viewModel(source)
            viewModel.onQueryChange("bowie")
            viewModel.submit()
            assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.RESULTS)

            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            source.gate = gate
            viewModel.onQueryChange("bowie bowie")
            testDispatcher.scheduler.advanceTimeBy(300)
            testDispatcher.scheduler.runCurrent()

            val state = viewModel.state.value
            assertThat(state.phase).isEqualTo(SearchPhase.RESULTS)
            assertThat(state.refreshing).isTrue()
            assertThat(state.results?.songs?.items).hasSize(2)

            // Let the live search finish; the new rows replace the old ones.
            gate.complete(Unit)
            testDispatcher.scheduler.advanceUntilIdle()
            assertThat(viewModel.state.value.refreshing).isFalse()
        }

    @Test
    fun `blank input cancels the debounce and returns to idle keeping the typed text`() =
        kotlinx.coroutines.test.runTest(testDispatcher.scheduler) {
            val source = FakeSearchSource()
            val viewModel = viewModel(source)

            viewModel.onQueryChange("bowie")
            viewModel.submit()
            viewModel.onQueryChange("   ")
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertThat(state.phase).isEqualTo(SearchPhase.IDLE)
            assertThat(state.query).isEqualTo("   ")
            assertThat(state.results).isNull()
            assertThat(state.refreshing).isFalse()
            assertThat(source.calls).hasSize(1)
        }

    @Test
    fun `a stale result is ignored once the query changed`() =
        kotlinx.coroutines.test.runTest(testDispatcher.scheduler) {
            val source = FakeSearchSource()
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            source.gate = gate
            val viewModel = viewModel(source)

            viewModel.onQueryChange("a")
            testDispatcher.scheduler.advanceUntilIdle()
            assertThat(source.calls.map { it.query }).containsExactly("a")

            viewModel.onQueryChange("ab")
            source.gate = null
            testDispatcher.scheduler.advanceUntilIdle()
            assertThat(source.calls.map { it.query }).containsExactly("a", "ab")
            gate.complete(Unit)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertThat(state.submittedQuery).isEqualTo("ab")
            assertThat(state.results?.query).isEqualTo("ab")
        }

    @Test
    fun `an empty result set maps to the no-results state`() {
        val source = FakeSearchSource(songTotal = 0)
        val viewModel = viewModel(source)

        viewModel.onQueryChange("nothing")
        viewModel.submit()

        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.NO_RESULTS)
        assertThat(viewModel.state.value.hasMore).isFalse()
    }

    @Test
    fun `a failed search maps to the error state and retry recovers`() {
        val source = FakeSearchSource().apply { failure = IOException("db closed") }
        val viewModel = viewModel(source)

        viewModel.onQueryChange("bowie")
        viewModel.submit()

        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.ERROR)

        source.failure = null
        viewModel.retry()

        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.RESULTS)
        assertThat(source.calls).hasSize(2)
    }

    @Test
    fun `toggling a category persists it and re-runs the active query`() {
        val source = FakeSearchSource(categories = setOf(SearchCategory.SONGS))
        val viewModel = viewModel(source)
        viewModel.onQueryChange("bowie")
        viewModel.submit()

        viewModel.toggleCategory(SearchCategory.ALBUMS)

        assertThat(source.currentPreferences().enabledCategories)
            .containsExactly(SearchCategory.SONGS, SearchCategory.ALBUMS)
        assertThat(source.calls).hasSize(2)
        assertThat(source.calls.last().categories)
            .containsExactly(SearchCategory.SONGS, SearchCategory.ALBUMS)
    }

    @Test
    fun `load more appends after a live search and clears hasMore at the end`() =
        kotlinx.coroutines.test.runTest(testDispatcher.scheduler) {
            val source = FakeSearchSource(pageSize = 2, songTotal = 3)
            val viewModel = viewModel(source)
            viewModel.onQueryChange("song")
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.loadMore()

            assertThat(source.calls.map { it.offset }).containsExactly(0, 2).inOrder()
            val results = viewModel.state.value.results!!
            assertThat(results.songs.items.map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
            assertThat(viewModel.state.value.hasMore).isFalse()
            assertThat(viewModel.state.value.appending).isFalse()
        }

    @Test
    fun `load more does not run while the debounce is pending`() =
        kotlinx.coroutines.test.runTest(testDispatcher.scheduler) {
            val source = FakeSearchSource(pageSize = 2, songTotal = 3)
            val viewModel = viewModel(source)
            viewModel.onQueryChange("song")
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.loadMore()
            viewModel.onQueryChange("song two")
            testDispatcher.scheduler.advanceTimeBy(10)

            viewModel.loadMore()

            assertThat(source.calls.map { it.offset }).containsExactly(0, 2).inOrder()
        }

    @Test
    fun `a failed append keeps the loaded results and marks the footer`() {
        val source = FakeSearchSource(pageSize = 2, songTotal = 3).apply {
            appendFailure = IOException("read failed")
        }
        val viewModel = viewModel(source)
        viewModel.onQueryChange("song")
        viewModel.submit()

        viewModel.loadMore()

        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.RESULTS)
        assertThat(viewModel.state.value.results?.songs?.items).hasSize(2)
        assertThat(viewModel.state.value.appendFailed).isTrue()
        assertThat(viewModel.state.value.appending).isFalse()
    }

    @Test
    fun `play song queues the loaded window anchored on the tapped row`() {
        val controller = RecordingController()
        val source = FakeSearchSource(pageSize = 3, songTotal = 3)
        val viewModel = viewModel(source, controller)
        viewModel.onQueryChange("song")
        viewModel.submit()
        controller.plays.clear()

        viewModel.playSong(songId = 2)

        val (entries, start) = controller.plays.single()
        assertThat(start).isEqualTo(1)
        assertThat(entries.map { it.songRowId }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(source.recordedPicks.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `play song skips a null external id and re-anchors`() {
        val controller = RecordingController()
        val source = FakeSearchSource(pageSize = 3, songTotal = 3).apply {
            songsWithNullExternal = setOf(1L)
        }
        val viewModel = viewModel(source, controller)
        viewModel.onQueryChange("song")
        viewModel.submit()
        controller.plays.clear()

        viewModel.playSong(songId = 2)

        val (entries, start) = controller.plays.single()
        assertThat(entries.map { it.songRowId }).containsExactly(2L, 3L).inOrder()
        assertThat(start).isEqualTo(0)
    }

    @Test
    fun `play song no-ops for an out of range row`() {
        val controller = RecordingController()
        val viewModel = viewModel(FakeSearchSource(), controller)
        viewModel.onQueryChange("song")
        viewModel.submit()

        viewModel.playSong(songId = 999)

        assertThat(controller.plays).isEmpty()
    }

    @Test
    fun `a result tap records exactly one pick`() {
        val source = FakeSearchSource()
        val viewModel = viewModel(source)
        viewModel.onQueryChange("song")
        viewModel.submit()

        viewModel.playSong(songId = 1)

        assertThat(source.recordedPicks).hasSize(1)
        assertThat(source.recordedPicks.single().kind).isEqualTo(RecentPickKind.SONG)
    }

    @Test
    fun `selecting a recent pick re-records it so it moves to the top`() {
        val source = FakeSearchSource(
            recentPicks = listOf(pick(id = 1), pick(id = 2), pick(id = 3)),
        )
        val viewModel = viewModel(source)

        viewModel.selectRecentPick(pick(id = 3))

        assertThat(source.currentPreferences().recentPicks.map { it.id })
            .containsExactly(3L, 1L, 2L).inOrder()
        assertThat(source.recordedPicks.map { it.id }).containsExactly(3L)
    }

    @Test
    fun `a recent pick whose row was deleted is pruned silently`() {
        val source = FakeSearchSource(recentPicks = listOf(pick(id = 4)))
        source.missing += RecentPickKind.SONG to 4L
        val viewModel = viewModel(source)

        viewModel.selectRecentPick(pick(id = 4))

        assertThat(source.currentPreferences().recentPicks).isEmpty()
    }


    @Test
    fun `recents can be removed and cleared`() {
        val source = FakeSearchSource(recentPicks = listOf(pick(id = 1), pick(id = 2)))
        val viewModel = viewModel(source)

        viewModel.removeRecentPick(RecentPickKind.SONG, 1)
        assertThat(viewModel.state.value.recentPicks.map { it.id }).containsExactly(2L)

        viewModel.clearRecentPicks()
        assertThat(viewModel.state.value.recentPicks).isEmpty()
    }

    @Test
    fun `clear query returns to the idle state`() {
        val source = FakeSearchSource()
        val viewModel = viewModel(source)
        viewModel.onQueryChange("bowie")
        viewModel.submit()

        viewModel.clearQuery()

        val state = viewModel.state.value
        assertThat(state.phase).isEqualTo(SearchPhase.IDLE)
        assertThat(state.query).isEmpty()
        assertThat(state.results).isNull()
        assertThat(state.hasMore).isFalse()
    }
}
