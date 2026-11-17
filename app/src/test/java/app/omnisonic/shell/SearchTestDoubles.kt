package app.omnisonic.shell

import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.search.RecentPick
import app.omnisonic.core.data.search.RecentPickKind
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
            recentPicks = emptyList(),
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

    override suspend fun recordSongPick(song: SongEntity) = Unit

    override suspend fun recordAlbumPick(album: AlbumEntity) = Unit

    override suspend fun recordArtistPick(artist: ArtistEntity) = Unit

    override suspend fun recordGenrePick(genre: GenreEntity) = Unit

    override suspend fun recordPlaylistPick(playlist: PlaylistEntity) = Unit

    override suspend fun recordRecentPick(pick: RecentPick) = Unit

    override suspend fun removeRecentPick(kind: RecentPickKind, id: Long) = Unit

    override suspend fun clearRecentPicks() {
        prefs.update { it.copy(recentPicks = emptyList()) }
    }

    override suspend fun resolveRecentPick(pick: RecentPick): RecentPick? = null

    override suspend fun setCategoryEnabled(category: SearchCategory, enabled: Boolean) {
        prefs.update { current ->
            current.copy(
                enabledCategories = if (enabled) {
                    current.enabledCategories + category
                } else {
                    current.enabledCategories - category
                },
            )
        }
    }
}

internal fun testSearchViewModel(): SearchViewModel =
    SearchViewModel(EmptySearchSource(), NoopPlaybackController())
