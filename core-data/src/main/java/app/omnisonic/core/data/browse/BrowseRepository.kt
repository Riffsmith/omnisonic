package app.omnisonic.core.data.browse

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.sync.LibraryInvalidation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Local-library browse pages for the library sections (albums, artists,
 * songs, genres, playlists), ordered alphabetically like their search
 * counterparts. Reads are scope-parameterized per the task-3 contract; the
 * default [ProfileScope.Unrestricted] passes the exclusion list through
 * unchanged until visibility semantics land (Phase 10).
 */
@Singleton
class BrowseRepository @Inject constructor(
    private val db: OmnisonicDatabase,
    private val invalidations: LibraryInvalidation,
) {

    /** Debounced catalog-change feed; library screens reload their windows on it. */
    val libraryChanges: Flow<Unit>
        get() = invalidations.changes

    suspend fun albums(
        scope: ProfileScope = ProfileScope.Unrestricted,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): BrowsePage<AlbumEntity> = pageOf(offset, limit) {
        db.albumDao().browseAlbums(scope, it, offset)
    }

    suspend fun artists(
        scope: ProfileScope = ProfileScope.Unrestricted,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): BrowsePage<ArtistEntity> = pageOf(offset, limit) {
        db.artistDao().browseArtists(scope, it, offset)
    }

    suspend fun songs(
        scope: ProfileScope = ProfileScope.Unrestricted,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): BrowsePage<SongEntity> = pageOf(offset, limit) {
        db.songDao().browseSongs(scope, it, offset)
    }

    suspend fun genres(
        scope: ProfileScope = ProfileScope.Unrestricted,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): BrowsePage<GenreEntity> = pageOf(offset, limit) {
        db.genreDao().browseGenres(scope, it, offset)
    }

    suspend fun playlists(
        scope: ProfileScope = ProfileScope.Unrestricted,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): BrowsePage<PlaylistEntity> = pageOf(offset, limit) {
        db.playlistDao().browsePlaylists(scope, it, offset)
    }

    suspend fun albumCount(scope: ProfileScope = ProfileScope.Unrestricted): Int =
        db.albumDao().countAlbums(scope)

    suspend fun artistCount(scope: ProfileScope = ProfileScope.Unrestricted): Int =
        db.artistDao().countArtists(scope)

    suspend fun songCount(scope: ProfileScope = ProfileScope.Unrestricted): Int =
        db.songDao().countSongs(scope)

    suspend fun genreCount(scope: ProfileScope = ProfileScope.Unrestricted): Int =
        db.genreDao().countGenres(scope)

    suspend fun playlistCount(scope: ProfileScope = ProfileScope.Unrestricted): Int =
        db.playlistDao().countPlaylists(scope)

    private suspend fun <T> pageOf(
        offset: Int,
        limit: Int,
        rows: suspend (fetchLimit: Int) -> List<T>,
    ): BrowsePage<T> = fetchPage(offset, limit, rows)

    companion object {
        const val DEFAULT_PAGE_LIMIT = 60

        fun sanitizeLimit(limit: Int): Int = limit.coerceIn(MIN_PAGE_LIMIT, MAX_PAGE_LIMIT)

        const val MIN_PAGE_LIMIT = 1
        const val MAX_PAGE_LIMIT = 200
    }
}
