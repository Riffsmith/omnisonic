package app.omnisonic.feature.search.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.search.RecentPick
import app.omnisonic.core.data.search.RecentPickKind
import app.omnisonic.core.data.search.SearchCategory
import app.omnisonic.playback.api.controller.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A result kind whose page the shell must open (songs play instead). */
sealed interface SearchNavigation {
    data class Album(val id: Long) : SearchNavigation
    data class Artist(val id: Long) : SearchNavigation
    data class Genre(val id: Long) : SearchNavigation
    data class Playlist(val id: Long) : SearchNavigation
}

/**
 * Search screen state over [SearchSource] (UI_SPEC.md §3.6): typing runs a
 * debounced local search, submit runs immediately, category toggles persist and
 * re-run the active query, result taps are recorded as recents, and paging is a
 * single load-more footer over the per-category pages.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val source: SearchSource,
    private val controller: PlaybackController,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val _navigation = MutableSharedFlow<SearchNavigation>(extraBufferCapacity = 8)
    val navigation: SharedFlow<SearchNavigation> = _navigation.asSharedFlow()

    private var job: Job? = null
    private var categoriesInitialised = false

    init {
        viewModelScope.launch {
            source.preferences.collect { preferences ->
                val previousCategories = _state.value.enabledCategories
                _state.update { preferences.toUi(it) }
                if (categoriesInitialised && preferences.enabledCategories != previousCategories) {
                    rerunIfActive()
                }
                categoriesInitialised = true
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        job?.cancel()
        job = null
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _state.update {
                it.copy(
                    submittedQuery = "",
                    phase = SearchPhase.IDLE,
                    results = null,
                    hasMore = false,
                    appending = false,
                    appendFailed = false,
                    refreshing = false,
                )
            }
            return
        }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            executeSearch(trimmed)
        }
    }

    fun submit() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) {
            clearQuery()
            return
        }
        runSearchImmediately(query)
    }

    fun retry() {
        val query = _state.value.submittedQuery.ifBlank { _state.value.query.trim() }
        if (query.isNotBlank()) runSearchImmediately(query)
    }

    fun clearQuery() {
        job?.cancel()
        job = null
        _state.update {
            it.copy(
                query = "",
                submittedQuery = "",
                phase = SearchPhase.IDLE,
                results = null,
                hasMore = false,
                appending = false,
                appendFailed = false,
                refreshing = false,
            )
        }
    }

    fun toggleCategory(category: SearchCategory) {
        val enabled = category !in _state.value.enabledCategories
        viewModelScope.launch { source.setCategoryEnabled(category, enabled) }
    }

    fun removeRecentPick(kind: RecentPickKind, id: Long) {
        viewModelScope.launch { source.removeRecentPick(kind, id) }
    }

    fun clearRecentPicks() {
        viewModelScope.launch { source.clearRecentPicks() }
    }

    /** Tapping a recent row re-resolves it by id before acting. */
    fun selectRecentPick(pick: RecentPick) {
        viewModelScope.launch {
            val resolved = runCatching { source.resolveRecentPick(pick) }.getOrNull()
            if (resolved == null) {
                source.removeRecentPick(pick.kind, pick.id)
                return@launch
            }
            recordPick { source.recordRecentPick(resolved) }
            when (resolved.kind) {
                RecentPickKind.SONG -> playPick(resolved)
                RecentPickKind.ALBUM -> _navigation.tryEmit(SearchNavigation.Album(resolved.id))
                RecentPickKind.ARTIST -> _navigation.tryEmit(SearchNavigation.Artist(resolved.id))
                RecentPickKind.GENRE -> _navigation.tryEmit(SearchNavigation.Genre(resolved.id))
                RecentPickKind.PLAYLIST -> _navigation.tryEmit(SearchNavigation.Playlist(resolved.id))
            }
        }
    }

    /** Plays from a tapped song result, queueing the loaded song window. */
    fun playSong(songId: Long) {
        val songs = _state.value.results?.songs?.items ?: return
        val tapped = songs.firstOrNull { it.id == songId } ?: return
        recordPick { source.recordSongPick(tapped) }
        val entries = songs.mapNotNull { it.toQueueEntry() }
        val start = entries.indexOfFirst { it.songRowId == songId }
        if (start < 0 || entries.isEmpty()) return
        viewModelScope.launch { controller.play(entries, start) }
    }

    fun onAlbumTap(albumId: Long) {
        val album = _state.value.results?.albums?.items?.firstOrNull { it.id == albumId } ?: return
        recordPick { source.recordAlbumPick(album) }
        _navigation.tryEmit(SearchNavigation.Album(albumId))
    }

    fun onArtistTap(artistId: Long) {
        val artist = _state.value.results?.artists?.items?.firstOrNull { it.id == artistId } ?: return
        recordPick { source.recordArtistPick(artist) }
        _navigation.tryEmit(SearchNavigation.Artist(artistId))
    }

    fun onGenreTap(genreId: Long) {
        val genre = _state.value.results?.genres?.items?.firstOrNull { it.id == genreId } ?: return
        recordPick { source.recordGenrePick(genre) }
        _navigation.tryEmit(SearchNavigation.Genre(genreId))
    }

    fun onPlaylistTap(playlistId: Long) {
        val playlist = _state.value.results?.playlists?.items?.firstOrNull { it.id == playlistId } ?: return
        recordPick { source.recordPlaylistPick(playlist) }
        _navigation.tryEmit(SearchNavigation.Playlist(playlistId))
    }

    fun loadMore() {
        val current = _state.value
        val results = current.results ?: return
        if (!current.hasMore || current.appending || current.refreshing || job?.isActive == true) return

        val offset = results.nextOffset()
        _state.update { it.copy(appending = true, appendFailed = false) }
        job = viewModelScope.launch {
            try {
                val next = source.search(
                    query = current.submittedQuery,
                    offset = offset,
                    limit = current.pageSize,
                    categories = current.enabledCategories,
                )
                val merged = results.appendWith(next)
                _state.update {
                    it.copy(
                        results = merged,
                        hasMore = merged.anyHasMore(),
                        appending = false,
                        appendFailed = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(appending = false, appendFailed = true) }
            }
        }
    }

    private fun rerunIfActive() {
        val query = _state.value.submittedQuery
        if (query.isNotBlank()) runSearchImmediately(query)
    }

    private fun runSearchImmediately(query: String) {
        job?.cancel()
        job = viewModelScope.launch { executeSearch(query) }
    }

    /**
     * Runs one offset-0 search. Called from the job it belongs to, so it never
     * cancels; a result is applied only while the typed query still matches.
     */
    private suspend fun executeSearch(query: String) {
        val prior = _state.value
        val keepRows = prior.phase == SearchPhase.RESULTS && prior.results != null
        _state.update {
            it.copy(
                query = if (it.query.isBlank()) query else it.query,
                submittedQuery = query,
                phase = if (keepRows) SearchPhase.RESULTS else SearchPhase.SEARCHING,
                refreshing = keepRows,
                hasMore = false,
                appending = false,
                appendFailed = false,
            )
        }
        try {
            val results = source.search(
                query = query,
                offset = 0,
                limit = _state.value.pageSize,
                categories = _state.value.enabledCategories,
            )
            if (_state.value.query.trim() != query) return
            _state.update {
                it.copy(
                    phase = if (results.isEmpty) SearchPhase.NO_RESULTS else SearchPhase.RESULTS,
                    results = results,
                    hasMore = results.anyHasMore(),
                    refreshing = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (_state.value.query.trim() != query) return
            _state.update {
                it.copy(
                    phase = SearchPhase.ERROR,
                    results = null,
                    hasMore = false,
                    refreshing = false,
                )
            }
        }
    }

    private fun playPick(pick: RecentPick) {
        val entry = pick.toQueueEntry() ?: return
        viewModelScope.launch { controller.play(listOf(entry), 0) }
    }

    private fun recordPick(record: suspend () -> Unit) {
        viewModelScope.launch { runCatching { record() } }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
