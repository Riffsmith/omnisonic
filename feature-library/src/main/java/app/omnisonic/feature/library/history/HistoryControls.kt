package app.omnisonic.feature.library.history

import app.omnisonic.core.data.history.HistoryEditor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/**
 * Write side of the listen-history controls (D-7) as the feature layer sees
 * it: the view model drives these, core-data owns the transactions. Kept as
 * an interface so plain JVM tests can fake the controls.
 */
interface HistoryControls {

    /** Providers whose recording is currently paused (process-local until task 9). */
    val pausedProviders: StateFlow<Set<Long>>

    /** Deletes one play event; false when the event no longer exists. */
    suspend fun deletePlay(eventId: Long): Boolean

    /** Sets a song's play count to an explicit value (clamped at 0 in core-data). */
    suspend fun setPlayCount(providerId: Long, externalId: String, count: Int)

    /** Wipes one provider's history: events, play state, pending play-count rows. */
    suspend fun wipeProvider(providerId: Long)

    fun setRecordingPaused(providerId: Long, paused: Boolean)
}

/** Production [HistoryControls] straight over the core-data [HistoryEditor]. */
@Singleton
class RepositoryHistoryControls @Inject constructor(
    private val editor: HistoryEditor,
) : HistoryControls {

    override val pausedProviders: StateFlow<Set<Long>> = editor.pausedProviders

    override suspend fun deletePlay(eventId: Long): Boolean = editor.deleteEvent(eventId)

    override suspend fun setPlayCount(providerId: Long, externalId: String, count: Int) =
        editor.setPlayCount(providerId, externalId, count)

    override suspend fun wipeProvider(providerId: Long) = editor.wipeProvider(providerId)

    override fun setRecordingPaused(providerId: Long, paused: Boolean) =
        editor.setRecordingPaused(providerId, paused)
}
