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

/**
 * Recent-plays list state (newest first, paged, empty state): one [SectionPager]
 * over the [HistorySource] pages, refreshed on the play-event change feed.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    source: HistorySource,
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

    fun load() = pager.loadInitial()

    fun loadMore() = pager.loadMore()

    fun setScrolling(scrolling: Boolean) = pager.setScrolling(scrolling)
}
