package app.omnisonic.shell

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.db.RecentPlayRow
import app.omnisonic.feature.library.history.HistoryControls
import app.omnisonic.feature.library.history.HistorySource
import app.omnisonic.feature.library.history.HistoryViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** Records every history-control call so render tests can assert the wiring. */
internal class RecordingHistoryControls : HistoryControls {
    val deletedEventIds = mutableListOf<Long>()
    val playCounts = mutableListOf<Triple<Long, String, Int>>()
    val wipedProviders = mutableListOf<Long>()
    private val gate = MutableStateFlow<Set<Long>>(emptySet())

    override val pausedProviders: StateFlow<Set<Long>>
        get() = gate

    override suspend fun deletePlay(eventId: Long): Boolean {
        deletedEventIds.add(eventId)
        return true
    }

    override suspend fun setPlayCount(providerId: Long, externalId: String, count: Int) {
        playCounts.add(Triple(providerId, externalId, count))
    }

    override suspend fun wipeProvider(providerId: Long) {
        wipedProviders.add(providerId)
    }

    override fun setRecordingPaused(providerId: Long, paused: Boolean) {
        gate.value = if (paused) gate.value + providerId else gate.value - providerId
    }
}

internal fun testHistoryViewModel(
    source: HistorySource = EmptyHistorySource(),
    controls: HistoryControls = RecordingHistoryControls(),
) = HistoryViewModel(source, controls)
