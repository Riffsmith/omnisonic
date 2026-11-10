package app.omnisonic.feature.library.detail

import app.omnisonic.feature.library.browse.BrowseUiState
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.SongEntity

/**
 * Detail screen state (UI_SPEC.md §3.5): the header plus the paged body.
 * [Loading] before the header resolves, [Missing] when the header row is
 * gone (deleted on the server — friendly empty + back), [Error] when the
 * header load failed (`load` re-enters). The body reuses the §1.4 browse
 * triplet including the load-more surface.
 */
sealed interface DetailUiState {

    data object Loading : DetailUiState

    data object Missing : DetailUiState

    data object Error : DetailUiState

    data class Album(
        val header: AlbumEntity,
        val tracks: BrowseUiState<SongEntity>,
    ) : DetailUiState

    data class Artist(
        val header: ArtistEntity,
        val tracks: BrowseUiState<SongEntity>,
        val albums: BrowseUiState<AlbumEntity>,
    ) : DetailUiState

    data class Genre(
        val header: GenreEntity,
        val tracks: BrowseUiState<SongEntity>,
    ) : DetailUiState

    data class Playlist(
        val header: PlaylistEntity,
        val entries: BrowseUiState<PlaylistEntryEntity>,
    ) : DetailUiState
}
