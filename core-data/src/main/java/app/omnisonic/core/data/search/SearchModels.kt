package app.omnisonic.core.data.search

import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity

enum class SearchCategory { SONGS, ALBUMS, ARTISTS, GENRES, PLAYLISTS }

data class SearchPage<T>(
    val items: List<T>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        fun <T> empty(offset: Int, limit: Int): SearchPage<T> =
            SearchPage(items = emptyList(), offset = offset, limit = limit, hasMore = false)
    }
}

/** Grouped search result: one hand-paged section per enabled category. */
data class SearchResults(
    val query: String,
    val terms: List<String>,
    val categories: Set<SearchCategory>,
    val songs: SearchPage<SongEntity>,
    val albums: SearchPage<AlbumEntity>,
    val artists: SearchPage<ArtistEntity>,
    val genres: SearchPage<GenreEntity>,
    val playlists: SearchPage<PlaylistEntity>,
) {
    val isEmpty: Boolean
        get() = songs.isEmpty && albums.isEmpty && artists.isEmpty && genres.isEmpty && playlists.isEmpty

    val totalItems: Int
        get() = songs.items.size + albums.items.size + artists.items.size +
            genres.items.size + playlists.items.size

    companion object {
        fun empty(query: String, categories: Set<SearchCategory>, offset: Int, limit: Int): SearchResults =
            SearchResults(
                query = query,
                terms = emptyList(),
                categories = categories,
                songs = SearchPage.empty(offset, limit),
                albums = SearchPage.empty(offset, limit),
                artists = SearchPage.empty(offset, limit),
                genres = SearchPage.empty(offset, limit),
                playlists = SearchPage.empty(offset, limit),
            )
    }
}
