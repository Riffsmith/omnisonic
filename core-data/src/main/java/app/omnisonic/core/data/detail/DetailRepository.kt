package app.omnisonic.core.data.detail

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.browse.BrowseRepository
import app.omnisonic.core.data.browse.fetchPage
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.sync.LibraryInvalidation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Detail-screen reads (UI_SPEC.md §3.5): one header lookup by id plus the
 * scoped, paged body reads — album tracks, artist albums/songs over the
 * link tables, genre tracks over the JSON genre column, playlist entries in
 * `position` order. Same LIMIT/OFFSET + one-past-the-window probe paging as
 * [BrowseRepository]. Header lookups are plain identity reads (a missing row
 * means the item was deleted on the server — the screen shows its empty
 * state); the body lists carry the provider exclusion like every catalog
 * read (task-3 contract).
 */
@Singleton
class DetailRepository @Inject constructor(
    private val db: OmnisonicDatabase,
    private val invalidations: LibraryInvalidation,
) {

    /** Debounced catalog-change feed; detail screens reload their bodies on it. */
    val libraryChanges: Flow<Unit>
        get() = invalidations.changes

    suspend fun album(id: Long): AlbumEntity? = db.albumDao().selectById(id)

    suspend fun artist(id: Long): ArtistEntity? = db.artistDao().selectById(id)

    suspend fun genre(id: Long): GenreEntity? = db.genreDao().selectById(id)

    suspend fun playlist(id: Long): PlaylistEntity? = db.playlistDao().selectById(id)

    suspend fun albumTracks(
        scope: ProfileScope = ProfileScope.Unrestricted,
        albumId: Long,
        offset: Int = 0,
        limit: Int = BrowseRepository.DEFAULT_PAGE_LIMIT,
    ): BrowsePage<SongEntity> = fetchPage(offset, limit) { fetchLimit ->
        db.songDao().albumTracks(scope, albumId, fetchLimit, offset)
    }

    suspend fun artistTracks(
        scope: ProfileScope = ProfileScope.Unrestricted,
        artistId: Long,
        offset: Int = 0,
        limit: Int = BrowseRepository.DEFAULT_PAGE_LIMIT,
    ): BrowsePage<SongEntity> = fetchPage(offset, limit) { fetchLimit ->
        db.songDao().artistTracks(scope, artistId, fetchLimit, offset)
    }

    suspend fun artistAlbums(
        scope: ProfileScope = ProfileScope.Unrestricted,
        artistId: Long,
        offset: Int = 0,
        limit: Int = BrowseRepository.DEFAULT_PAGE_LIMIT,
    ): BrowsePage<AlbumEntity> = fetchPage(offset, limit) { fetchLimit ->
        db.albumDao().artistAlbums(scope, artistId, fetchLimit, offset)
    }

    suspend fun genreTracks(
        scope: ProfileScope = ProfileScope.Unrestricted,
        genreId: Long,
        offset: Int = 0,
        limit: Int = BrowseRepository.DEFAULT_PAGE_LIMIT,
    ): BrowsePage<SongEntity> {
        val title = db.genreDao().selectById(genreId)?.title
            ?: return BrowsePage.empty(offset, limit)
        return fetchPage(offset, limit) { fetchLimit ->
            db.songDao().genreTracks(scope, title, fetchLimit, offset)
        }
    }

    suspend fun playlistEntries(
        scope: ProfileScope = ProfileScope.Unrestricted,
        playlistId: Long,
        offset: Int = 0,
        limit: Int = BrowseRepository.DEFAULT_PAGE_LIMIT,
    ): BrowsePage<PlaylistEntryEntity> = fetchPage(offset, limit) { fetchLimit ->
        db.playlistEntryDao().detailEntries(scope, playlistId, fetchLimit, offset)
    }
}
