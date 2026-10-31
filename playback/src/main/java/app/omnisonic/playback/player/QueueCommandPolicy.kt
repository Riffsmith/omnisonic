package app.omnisonic.playback.player

import androidx.media3.common.Player
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode

/**
 * Queue-derived next/previous policy for the exposed player, mirroring the
 * queue machine's skip semantics: repeat OFF is bounded by the queue, ALL and
 * ONE wrap, and a previous command always exists while an entry is current
 * because the queue machine restarts a deep position instead of walking back.
 */
internal object QueueCommandPolicy {

    fun nextOrderIndex(state: QueueState): Int? {
        if (state.currentEntry == null) return null
        val size = state.effectiveOrder().size
        if (size == 0) return null
        return when (state.repeatMode) {
            RepeatMode.OFF -> (state.playIndex + 1).takeIf { it < size }
            else -> (state.playIndex + 1) % size
        }
    }

    fun previousOrderIndex(state: QueueState): Int? {
        if (state.currentEntry == null) return null
        val size = state.effectiveOrder().size
        if (size == 0) return null
        val previous = state.playIndex - 1
        return when {
            previous >= 0 -> previous
            state.repeatMode == RepeatMode.ALL -> size - 1
            else -> null
        }
    }

    fun canSkipNext(state: QueueState): Boolean = nextOrderIndex(state) != null

    fun canSkipPrevious(state: QueueState): Boolean = state.currentEntry != null

    fun toPlayerRepeatMode(mode: RepeatMode): Int = when (mode) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    }

    fun fromPlayerRepeatMode(mode: Int): RepeatMode = when (mode) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }
}
