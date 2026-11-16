package app.omnisonic.feature.library.miniplayer

import app.omnisonic.feature.library.nowplaying.NowPlayingEntry
import app.omnisonic.feature.library.nowplaying.playbackProgress
import app.omnisonic.feature.library.nowplaying.resolveNowPlayingEntry
import app.omnisonic.playback.api.controller.PlayerCoreState
import app.omnisonic.playback.api.queue.QueueState

/**
 * Compact-bar view state (UI_SPEC.md §3.10): the playing entry, the transport
 * flag and the playback position for the bottom-edge progress hairline, mapped
 * only from `PlaybackController` flows. The bar is hidden when nothing is
 * playing, so [isVisible] doubles as the shell's "reserve the slot at all"
 * answer.
 */
data class MiniPlayerUiState(
    val entry: NowPlayingEntry? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
) {
    val isVisible: Boolean get() = entry != null

    val progress: Float get() = playbackProgress(positionMs, durationMs)

    companion object {
        fun from(player: PlayerCoreState, queue: QueueState): MiniPlayerUiState {
            val current = player.current ?: queue.currentEntry
            return MiniPlayerUiState(
                entry = resolveNowPlayingEntry(player, queue),
                isPlaying = player.isPlaying,
                positionMs = player.positionMs,
                durationMs = player.durationMs ?: current?.durationMs,
            )
        }
    }
}
