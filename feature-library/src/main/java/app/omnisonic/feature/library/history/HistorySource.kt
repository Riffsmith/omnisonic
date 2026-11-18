package app.omnisonic.feature.library.history

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.db.RecentPlayRow
import kotlinx.coroutines.flow.Flow

/**
 * The history read surface the view model consumes. Decouples the view model
 * from Room (core-data keeps its database internal) so plain JVM tests can
 * fake the pages.
 */
interface HistorySource {

    /** Debounced play-event changes; the history screen reloads its window on it. */
    val changes: Flow<Unit>

    suspend fun recentPlays(offset: Int, limit: Int): BrowsePage<RecentPlayRow>

    suspend fun recentCount(): Int
}
