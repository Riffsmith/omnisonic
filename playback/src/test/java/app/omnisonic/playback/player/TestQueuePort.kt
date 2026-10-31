package app.omnisonic.playback.player

import app.omnisonic.playback.api.queue.QueueCommandPort
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TestQueuePort(startState: QueueState = QueueState()) : QueueCommandPort {
    val state = MutableStateFlow(startState)
    override val queueState: StateFlow<QueueState> = state
    val skipNextCalls = mutableListOf<Boolean>()
    val skipPreviousCalls = mutableListOf<Unit>()
    val jumpCalls = mutableListOf<Int>()
    val repeatCalls = mutableListOf<RepeatMode>()
    val shuffleCalls = mutableListOf<Boolean>()

    override fun skipNext() {
        skipNextCalls += true
    }

    override fun skipPrevious() {
        skipPreviousCalls += Unit
    }

    override fun jumpToOrderIndex(orderIndex: Int) {
        jumpCalls += orderIndex
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeatCalls += mode
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        shuffleCalls += enabled
    }
}
