package app.omnisonic.feature.library.nowplaying

import app.omnisonic.playback.api.controller.PlayerCoreState
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.provider.api.CoverImageKey

data class NowPlayingEntry(
    val queueItemId: Long,
    val title: String,
    val artistName: String?,
    val cover: CoverImageKey?,
)

/**
 * Now-playing view state (UI_SPEC.md §3.8), mapped exclusively from
 * [PlayerCoreState] + [QueueState] — the screen never sees engine or session
 * types.
 */
data class NowPlayingUiState(
    val entry: NowPlayingEntry? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
) {
    val isEmpty: Boolean get() = entry == null

    val progress: Float
        get() {
            val duration = durationMs ?: return 0f
            if (duration <= 0L) return 0f
            return (positionMs.toFloat() / duration).coerceIn(0f, 1f)
        }

    companion object {
        fun from(player: PlayerCoreState, queue: QueueState): NowPlayingUiState {
            val current = player.current ?: queue.currentEntry
            return NowPlayingUiState(
                entry = resolveNowPlayingEntry(player, queue),
                isPlaying = player.isPlaying,
                positionMs = player.positionMs,
                durationMs = player.durationMs ?: current?.durationMs,
                shuffleEnabled = queue.shuffle != null,
                repeatMode = queue.repeatMode,
            )
        }
    }
}

/**
 * The queue pointer survives a stopped/ended engine, so the current song is
 * read from the player first and the queue second. Shared by every playback
 * surface that shows the playing item (now-playing, mini-player).
 */
internal fun resolveNowPlayingEntry(
    player: PlayerCoreState,
    queue: QueueState,
): NowPlayingEntry? {
    val current = player.current ?: queue.currentEntry ?: return null
    return NowPlayingEntry(
        queueItemId = current.queueItemId,
        title = current.title,
        artistName = current.artistName,
        cover = current.coverImageId?.let { CoverImageKey(current.providerId, it) },
    )
}
