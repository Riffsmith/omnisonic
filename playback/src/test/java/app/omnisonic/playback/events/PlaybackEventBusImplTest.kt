package app.omnisonic.playback.events

import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEventBusImplTest {

    private fun event(id: Int) = PlaybackEvent.Started(
        PlaybackQueueEntry(providerId = 1, externalId = "e$id", queueItemId = id.toLong()),
        0,
    )

    @Test
    fun `collectors receive events in emission order`() = runTest {
        val bus = PlaybackEventBusImpl()
        val received = mutableListOf<PlaybackEvent>()
        backgroundScope.launch { bus.events.collect { received += it } }
        runCurrent()

        bus.emit(event(1))
        bus.emit(event(2))
        bus.emit(event(3))
        runCurrent()

        assertThat(received.map { (it as PlaybackEvent.Started).entry.queueItemId })
            .containsExactly(1L, 2L, 3L)
            .inOrder()
    }

    @Test
    fun `late collectors receive no replay`() = runTest {
        val bus = PlaybackEventBusImpl()
        bus.emit(event(1))
        bus.emit(event(2))

        val received = mutableListOf<PlaybackEvent>()
        backgroundScope.launch { bus.events.collect { received += it } }
        runCurrent()
        bus.emit(event(3))
        runCurrent()

        assertThat(received).hasSize(1)
    }

    @Test
    fun `emitting with no collectors never suspends or throws`() = runTest {
        val bus = PlaybackEventBusImpl()

        repeat(1_000) { bus.emit(event(it)) }

        assertThat(bus.events.replayCache).isEmpty()
    }

    @Test
    fun `a slow collector drops the oldest buffered events`() = runTest {
        val bus = PlaybackEventBusImpl(bufferCapacity = 2)
        val received = mutableListOf<PlaybackEvent>()
        val slow = async {
            bus.events.collect { event ->
                received += event
                delay(1_000)
            }
        }
        runCurrent()
        bus.emit(event(9))
        runCurrent()
        assertThat(received).hasSize(1)

        repeat(4) { bus.emit(event(it + 10)) }
        advanceTimeBy(5_000)
        runCurrent()

        assertThat(received.map { (it as PlaybackEvent.Started).entry.queueItemId })
            .containsExactly(9L, 12L, 13L)
            .inOrder()
        slow.cancel()
    }
}
