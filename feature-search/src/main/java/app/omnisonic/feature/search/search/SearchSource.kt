package app.omnisonic.feature.search.search

import app.omnisonic.core.data.search.SearchCategory
import app.omnisonic.core.data.search.SearchPreferences
import app.omnisonic.core.data.search.SearchPreferencesStore
import app.omnisonic.core.data.search.SearchRepository
import app.omnisonic.core.data.search.SearchResults
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * The search read surface the ViewModel consumes: the repository call plus the
 * user preference flows it has to show and edit. Decouples the ViewModel from
 * Room and DataStore so plain JVM tests can fake results and preferences.
 */
interface SearchSource {

    val preferences: Flow<SearchPreferences>

    suspend fun search(
        query: String,
        offset: Int,
        limit: Int,
        categories: Set<SearchCategory>,
    ): SearchResults

    suspend fun recordRecentSearch(query: String)

    suspend fun removeRecentSearch(query: String)

    suspend fun clearRecentSearches()

    suspend fun setCategoryEnabled(category: SearchCategory, enabled: Boolean)
}

class RepositorySearchSource @Inject constructor(
    private val repository: SearchRepository,
    private val preferencesStore: SearchPreferencesStore,
) : SearchSource {

    override val preferences: Flow<SearchPreferences> = preferencesStore.preferences

    override suspend fun search(
        query: String,
        offset: Int,
        limit: Int,
        categories: Set<SearchCategory>,
    ): SearchResults = repository.search(
        query = query,
        offset = offset,
        limit = limit,
        categories = categories,
    )

    override suspend fun recordRecentSearch(query: String) =
        preferencesStore.recordRecentSearch(query)

    override suspend fun removeRecentSearch(query: String) =
        preferencesStore.removeRecentSearch(query)

    override suspend fun clearRecentSearches() = preferencesStore.clearRecentSearches()

    override suspend fun setCategoryEnabled(category: SearchCategory, enabled: Boolean) =
        preferencesStore.setCategoryEnabled(category, enabled)
}
