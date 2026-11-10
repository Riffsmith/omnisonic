package app.omnisonic.feature.library.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.browse.BrowseRepository
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.feature.library.browse.BrowseUiState
import app.omnisonic.feature.library.browse.SectionPager
import app.omnisonic.playback.api.controller.PlaybackController
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One detail destination's state + playback actions (UI_SPEC.md §3.5).
 * Instances are back-stack-entry scoped and serve exactly one (kind, id)
 * pair: `load` is idempotent for the same pair (recomposition, config
 * changes), re-enters only from [DetailUiState.Error], and a different pair
 * resets the body.
 *
 * The header loads first; each body list is a [SectionPager] (tracks for
 * album/genre, tracks + albums for artist, position-ordered entries for
 * playlist) refreshed live by the catalog-change feed like the library
 * sections (task 4b). Play actions queue the rows loaded so far through the
 * [PlaybackController] — queueing beyond the loaded window is a
 * later-phase item.
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val source: DetailSource,
    private val controller: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var kind: DetailKind? = null
    private var detailId: Long = 0
    private var bodyJobs: List<Job> = emptyList()

    private var tracksPager: SectionPager<SongEntity>? = null
    private var albumsPager: SectionPager<AlbumEntity>? = null
    private var entriesPager: SectionPager<PlaylistEntryEntity>? = null

    fun load(kind: DetailKind, id: Long) {
        val same = this.kind == kind && this.detailId == id
        if (same && _state.value !is DetailUiState.Error) return
        if (!same) {
            bodyJobs.forEach { it.cancel() }
            bodyJobs = emptyList()
            tracksPager = null
            albumsPager = null
            entriesPager = null
            this.kind = kind
            this.detailId = id
        }
        _state.value = DetailUiState.Loading
        bodyJobs += viewModelScope.launch {
            val header = runCatching { headerOf(kind, id) }.getOrElse {
                _state.value = DetailUiState.Error
                return@launch
            }
            if (header == null) {
                _state.value = DetailUiState.Missing
                return@launch
            }
            startBody(kind, id, header)
            watchHeader(kind, id)
        }
    }

    fun loadMoreTracks() {
        tracksPager?.loadMore()
    }

    fun loadMoreAlbums() {
        albumsPager?.loadMore()
    }

    fun loadMoreEntries() {
        entriesPager?.loadMore()
    }

    /** Retry for a failed body window (the header already resolved). */
    fun retryTracks() {
        tracksPager?.loadInitial()
    }

    fun retryAlbums() {
        albumsPager?.loadInitial()
    }

    fun retryEntries() {
        entriesPager?.loadInitial()
    }

    fun playAll() = playLoaded(startEntriesIndex = 0, shuffle = false)

    /** Plays from the tapped row, keeping the rest of the loaded list after it. */
    fun playFrom(rowIndex: Int) {
        val externalId = currentRowExternalId(rowIndex) ?: return
        val entries = playableEntries() ?: return
        val start = entries.indexOfFirst { it.externalId == externalId }.takeIf { it >= 0 } ?: return
        playLoaded(entries, start, shuffle = false)
    }

    fun shuffleAll() = playLoaded(startEntriesIndex = 0, shuffle = true)

    private fun playLoaded(startEntriesIndex: Int, shuffle: Boolean) {
        val entries = playableEntries()?.takeIf { it.isNotEmpty() } ?: return
        playLoaded(entries, startEntriesIndex, shuffle)
    }

    private fun playLoaded(
        entries: List<PlaybackQueueEntry>,
        startEntriesIndex: Int,
        shuffle: Boolean,
    ) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            controller.play(entries, startEntriesIndex.coerceIn(entries.indices))
            if (shuffle) controller.setShuffleEnabled(true)
        }
    }

    private fun playableEntries(): List<PlaybackQueueEntry>? = when (val current = _state.value) {
        is DetailUiState.Album -> (current.tracks as? BrowseUiState.Content)?.items?.mapNotNull { it.toQueueEntry() }
        is DetailUiState.Artist -> (current.tracks as? BrowseUiState.Content)?.items?.mapNotNull { it.toQueueEntry() }
        is DetailUiState.Genre -> (current.tracks as? BrowseUiState.Content)?.items?.mapNotNull { it.toQueueEntry() }
        is DetailUiState.Playlist -> (current.entries as? BrowseUiState.Content)?.items?.mapNotNull { it.toQueueEntry() }
        else -> null
    }

    private fun currentRowExternalId(rowIndex: Int): String? = when (val current = _state.value) {
        is DetailUiState.Album -> (current.tracks as? BrowseUiState.Content)?.items?.getOrNull(rowIndex)?.externalId
        is DetailUiState.Artist -> (current.tracks as? BrowseUiState.Content)?.items?.getOrNull(rowIndex)?.externalId
        is DetailUiState.Genre -> (current.tracks as? BrowseUiState.Content)?.items?.getOrNull(rowIndex)?.externalId
        is DetailUiState.Playlist -> (current.entries as? BrowseUiState.Content)?.items?.getOrNull(rowIndex)?.externalId
        else -> null
    }

    private suspend fun headerOf(kind: DetailKind, id: Long) = when (kind) {
        DetailKind.ALBUM -> source.album(id)
        DetailKind.ARTIST -> source.artist(id)
        DetailKind.GENRE -> source.genre(id)
        DetailKind.PLAYLIST -> source.playlist(id)
    }

    private suspend fun startBody(kind: DetailKind, id: Long, header: Any) {
        when (kind) {
            DetailKind.ALBUM -> startAlbumBody(id, header as AlbumEntity)
            DetailKind.ARTIST -> startArtistBody(id, header as ArtistEntity)
            DetailKind.GENRE -> startGenreBody(id, header as GenreEntity)
            DetailKind.PLAYLIST -> startPlaylistBody(id, header as PlaylistEntity)
        }
    }

    private fun startAlbumBody(id: Long, header: AlbumEntity) {
        _state.value = DetailUiState.Album(header, BrowseUiState.Loading)
        val pager = createTracksPager { offset, limit -> source.albumTracks(id, offset, limit) }
        observeTracks { tracks -> (_state.value as? DetailUiState.Album)?.copy(tracks = tracks) }
        pager.loadInitial()
    }

    private fun startArtistBody(id: Long, header: ArtistEntity) {
        _state.value = DetailUiState.Artist(header, BrowseUiState.Loading, BrowseUiState.Loading)
        val tracks = createTracksPager { offset, limit -> source.artistTracks(id, offset, limit) }
        val albums = SectionPager(
            viewModelScope,
            BrowseRepository.DEFAULT_PAGE_LIMIT,
            { offset, limit -> source.artistAlbums(id, offset, limit) },
            { header.albumCount },
            source.libraryChanges,
        )
        albumsPager = albums
        observeTracks { tracksState ->
            (_state.value as? DetailUiState.Artist)?.copy(tracks = tracksState)
        }
        bodyJobs += viewModelScope.launch {
            albums.state.collect { albumState ->
                (_state.value as? DetailUiState.Artist)?.let { current ->
                    _state.value = current.copy(albums = albumState)
                }
            }
        }
        tracks.loadInitial()
        albums.loadInitial()
    }

    private fun startGenreBody(id: Long, header: GenreEntity) {
        _state.value = DetailUiState.Genre(header, BrowseUiState.Loading)
        val pager = createTracksPager { offset, limit -> source.genreTracks(id, offset, limit) }
        observeTracks { tracks -> (_state.value as? DetailUiState.Genre)?.copy(tracks = tracks) }
        pager.loadInitial()
    }

    private fun startPlaylistBody(id: Long, header: PlaylistEntity) {
        _state.value = DetailUiState.Playlist(header, BrowseUiState.Loading)
        val pager = SectionPager(
            viewModelScope,
            BrowseRepository.DEFAULT_PAGE_LIMIT,
            { offset, limit -> source.playlistEntries(id, offset, limit) },
            { header.entryCount },
            source.libraryChanges,
        )
        entriesPager = pager
        bodyJobs += viewModelScope.launch {
            pager.state.collect { entries ->
                (_state.value as? DetailUiState.Playlist)?.let { current ->
                    _state.value = current.copy(entries = entries)
                }
            }
        }
        pager.loadInitial()
    }

    private fun createTracksPager(
        loadPage: suspend (offset: Int, limit: Int) -> BrowsePage<SongEntity>,
    ): SectionPager<SongEntity> {
        val pager = SectionPager(
            viewModelScope,
            BrowseRepository.DEFAULT_PAGE_LIMIT,
            loadPage,
            { trackedCount },
            source.libraryChanges,
        )
        tracksPager = pager
        return pager
    }

    private fun observeTracks(merge: (BrowseUiState<SongEntity>) -> DetailUiState?) {
        bodyJobs += viewModelScope.launch {
            tracksPager?.state?.collect { tracks ->
                merge(tracks)?.let { _state.value = it }
            }
        }
    }

    /**
     * The body pager's display count rides the header's synced totals (song /
     * album / entry counts), which equal the scoped count under the Phase 5
     * unrestricted scope; Phase 10 folds visibility semantics in.
     */
    private val trackedCount: Int
        get() = when (val current = _state.value) {
            is DetailUiState.Album -> current.header.songCount
            is DetailUiState.Artist -> current.header.songCount
            is DetailUiState.Genre -> current.header.songCount
            is DetailUiState.Playlist -> current.header.entryCount
            else -> 0
        }

    /**
     * Live header refresh (D-16): a catalog write re-reads the header row so
     * counts and favorite state follow the sync in front of the user; a row
     * deleted mid-session moves the screen to [DetailUiState.Missing].
     */
    private suspend fun watchHeader(kind: DetailKind, id: Long) {
        source.libraryChanges.collect {
            val fresh = runCatching { headerOf(kind, id) }.getOrNull()
            when (val current = _state.value) {
                is DetailUiState.Album -> _state.value =
                    (fresh as? AlbumEntity)?.let { current.copy(header = it) } ?: DetailUiState.Missing
                is DetailUiState.Artist -> _state.value =
                    (fresh as? ArtistEntity)?.let { current.copy(header = it) } ?: DetailUiState.Missing
                is DetailUiState.Genre -> _state.value =
                    (fresh as? GenreEntity)?.let { current.copy(header = it) } ?: DetailUiState.Missing
                is DetailUiState.Playlist -> _state.value =
                    (fresh as? PlaylistEntity)?.let { current.copy(header = it) } ?: DetailUiState.Missing
                else -> Unit
            }
        }
    }
}
