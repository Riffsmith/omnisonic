package app.omnisonic.feature.library.detail

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.SongEntity
import kotlinx.coroutines.flow.Flow

/**
 * The detail read surface the [DetailViewModel] consumes. Decouples the
 * view model from Room (core-data keeps its database internal) so plain JVM
 * tests can fake the pages; production reads go through [DetailRepository]
 * with the default scope.
 */
interface DetailSource {

    /** Debounced catalog changes; detail bodies reload their windows on it. */
    val libraryChanges: Flow<Unit>

    suspend fun album(id: Long): AlbumEntity?

    suspend fun artist(id: Long): ArtistEntity?

    suspend fun genre(id: Long): GenreEntity?

    suspend fun playlist(id: Long): PlaylistEntity?

    suspend fun albumTracks(albumId: Long, offset: Int, limit: Int): BrowsePage<SongEntity>

    suspend fun artistTracks(artistId: Long, offset: Int, limit: Int): BrowsePage<SongEntity>

    suspend fun artistAlbums(artistId: Long, offset: Int, limit: Int): BrowsePage<AlbumEntity>

    suspend fun genreTracks(genreId: Long, offset: Int, limit: Int): BrowsePage<SongEntity>

    suspend fun playlistEntries(playlistId: Long, offset: Int, limit: Int): BrowsePage<PlaylistEntryEntity>
}
