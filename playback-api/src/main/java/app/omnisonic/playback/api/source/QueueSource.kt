package app.omnisonic.playback.api.source

import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState

/** Fill-in seam for radio mode, autoplay and "play from search". Defaults are no-ops. */
interface QueueSource {
    /** Called while the queue tail approaches; appends more entries in queue order. */
    suspend fun fillQueueTail(state: QueueState, desiredCount: Int): List<PlaybackQueueEntry> = emptyList()

    suspend fun playFromSearch(query: String): List<PlaybackQueueEntry> = emptyList()
}

open class QueueSources(private val sourcesInPriorityOrder: List<QueueSource>) {
    suspend fun fillQueueTail(state: QueueState, desiredCount: Int): List<PlaybackQueueEntry> =
        sourcesInPriorityOrder.firstNotNullOfOrNull { source ->
            source.fillQueueTail(state, desiredCount).takeIf { it.isNotEmpty() }
        } ?: emptyList()

    suspend fun fillFromSearch(query: String): List<PlaybackQueueEntry> =
        sourcesInPriorityOrder.firstNotNullOfOrNull { it.playFromSearch(query).takeIf { entries -> entries.isNotEmpty() } }
            ?: emptyList()
}

class NoOpQueueSources : QueueSources(emptyList())
