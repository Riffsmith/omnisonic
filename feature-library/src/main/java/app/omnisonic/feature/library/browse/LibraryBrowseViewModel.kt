package app.omnisonic.feature.library.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.browse.BrowseRepository
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.feature.library.playback.toQueueEntry
import app.omnisonic.playback.api.controller.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    private val controller: PlaybackController,
) : ViewModel() {

    private val albumsPager = SectionPager(
        scope = viewModelScope,
        pageSize = BrowseRepository.DEFAULT_PAGE_LIMIT,
        loadPage = source::albums,
        loadCount = source::albumCount,
        keyOf = { it.id },
        changes = source.libraryChanges,
    )
    private val artistsPager = SectionPager(
        scope = viewModelScope,
        pageSize = BrowseRepository.DEFAULT_PAGE_LIMIT,
        loadPage = source::artists,
        loadCount = source::artistCount,
        keyOf = { it.id },
        changes = source.libraryChanges,
    )
    private val songsPager = SectionPager(
        scope = viewModelScope,
        pageSize = BrowseRepository.DEFAULT_PAGE_LIMIT,
        loadPage = source::songs,
        loadCount = source::songCount,
        keyOf = { it.id },
        changes = source.libraryChanges,
    )
    private val genresPager = SectionPager(
        scope = viewModelScope,
        pageSize = BrowseRepository.DEFAULT_PAGE_LIMIT,
        loadPage = source::genres,
        loadCount = source::genreCount,
        keyOf = { it.id },
        changes = source.libraryChanges,
    )
    private val playlistsPager = SectionPager(
        scope = viewModelScope,
        pageSize = BrowseRepository.DEFAULT_PAGE_LIMIT,
        loadPage = source::playlists,
        loadCount = source::playlistCount,
        keyOf = { it.id },
        changes = source.libraryChanges,
    )

    val albums: StateFlow<BrowseUiState<AlbumEntity>> = albumsPager.state
    val artists: StateFlow<BrowseUiState<ArtistEntity>> = artistsPager.state
    val songs: StateFlow<BrowseUiState<SongEntity>> = songsPager.state
    val genres: StateFlow<BrowseUiState<GenreEntity>> = genresPager.state
    val playlists: StateFlow<BrowseUiState<PlaylistEntity>> = playlistsPager.state

    fun load(section: LibrarySection) = pagerFor(section).loadInitial()

    fun loadMore(section: LibrarySection) = pagerFor(section).loadMore()

    /**
     * Scroll gate (Fix 2): a catalog change that lands while the user scrolls
     * the section waits instead of replacing the window under their finger.
     */
    fun setScrolling(section: LibrarySection, scrolling: Boolean) =
        pagerFor(section).setScrolling(scrolling)

    /**
     * Plays from the tapped song row, mirroring the detail screens: only the
     * loaded song window is queued, rows the stream resolver cannot address
     * are skipped, and the start index re-anchors on the tapped row's
     * provider identity (never on the row position).
     */
    fun playFrom(rowIndex: Int) {
        val content = songs.value as? BrowseUiState.Content<SongEntity> ?: return
        val song = content.items.getOrNull(rowIndex) ?: return
        val entries = content.items.mapNotNull { it.toQueueEntry() }
        val start = entries.indexOfFirst { it.externalId == song.externalId }.takeIf { it >= 0 } ?: return
        viewModelScope.launch { controller.play(entries, start) }
    }

    private fun pagerFor(section: LibrarySection): SectionPager<*> = when (section) {
        LibrarySection.ALBUMS -> albumsPager
        LibrarySection.ARTISTS -> artistsPager
        LibrarySection.GENRES -> genresPager
        LibrarySection.SONGS -> songsPager
        LibrarySection.PLAYLISTS -> playlistsPager
    }
}
