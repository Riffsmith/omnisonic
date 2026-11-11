package app.omnisonic.feature.search

import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.search.SearchCategory
import app.omnisonic.core.data.search.SearchPage
import app.omnisonic.core.data.search.SearchPreferences
import app.omnisonic.core.data.search.SearchResults
import app.omnisonic.feature.search.search.SearchSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeSearchSource(
    recentSearches: List<String> = emptyList(),
    categories: Set<SearchCategory> = SearchCategory.entries.toSet(),
    pageSize: Int = 2,
    var songTotal: Int = 5,
) : SearchSource {

    data class Call(
        val query: String,
        val offset: Int,
        val limit: Int,
        val categories: Set<SearchCategory>,
    )

    private val prefs = MutableStateFlow(
        SearchPreferences(
            enabledCategories = categories,
            recentSearches = recentSearches,
            pageSize = pageSize,
        ),
    )

    override val preferences: Flow<SearchPreferences> = prefs.asStateFlow()

    val calls = mutableListOf<Call>()
    val recorded = mutableListOf<String>()
    var failure: Throwable? = null
    var appendFailure: Throwable? = null

    fun currentPreferences(): SearchPreferences = prefs.value

    override suspend fun search(
        query: String,
        offset: Int,
        limit: Int,
        categories: Set<SearchCategory>,
    ): SearchResults {
        calls += Call(query, offset, limit, categories)
        if (offset == 0) failure?.let { throw it } else appendFailure?.let { throw it }
        return SearchResults(
            query = query,
            terms = query.split(" "),
            categories = categories,
            songs = if (SearchCategory.SONGS in categories) {
                window(offset, limit, songTotal) { index ->
                    SongEntity(
                        id = index.toLong() + 1,
                        providerId = 1,
                        title = "Song $index",
                        updatedAt = 0,
                    )
                }
            } else {
                SearchPage.empty(offset, limit)
            },
            albums = SearchPage.empty(offset, limit),
            artists = SearchPage.empty(offset, limit),
            genres = SearchPage.empty(offset, limit),
            playlists = SearchPage.empty(offset, limit),
        )
    }

    override suspend fun recordRecentSearch(query: String) {
        recorded += query
        prefs.update { prefs ->
            prefs.copy(
                recentSearches = (listOf(query) + prefs.recentSearches
                    .filterNot { it.equals(query, ignoreCase = true) }).take(10),
            )
        }
    }

    override suspend fun removeRecentSearch(query: String) {
        prefs.update { it.copy(recentSearches = it.recentSearches.filterNot { q -> q == query }) }
    }

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

    private fun <T> window(
        offset: Int,
        limit: Int,
        total: Int,
        make: (Int) -> T,
    ): SearchPage<T> {
        if (offset >= total) return SearchPage.empty(offset, limit)
        val end = minOf(offset + limit, total)
        return SearchPage(
            items = (offset until end).map(make),
            offset = offset,
            limit = limit,
            hasMore = end < total,
        )
    }
}
