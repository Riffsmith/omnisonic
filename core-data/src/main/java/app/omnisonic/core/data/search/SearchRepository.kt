package app.omnisonic.core.data.search

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
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

    /** Builds the stored recents snapshot for a tapped song result. */
    suspend fun recentPickFor(song: SongEntity): RecentPick = RecentPick(
        kind = RecentPickKind.SONG,
        id = song.id,
        providerId = song.providerId,
        externalId = song.externalId,
        title = song.title,
        subtitle = song.displayArtist,
        imagePath = song.imagePath ?: albumArtFor(song.albumId),
        durationMs = song.durationMs,
    )

    suspend fun recentPickFor(album: AlbumEntity): RecentPick = RecentPick(
        kind = RecentPickKind.ALBUM,
        id = album.id,
        providerId = album.providerId,
        title = album.title,
        subtitle = album.displayArtist,
        imagePath = album.imagePath,
        durationMs = album.durationMs,
    )

    suspend fun recentPickFor(artist: ArtistEntity): RecentPick = RecentPick(
        kind = RecentPickKind.ARTIST,
        id = artist.id,
        providerId = artist.providerId,
        title = artist.title,
        imagePath = artist.imagePath,
    )

    suspend fun recentPickFor(genre: GenreEntity): RecentPick = RecentPick(
        kind = RecentPickKind.GENRE,
        id = genre.id,
        providerId = genre.providerId,
        title = genre.title,
    )

    suspend fun recentPickFor(playlist: PlaylistEntity): RecentPick = RecentPick(
        kind = RecentPickKind.PLAYLIST,
        id = playlist.id,
        providerId = playlist.providerId,
        title = playlist.title,
        imagePath = playlist.imagePath,
        durationMs = playlist.durationMs,
    )

    /**
     * Re-reads the pick's catalog row by id. Returns null when the row was
     * deleted; the stored snapshot is not rewritten here so a click never
     * writes to the preferences store.
     */
    suspend fun resolveRecentPick(pick: RecentPick): RecentPick? = when (pick.kind) {
        RecentPickKind.SONG -> db.songDao().selectById(pick.id)?.let { recentPickFor(it) }
        RecentPickKind.ALBUM -> db.albumDao().selectById(pick.id)?.let { recentPickFor(it) }
        RecentPickKind.ARTIST -> db.artistDao().selectById(pick.id)?.let { recentPickFor(it) }
        RecentPickKind.GENRE -> db.genreDao().selectById(pick.id)?.let { recentPickFor(it) }
        RecentPickKind.PLAYLIST -> db.playlistDao().selectById(pick.id)?.let { recentPickFor(it) }
    }

    private suspend fun albumArtFor(albumId: Long?): String? =
        albumId?.let { db.albumDao().selectById(it)?.imagePath }

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
