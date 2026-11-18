package app.omnisonic.feature.library.history

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.db.RecentPlayRow
import app.omnisonic.core.data.history.PlayHistoryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Production [HistorySource] over the default (unrestricted) scope. */
@Singleton
class RepositoryHistorySource @Inject constructor(
    private val repository: PlayHistoryRepository,
) : HistorySource {

    override val changes: Flow<Unit>
        get() = repository.changes

    override suspend fun recentPlays(offset: Int, limit: Int): BrowsePage<RecentPlayRow> =
        repository.recentPlays(offset = offset, limit = limit)

    override suspend fun recentCount(): Int = repository.recentCount()
}
