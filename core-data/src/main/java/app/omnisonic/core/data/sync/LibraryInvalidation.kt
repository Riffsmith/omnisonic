package app.omnisonic.core.data.sync

import androidx.room.invalidationTrackerFlow
import app.omnisonic.core.data.db.OmnisonicDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/**
 * Debounced change feed over the catalog tables the library screens read
 * (task 4b / D-16). Every collected instance registers a Room invalidation
 * observer; writes during a sync arrive in bursts, so a quiet period collapses
 * them into one refresh instead of one reload per INSERT.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LibraryInvalidation @Inject constructor(
    database: OmnisonicDatabase,
) {

    val changes: Flow<Unit> =
        database.invalidationTrackerFlow(*CATALOG_TABLES, emitInitialState = false)
            .transformLatest {
                delay(DEBOUNCE_MS)
                emit(Unit)
            }

    private companion object {
        val CATALOG_TABLES = arrayOf("songs", "albums", "artists", "genres", "playlists")
        const val DEBOUNCE_MS = 250L
    }
}
