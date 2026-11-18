package app.omnisonic.feature.library.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.db.RecentPlayRow
import app.omnisonic.core.data.history.PlayHistoryRepository
import app.omnisonic.feature.library.browse.BrowseUiState
import app.omnisonic.feature.library.browse.SectionPager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Recent-plays list state (newest first, paged, empty state): one [SectionPager]
 * over the [HistorySource] pages, refreshed on the play-event change feed.
 * The write side of the history controls (D-7) delegates to [HistoryControls];
 * the change feed reloads the window after every mutation.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    source: HistorySource,
    private val controls: HistoryControls,
) : ViewModel() {

    private val pager = SectionPager(
        scope = viewModelScope,
        pageSize = PlayHistoryRepository.DEFAULT_PAGE_LIMIT,
        loadPage = source::recentPlays,
        loadCount = source::recentCount,
        keyOf = { it.eventId },
        changes = source.changes,
    )

    val state: StateFlow<BrowseUiState<RecentPlayRow>> = pager.state

    val pausedProviders: StateFlow<Set<Long>> = controls.pausedProviders

    fun load() = pager.loadInitial()

    fun loadMore() = pager.loadMore()

    fun setScrolling(scrolling: Boolean) = pager.setScrolling(scrolling)

    fun removePlay(eventId: Long) {
        viewModelScope.launch { controls.deletePlay(eventId) }
    }

    fun editPlayCount(providerId: Long, externalId: String, count: Int) {
        viewModelScope.launch { controls.setPlayCount(providerId, externalId, count) }
    }

    fun wipeProvider(providerId: Long) {
        viewModelScope.launch { controls.wipeProvider(providerId) }
    }

    fun setRecordingPaused(providerId: Long, paused: Boolean) {
        controls.setRecordingPaused(providerId, paused)
    }
}
