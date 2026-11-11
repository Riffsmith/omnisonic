package app.omnisonic.feature.search.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.search.SearchCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Search screen state over [SearchSource] (UI_SPEC.md §3.6): submit runs one
 * local repository call, category toggles persist to the preferences store and
 * re-run the active query, recent searches are recorded only on a successful
 * submit. Paging is a single load-more footer over the per-category pages.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val source: SearchSource,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var categoriesInitialised = false

    init {
        viewModelScope.launch {
            source.preferences.collect { preferences ->
                val previousCategories = _state.value.enabledCategories
                _state.update { preferences.toUi(it) }
                if (categoriesInitialised && preferences.enabledCategories != previousCategories) {
                    rerunIfActive()
                }
                categoriesInitialised = true
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun submit() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) {
            clearQuery()
            return
        }
        runSearch(query, record = true)
    }

    fun selectRecentSearch(query: String) {
        _state.update { it.copy(query = query) }
        runSearch(query.trim(), record = true)
    }

    fun retry() {
        val query = _state.value.submittedQuery.ifBlank { _state.value.query.trim() }
        if (query.isNotBlank()) runSearch(query, record = false)
    }

    fun clearQuery() {
        job?.cancel()
        job = null
        _state.update {
            it.copy(
                query = "",
                submittedQuery = "",
                phase = SearchPhase.IDLE,
                results = null,
                hasMore = false,
                appending = false,
                appendFailed = false,
            )
        }
    }

    fun toggleCategory(category: SearchCategory) {
        val enabled = category !in _state.value.enabledCategories
        viewModelScope.launch { source.setCategoryEnabled(category, enabled) }
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch { source.removeRecentSearch(query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { source.clearRecentSearches() }
    }

    fun loadMore() {
        val current = _state.value
        val results = current.results ?: return
        if (!current.hasMore || current.appending || job?.isActive == true) return

        val offset = results.nextOffset()
        _state.update { it.copy(appending = true, appendFailed = false) }
        job = viewModelScope.launch {
            try {
                val next = source.search(
                    query = current.submittedQuery,
                    offset = offset,
                    limit = current.pageSize,
                    categories = current.enabledCategories,
                )
                val merged = results.appendWith(next)
                _state.update {
                    it.copy(
                        results = merged,
                        hasMore = merged.anyHasMore(),
                        appending = false,
                        appendFailed = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(appending = false, appendFailed = true) }
            }
        }
    }

    private fun rerunIfActive() {
        val query = _state.value.submittedQuery
        if (query.isNotBlank()) runSearch(query, record = false)
    }

    private fun runSearch(query: String, record: Boolean) {
        job?.cancel()
        _state.update {
            it.copy(
                query = if (it.query.isBlank()) query else it.query,
                submittedQuery = query,
                phase = SearchPhase.SEARCHING,
                results = null,
                hasMore = false,
                appending = false,
                appendFailed = false,
            )
        }
        job = viewModelScope.launch {
            try {
                val results = source.search(
                    query = query,
                    offset = 0,
                    limit = _state.value.pageSize,
                    categories = _state.value.enabledCategories,
                )
                _state.update {
                    it.copy(
                        phase = if (results.isEmpty) {
                            SearchPhase.NO_RESULTS
                        } else {
                            SearchPhase.RESULTS
                        },
                        results = results,
                        hasMore = results.anyHasMore(),
                    )
                }
                if (record) source.recordRecentSearch(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(phase = SearchPhase.ERROR, results = null, hasMore = false)
                }
            }
        }
    }
}
