package app.omnisonic.shell

import app.omnisonic.core.data.search.SearchCategory
import app.omnisonic.core.data.search.SearchPreferences
import app.omnisonic.core.data.search.SearchResults
import app.omnisonic.feature.search.search.SearchSource
import app.omnisonic.feature.search.search.SearchViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Keeps the search tab on its idle state; search behavior is tested in `:feature-search`. */
internal class EmptySearchSource : SearchSource {

    private val prefs = MutableStateFlow(
        SearchPreferences(
            enabledCategories = SearchCategory.entries.toSet(),
            recentSearches = emptyList(),
            pageSize = 30,
        ),
    )

    override val preferences: Flow<SearchPreferences> = prefs.asStateFlow()

    override suspend fun search(
        query: String,
        offset: Int,
        limit: Int,
        categories: Set<SearchCategory>,
    ): SearchResults = SearchResults.empty(query, categories, offset, limit)

    override suspend fun recordRecentSearch(query: String) = Unit

    override suspend fun removeRecentSearch(query: String) = Unit

    override suspend fun clearRecentSearches() {
        prefs.update { it.copy(recentSearches = emptyList()) }
    }

    override suspend fun setCategoryEnabled(category: SearchCategory, enabled: Boolean) {
        prefs.update { prefs ->
            prefs.copy(
                enabledCategories = if (enabled) {
                    prefs.enabledCategories + category
                } else {
                    prefs.enabledCategories - category
                },
            )
        }
    }
}

internal fun testSearchViewModel(): SearchViewModel = SearchViewModel(EmptySearchSource())
