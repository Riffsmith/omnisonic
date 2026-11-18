package app.omnisonic.shell

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.db.RecentPlayRow
import app.omnisonic.feature.library.history.HistorySource
import app.omnisonic.feature.library.history.HistoryViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Shell tests only need the history view model because the nav host holds one;
 * page behavior is exercised in `:core-data` and `:feature-library`. The
 * empty source keeps the history destination on its empty state.
 */
internal class EmptyHistorySource : HistorySource {
    override val changes: Flow<Unit> = emptyFlow()
    override suspend fun recentPlays(offset: Int, limit: Int) =
        BrowsePage<RecentPlayRow>(emptyList(), offset, limit, false)
    override suspend fun recentCount() = 0
}

/** Fixed rows for render tests: the first page carries everything. */
internal class StaticHistorySource(private val rows: List<RecentPlayRow>) : HistorySource {
    override val changes: Flow<Unit> = emptyFlow()
    override suspend fun recentPlays(offset: Int, limit: Int) =
        if (offset == 0) {
            BrowsePage(rows.take(limit), offset, limit, rows.size > limit)
        } else {
            BrowsePage(emptyList(), offset, limit, false)
        }
    override suspend fun recentCount() = rows.size
}

internal fun testHistoryViewModel(source: HistorySource = EmptyHistorySource()) =
    HistoryViewModel(source)
