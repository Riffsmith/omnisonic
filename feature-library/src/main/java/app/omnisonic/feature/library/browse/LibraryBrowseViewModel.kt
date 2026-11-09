package app.omnisonic.feature.library.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.browse.BrowseRepository
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Owns the paging state of every library section for the whole shell, so
 * section pages keep their loaded items across navigation. Screens observe
 * the typed flow for their section and call [load]/[loadMore] (UI_SPEC.md
 * §3.4); load-more is requested when the section's footer becomes visible.
 */
@HiltViewModel
class LibraryBrowseViewModel @Inject constructor(
    source: LibraryBrowseSource,
) : ViewModel() {

    private val albumsPager = SectionPager(viewModelScope, BrowseRepository.DEFAULT_PAGE_LIMIT, source::albums, source::albumCount)
    private val artistsPager = SectionPager(viewModelScope, BrowseRepository.DEFAULT_PAGE_LIMIT, source::artists, source::artistCount)
    private val songsPager = SectionPager(viewModelScope, BrowseRepository.DEFAULT_PAGE_LIMIT, source::songs, source::songCount)
    private val genresPager = SectionPager(viewModelScope, BrowseRepository.DEFAULT_PAGE_LIMIT, source::genres, source::genreCount)
    private val playlistsPager = SectionPager(viewModelScope, BrowseRepository.DEFAULT_PAGE_LIMIT, source::playlists, source::playlistCount)

    val albums: StateFlow<BrowseUiState<AlbumEntity>> = albumsPager.state
    val artists: StateFlow<BrowseUiState<ArtistEntity>> = artistsPager.state
    val songs: StateFlow<BrowseUiState<SongEntity>> = songsPager.state
    val genres: StateFlow<BrowseUiState<GenreEntity>> = genresPager.state
    val playlists: StateFlow<BrowseUiState<PlaylistEntity>> = playlistsPager.state

    fun load(section: LibrarySection) = pagerFor(section).loadInitial()

    fun loadMore(section: LibrarySection) = pagerFor(section).loadMore()

    private fun pagerFor(section: LibrarySection): SectionPager<*> = when (section) {
        LibrarySection.ALBUMS -> albumsPager
        LibrarySection.ARTISTS -> artistsPager
        LibrarySection.GENRES -> genresPager
        LibrarySection.SONGS -> songsPager
        LibrarySection.PLAYLISTS -> playlistsPager
    }
}
