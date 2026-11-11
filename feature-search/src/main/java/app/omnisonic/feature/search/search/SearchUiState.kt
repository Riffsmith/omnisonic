package app.omnisonic.feature.search.search

import app.omnisonic.core.data.search.SearchCategory
import app.omnisonic.core.data.search.SearchPage
import app.omnisonic.core.data.search.SearchPreferences
import app.omnisonic.core.data.search.SearchResults

/** Search screen states (UI_SPEC.md §3.6). */
enum class SearchPhase { IDLE, SEARCHING, RESULTS, NO_RESULTS, ERROR }

data class SearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val phase: SearchPhase = SearchPhase.IDLE,
    val results: SearchResults? = null,
    val recentSearches: List<String> = emptyList(),
    val enabledCategories: Set<SearchCategory> = SearchCategory.entries.toSet(),
    val pageSize: Int = 30,
    val hasMore: Boolean = false,
    val appending: Boolean = false,
    val appendFailed: Boolean = false,
) {
    val isActive: Boolean get() = submittedQuery.isNotBlank()
}

internal fun SearchResults.anyHasMore(): Boolean =
    songs.hasMore || albums.hasMore || artists.hasMore || genres.hasMore || playlists.hasMore

/** Offset of the next window; every category pages at the same offset. */
internal fun SearchResults.nextOffset(): Int = maxOf(
    songs.offset + songs.items.size,
    albums.offset + albums.items.size,
    artists.offset + artists.items.size,
    genres.offset + genres.items.size,
    playlists.offset + playlists.items.size,
)

internal fun <T> SearchPage<T>.appendWith(next: SearchPage<T>): SearchPage<T> = SearchPage(
    items = items + next.items,
    offset = offset,
    limit = next.limit,
    hasMore = next.hasMore,
)

internal fun SearchResults.appendWith(next: SearchResults): SearchResults = SearchResults(
    query = query,
    terms = (terms + next.terms).distinct(),
    categories = categories,
    songs = songs.appendWith(next.songs),
    albums = albums.appendWith(next.albums),
    artists = artists.appendWith(next.artists),
    genres = genres.appendWith(next.genres),
    playlists = playlists.appendWith(next.playlists),
)

internal fun SearchPreferences.toUi(state: SearchUiState): SearchUiState = state.copy(
    recentSearches = recentSearches,
    enabledCategories = enabledCategories,
    pageSize = pageSize,
)
