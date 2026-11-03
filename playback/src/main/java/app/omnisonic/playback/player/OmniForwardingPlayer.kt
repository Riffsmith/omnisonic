package app.omnisonic.playback.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import app.omnisonic.playback.api.queue.QueueCommandPort
import app.omnisonic.playback.api.queue.QueueState
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable exposed player: one instance for the lifetime of an
 * [app.omnisonic.playback.api.player.OmniPlayer], wrapping the stable
 * [QueueAwareBridge] so session/UI listeners survive engine swaps. Queue-aware
 * commands route to the [QueueCommandPort]; next/previous availability and
 * indices derive from the queue state through [QueueCommandPolicy], and
 * repeat/shuffle state is queue-owned. Media-list mutation and engine
 * repeat/shuffle setters are never forwarded to the engine.
 */
@UnstableApi
internal class OmniForwardingPlayer(
    private val queueState: StateFlow<QueueState>,
    private val port: QueueCommandPort,
    bridge: QueueAwareBridge,
    private val releaseAction: () -> Unit,
) : ForwardingPlayer(bridge) {

    override fun seekToNext() {
        port.skipNext()
    }

    override fun seekToNextMediaItem() {
        port.skipNext()
    }

    override fun seekToPrevious() {
        port.skipPrevious()
    }

    override fun seekToPreviousMediaItem() {
        port.skipPrevious()
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val state = queueState.value
        val orderPosition = orderPositionOf(state, mediaItemIndex)
        if (orderPosition == null || orderPosition == state.playIndex) {
            super.seekTo(mediaItemIndex, positionMs)
        } else {
            port.jumpToOrderIndex(orderPosition)
        }
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        seekTo(mediaItemIndex, C.TIME_UNSET)
    }

    override fun setRepeatMode(repeatMode: Int) {
        port.setRepeatMode(QueueCommandPolicy.fromPlayerRepeatMode(repeatMode))
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        port.setShuffleEnabled(shuffleModeEnabled)
    }

    override fun hasNextMediaItem(): Boolean = QueueCommandPolicy.canSkipNext(queueState.value)

    override fun getNextWindowIndex(): Int =
        QueueCommandPolicy.nextOrderIndex(queueState.value) ?: C.INDEX_UNSET

    override fun getNextMediaItemIndex(): Int = getNextWindowIndex()

    override fun hasPreviousMediaItem(): Boolean = QueueCommandPolicy.canSkipPrevious(queueState.value)

    override fun getPreviousWindowIndex(): Int =
        QueueCommandPolicy.previousOrderIndex(queueState.value) ?: C.INDEX_UNSET

    override fun getPreviousMediaItemIndex(): Int = getPreviousWindowIndex()

    override fun release() {
        releaseAction()
    }

    private fun orderPositionOf(state: QueueState, orderIndex: Int): Int? {
        if (orderIndex !in state.effectiveOrder().indices) return null
        val entriesIndex = state.effectiveOrder()[orderIndex]
        return orderIndex.takeIf { state.entries.getOrNull(entriesIndex) != null }
    }
}
