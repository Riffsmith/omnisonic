package app.omnisonic.core.data.search

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.network.ProviderStatusTracker
import app.omnisonic.provider.api.ProviderStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Local-DB search over the synced library: space-split tokens ANDed together,
 * each matched against title / roman title / sort title / display artist
 * (albums also disc titles). Plain suspend call with no server round trip, so
 * a future media session callback can reuse it unchanged.
 *
 * Provider visibility is folded into the query through [ProfileScope]: hidden
 * providers come from [ProviderVisibilityStore], unavailable providers from
 * [ProviderStatusTracker] (any status other than OK excludes). The repository
 * reads the tracker's current state; it never triggers network checks itself.
 */
@Singleton
class SearchRepository @Inject constructor(
    private val db: OmnisonicDatabase,
    private val preferences: SearchPreferencesStore,
    private val providerVisibility: ProviderVisibilityStore,
    private val providerStatuses: ProviderStatusTracker,
) {

    /**
     * @param categories null means "read the user's category toggles".
     * @param scope caller-supplied exclusions; hidden and unavailable providers
     *   are added on top.
     */
    suspend fun search(
        query: String,
        offset: Int = 0,
        limit: Int = SearchPreferencesStore.DEFAULT_PAGE_SIZE,
        categories: Set<SearchCategory>? = null,
        scope: ProfileScope = ProfileScope.Unrestricted,
    ): SearchResults {
        val terms = SearchQuery.terms(query)
        val requested = categories ?: preferences.preferences.first().enabledCategories
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = SearchPreferencesStore.sanitizePageSize(limit)

        if (terms.isEmpty() || requested.isEmpty()) {
            return SearchResults.empty(query = query, categories = requested, offset = safeOffset, limit = safeLimit)
        }

        val effectiveScope = effectiveScope(scope)
        val fetchLimit = safeLimit + 1
        val searchDao = db.searchDao()

        return SearchResults(
            query = query,
            terms = terms.map { it.text },
            categories = requested,
            songs = if (SearchCategory.SONGS in requested) {
                pageOf(safeOffset, safeLimit) {
                    searchDao.searchSongRows(effectiveScope, terms, fetchLimit, safeOffset)
                }
            } else {
                SearchPage.empty(safeOffset, safeLimit)
            },
            albums = if (SearchCategory.ALBUMS in requested) {
                pageOf(safeOffset, safeLimit) {
                    searchDao.searchAlbumRows(effectiveScope, terms, fetchLimit, safeOffset)
                }
            } else {
                SearchPage.empty(safeOffset, safeLimit)
            },
            artists = if (SearchCategory.ARTISTS in requested) {
                pageOf(safeOffset, safeLimit) {
                    searchDao.searchArtistRows(effectiveScope, terms, fetchLimit, safeOffset)
                }
            } else {
                SearchPage.empty(safeOffset, safeLimit)
            },
            genres = if (SearchCategory.GENRES in requested) {
                pageOf(safeOffset, safeLimit) {
                    searchDao.searchGenreRows(effectiveScope, terms, fetchLimit, safeOffset)
                }
            } else {
                SearchPage.empty(safeOffset, safeLimit)
            },
            playlists = if (SearchCategory.PLAYLISTS in requested) {
                pageOf(safeOffset, safeLimit) {
                    searchDao.searchPlaylistRows(effectiveScope, terms, fetchLimit, safeOffset)
                }
            } else {
                SearchPage.empty(safeOffset, safeLimit)
            },
        )
    }

    private suspend fun effectiveScope(base: ProfileScope): ProfileScope {
        // A search is the first work many sessions run; refresh so the status
        // map reflects the current network/credential state instead of a
        // possibly empty startup map.
        providerStatuses.refresh()
        val hidden = providerVisibility.hiddenProviderIds.first()
        val unavailable = providerStatuses.statuses.value
            .filterValues { status -> status != ProviderStatus.OK }
            .keys
        return base.excluding(hidden + unavailable)
    }

    private suspend fun <T> pageOf(
        offset: Int,
        limit: Int,
        rows: suspend () -> List<T>,
    ): SearchPage<T> {
        val fetched = rows()
        return SearchPage(
            items = fetched.take(limit),
            offset = offset,
            limit = limit,
            hasMore = fetched.size > limit,
        )
    }
}
