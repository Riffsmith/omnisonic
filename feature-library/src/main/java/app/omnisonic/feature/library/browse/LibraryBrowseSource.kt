package app.omnisonic.feature.library.browse

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import kotlinx.coroutines.flow.Flow

/**
 * The library browse read surface the section ViewModels consume. Decouples
 * the ViewModels from Room (core-data keeps its database internal) so plain
 * JVM tests can fake the pages.
 */
interface LibraryBrowseSource {

    /** Debounced catalog changes; section pagers reload their windows on it. */
    val libraryChanges: Flow<Unit>

    suspend fun albums(offset: Int, limit: Int): BrowsePage<AlbumEntity>

    suspend fun artists(offset: Int, limit: Int): BrowsePage<ArtistEntity>

    suspend fun songs(offset: Int, limit: Int): BrowsePage<SongEntity>

    suspend fun genres(offset: Int, limit: Int): BrowsePage<GenreEntity>

    suspend fun playlists(offset: Int, limit: Int): BrowsePage<PlaylistEntity>

    suspend fun albumCount(): Int

    suspend fun artistCount(): Int

    suspend fun songCount(): Int

    suspend fun genreCount(): Int

    suspend fun playlistCount(): Int
}
