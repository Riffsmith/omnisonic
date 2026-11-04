package app.omnisonic.playback.events

import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.events.PlaybackEventBus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fan-out bus for playback events. Replay is 0 (events describe what just
 * happened, not state), the buffer absorbs a slow collector with a drop-oldest
 * policy so producers never suspend, and each collector sees the events in
 * emission order.
 */
class PlaybackEventBusImpl(
    bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
) : PlaybackEventBus {

    private val mutable = MutableSharedFlow<PlaybackEvent>(
        replay = 0,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<PlaybackEvent> = mutable.asSharedFlow()

    override fun emit(event: PlaybackEvent) {
        mutable.tryEmit(event)
    }

    companion object {
        const val DEFAULT_BUFFER_CAPACITY = 64
    }
}
