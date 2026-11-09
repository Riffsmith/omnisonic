package app.omnisonic.feature.library.browse

import app.omnisonic.core.data.browse.BrowseRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Production [LibraryBrowseSource] over the default (unrestricted) scope. */
@Singleton
class RepositoryBrowseSource @Inject constructor(
    private val repository: BrowseRepository,
) : LibraryBrowseSource {

    override suspend fun albums(offset: Int, limit: Int) =
        repository.albums(offset = offset, limit = limit)

    override suspend fun artists(offset: Int, limit: Int) =
        repository.artists(offset = offset, limit = limit)

    override suspend fun songs(offset: Int, limit: Int) =
        repository.songs(offset = offset, limit = limit)

    override suspend fun genres(offset: Int, limit: Int) =
        repository.genres(offset = offset, limit = limit)

    override suspend fun playlists(offset: Int, limit: Int) =
        repository.playlists(offset = offset, limit = limit)

    override suspend fun albumCount(): Int = repository.albumCount()

    override suspend fun artistCount(): Int = repository.artistCount()

    override suspend fun songCount(): Int = repository.songCount()

    override suspend fun genreCount(): Int = repository.genreCount()

    override suspend fun playlistCount(): Int = repository.playlistCount()
}
