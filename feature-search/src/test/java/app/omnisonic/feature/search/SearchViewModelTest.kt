package app.omnisonic.feature.search

import app.omnisonic.core.data.search.SearchCategory
import app.omnisonic.feature.search.search.SearchPhase
import app.omnisonic.feature.search.search.SearchViewModel
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is idle and shows persisted preferences`() {
        val source = FakeSearchSource(
            recentSearches = listOf("bowie", "kate bush"),
            categories = setOf(SearchCategory.SONGS),
        )
        val state = SearchViewModel(source).state.value

        assertThat(state.phase).isEqualTo(SearchPhase.IDLE)
        assertThat(state.query).isEmpty()
        assertThat(state.recentSearches).containsExactly("bowie", "kate bush").inOrder()
        assertThat(state.enabledCategories).containsExactly(SearchCategory.SONGS)
        assertThat(state.pageSize).isEqualTo(2)
    }

    @Test
    fun `submit searches with the enabled categories and records the query`() {
        val source = FakeSearchSource()
        val viewModel = SearchViewModel(source)

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
        assertThat(source.recorded).containsExactly("bowie")
        assertThat(state.recentSearches).containsExactly("bowie")
    }

    @Test
    fun `blank submit stays idle and never searches`() {
        val source = FakeSearchSource()
        val viewModel = SearchViewModel(source)

        viewModel.onQueryChange("   ")
        viewModel.submit()

        assertThat(source.calls).isEmpty()
        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.IDLE)
    }

    @Test
    fun `an empty result set maps to the no-results state`() {
        val source = FakeSearchSource(songTotal = 0)
        val viewModel = SearchViewModel(source)

        viewModel.onQueryChange("nothing")
        viewModel.submit()

        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.NO_RESULTS)
        assertThat(viewModel.state.value.hasMore).isFalse()
    }

    @Test
    fun `a failed search maps to the error state and retry recovers`() {
        val source = FakeSearchSource().apply { failure = IOException("db closed") }
        val viewModel = SearchViewModel(source)

        viewModel.onQueryChange("bowie")
        viewModel.submit()

        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.ERROR)
        assertThat(source.recorded).isEmpty()

        source.failure = null
        viewModel.retry()

        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.RESULTS)
        assertThat(source.calls).hasSize(2)
    }

    @Test
    fun `toggling a category persists it and re-runs the active query`() {
        val source = FakeSearchSource(categories = setOf(SearchCategory.SONGS))
        val viewModel = SearchViewModel(source)
        viewModel.onQueryChange("bowie")
        viewModel.submit()

        viewModel.toggleCategory(SearchCategory.ALBUMS)

        assertThat(source.currentPreferences().enabledCategories)
            .containsExactly(SearchCategory.SONGS, SearchCategory.ALBUMS)
        assertThat(source.calls).hasSize(2)
        assertThat(source.calls.last().categories)
            .containsExactly(SearchCategory.SONGS, SearchCategory.ALBUMS)
        assertThat(viewModel.state.value.enabledCategories)
            .containsExactly(SearchCategory.SONGS, SearchCategory.ALBUMS)
    }

    @Test
    fun `selecting a recent search fills the query and runs it`() {
        val source = FakeSearchSource(recentSearches = listOf("kate bush"))
        val viewModel = SearchViewModel(source)

        viewModel.selectRecentSearch("kate bush")

        assertThat(viewModel.state.value.query).isEqualTo("kate bush")
        assertThat(source.calls.single().query).isEqualTo("kate bush")
        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.RESULTS)
    }

    @Test
    fun `load more appends the next window and clears hasMore at the end`() {
        val source = FakeSearchSource(pageSize = 2, songTotal = 3)
        val viewModel = SearchViewModel(source)
        viewModel.onQueryChange("song")
        viewModel.submit()

        viewModel.loadMore()

        assertThat(source.calls.map { it.offset }).containsExactly(0, 2).inOrder()
        val results = viewModel.state.value.results!!
        assertThat(results.songs.items.map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(viewModel.state.value.hasMore).isFalse()
        assertThat(viewModel.state.value.appending).isFalse()
    }

    @Test
    fun `a failed append keeps the loaded results and marks the footer`() {
        val source = FakeSearchSource(pageSize = 2, songTotal = 3).apply {
            appendFailure = IOException("read failed")
        }
        val viewModel = SearchViewModel(source)
        viewModel.onQueryChange("song")
        viewModel.submit()

        viewModel.loadMore()

        assertThat(viewModel.state.value.phase).isEqualTo(SearchPhase.RESULTS)
        assertThat(viewModel.state.value.results?.songs?.items).hasSize(2)
        assertThat(viewModel.state.value.appendFailed).isTrue()
        assertThat(viewModel.state.value.appending).isFalse()
    }

    @Test
    fun `recent searches can be removed and cleared`() {
        val source = FakeSearchSource(recentSearches = listOf("a", "b"))
        val viewModel = SearchViewModel(source)

        viewModel.removeRecentSearch("a")
        assertThat(viewModel.state.value.recentSearches).containsExactly("b")

        viewModel.clearRecentSearches()
        assertThat(viewModel.state.value.recentSearches).isEmpty()
    }

    @Test
    fun `clear query returns to the idle state`() {
        val source = FakeSearchSource()
        val viewModel = SearchViewModel(source)
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
