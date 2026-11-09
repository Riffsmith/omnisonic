package app.omnisonic.shell

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.feature.library.browse.LibraryBrowseSource
import app.omnisonic.feature.library.browse.LibraryBrowseViewModel

/**
 * Shell tests only need the browse view model because the nav host holds one;
 * page behavior is exercised in `:core-data` and `:feature-library`. The
 * empty source keeps section destinations on their empty state.
 */
internal class EmptyBrowseSource : LibraryBrowseSource {
    override suspend fun albums(offset: Int, limit: Int) = BrowsePage<app.omnisonic.core.data.db.entity.AlbumEntity>(emptyList(), offset, limit, false)
    override suspend fun artists(offset: Int, limit: Int) = BrowsePage<app.omnisonic.core.data.db.entity.ArtistEntity>(emptyList(), offset, limit, false)
    override suspend fun songs(offset: Int, limit: Int) = BrowsePage<app.omnisonic.core.data.db.entity.SongEntity>(emptyList(), offset, limit, false)
    override suspend fun genres(offset: Int, limit: Int) = BrowsePage<app.omnisonic.core.data.db.entity.GenreEntity>(emptyList(), offset, limit, false)
    override suspend fun playlists(offset: Int, limit: Int) = BrowsePage<app.omnisonic.core.data.db.entity.PlaylistEntity>(emptyList(), offset, limit, false)
    override suspend fun albumCount() = 0
    override suspend fun artistCount() = 0
    override suspend fun songCount() = 0
    override suspend fun genreCount() = 0
    override suspend fun playlistCount() = 0
}

internal fun testBrowseViewModel(): LibraryBrowseViewModel = LibraryBrowseViewModel(EmptyBrowseSource())
