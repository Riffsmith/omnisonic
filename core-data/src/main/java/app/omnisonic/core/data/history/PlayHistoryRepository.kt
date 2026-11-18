package app.omnisonic.core.data.history

import androidx.room.invalidationTrackerFlow
import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.browse.BrowseRepository
import app.omnisonic.core.data.browse.fetchPage
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.RecentPlayRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/**
 * Read side of listen history: recent plays from `play_events`, newest first,
 * joined to the materialised song/album rows for display. Reads are
 * scope-parameterized per the `ProfileScope` convention; the default
 * [ProfileScope.Unrestricted] passes the exclusion list through unchanged
 * until visibility semantics land (Phase 10).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlayHistoryRepository @Inject constructor(
    private val db: OmnisonicDatabase,
) {

    /**
     * Debounced `play_events` change feed (same quiet-period collapse as
     * `LibraryInvalidation`); the history screen reloads its window on it.
     */
    val changes: Flow<Unit> =
        db.invalidationTrackerFlow(PLAY_EVENTS_TABLE, emitInitialState = false)
            .transformLatest {
                delay(DEBOUNCE_MS)
                emit(Unit)
            }

    suspend fun recentPlays(
        scope: ProfileScope = ProfileScope.Unrestricted,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): BrowsePage<RecentPlayRow> = fetchPage(offset, limit) {
        db.playEventDao().recentPlays(scope, it, offset)
    }

    suspend fun recentCount(scope: ProfileScope = ProfileScope.Unrestricted): Int =
        db.playEventDao().countRecentPlays(scope)

    companion object {
        const val DEFAULT_PAGE_LIMIT = BrowseRepository.DEFAULT_PAGE_LIMIT
        private const val PLAY_EVENTS_TABLE = "play_events"
        private const val DEBOUNCE_MS = 250L
    }
}
