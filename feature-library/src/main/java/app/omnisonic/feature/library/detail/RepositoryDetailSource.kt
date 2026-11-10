package app.omnisonic.feature.library.detail

import app.omnisonic.core.data.detail.DetailRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Production [DetailSource] over [DetailRepository] (default unrestricted scope). */
@Singleton
class RepositoryDetailSource @Inject constructor(
    private val repository: DetailRepository,
) : DetailSource {

    override val libraryChanges: Flow<Unit>
        get() = repository.libraryChanges

    override suspend fun album(id: Long) = repository.album(id)

    override suspend fun artist(id: Long) = repository.artist(id)

    override suspend fun genre(id: Long) = repository.genre(id)

    override suspend fun playlist(id: Long) = repository.playlist(id)

    override suspend fun albumTracks(albumId: Long, offset: Int, limit: Int) =
        repository.albumTracks(albumId = albumId, offset = offset, limit = limit)

    override suspend fun artistTracks(artistId: Long, offset: Int, limit: Int) =
        repository.artistTracks(artistId = artistId, offset = offset, limit = limit)

    override suspend fun artistAlbums(artistId: Long, offset: Int, limit: Int) =
        repository.artistAlbums(artistId = artistId, offset = offset, limit = limit)

    override suspend fun genreTracks(genreId: Long, offset: Int, limit: Int) =
        repository.genreTracks(genreId = genreId, offset = offset, limit = limit)

    override suspend fun playlistEntries(playlistId: Long, offset: Int, limit: Int) =
        repository.playlistEntries(playlistId = playlistId, offset = offset, limit = limit)
}
