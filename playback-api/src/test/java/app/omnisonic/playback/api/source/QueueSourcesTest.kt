package app.omnisonic.playback.api.source

import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class FixedSource(
    private val tail: List<PlaybackQueueEntry> = emptyList(),
    private val search: List<PlaybackQueueEntry> = emptyList(),
) : QueueSource {
    override suspend fun fillQueueTail(state: QueueState, desiredCount: Int): List<PlaybackQueueEntry> = tail
    override suspend fun playFromSearch(query: String): List<PlaybackQueueEntry> = search
}

private fun entry(id: Long) = PlaybackQueueEntry(providerId = 1, externalId = "ext-$id", title = "T$id")

class QueueSourcesTest {

    @Test
    fun `first non-empty source wins in priority order`() = runTest {
        val tail = listOf(entry(1))
        val search = listOf(entry(2))
        val sources = QueueSources(listOf(FixedSource(), FixedSource(tail = tail, search = search)))

        assertThat(sources.fillQueueTail(QueueState(), desiredCount = 5)).containsExactlyElementsIn(tail)
        assertThat(sources.fillFromSearch("q")).containsExactlyElementsIn(search)
    }

    @Test
    fun `an empty result falls through to the next source`() = runTest {
        val tail = listOf(entry(1))
        val search = listOf(entry(2))
        val sources = QueueSources(listOf(FixedSource(tail = tail), FixedSource(search = search)))

        assertThat(sources.fillQueueTail(QueueState(), desiredCount = 5)).containsExactlyElementsIn(tail)
        assertThat(sources.fillFromSearch("q")).containsExactlyElementsIn(search)
    }

    @Test
    fun `all empty sources and the no-op default yield empty`() = runTest {
        assertThat(QueueSources(listOf(FixedSource(), FixedSource())).fillQueueTail(QueueState(), 5)).isEmpty()
        assertThat(QueueSources(listOf(FixedSource())).fillFromSearch("q")).isEmpty()
        assertThat(NoOpQueueSources().fillQueueTail(QueueState(), 5)).isEmpty()
        assertThat(NoOpQueueSources().fillFromSearch("q")).isEmpty()
        assertThat(QueueSources(emptyList()).fillQueueTail(QueueState(), 5)).isEmpty()
    }
}
