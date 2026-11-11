package app.omnisonic.playback.api.controller

import app.omnisonic.playback.api.events.PlaybackEventBus
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueCommandPort
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.renderer.PlaybackRenderer
import app.omnisonic.playback.api.settings.NotificationAction
import app.omnisonic.playback.api.source.QueueSources
import kotlinx.coroutines.flow.StateFlow

data class PlayerCoreState(
    val current: PlaybackQueueEntry? = null,
    val isPlaying: Boolean = false,
    val isIdle: Boolean = true,
    val ducked: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val speed: Float = 1f,
    val pitch: Float = 1f,
)

/**
 * Hub commands flow through: UI and session callbacks talk only to this.
 * Extends [QueueCommandPort] so the exposed player can route its queue-aware
 * commands here without reaching the queue machine.
 */
interface PlaybackController : QueueCommandPort {
    val playerState: StateFlow<PlayerCoreState>
    val events: PlaybackEventBus
    val queueSources: QueueSources

    suspend fun play(entries: List<PlaybackQueueEntry>, startEntriesIndex: Int = 0, sourceType: String? = null, sourceData: String? = null)
    fun playOrPause()
    fun pause()
    fun resume()
    fun stop()

    fun seekTo(positionMs: Long)

    fun setPlaybackSpeed(scale: Float)
    fun setPlaybackPitch(pitch: Float)

    /** Executes lock-screen/notification and media-button commands. */
    fun applyAction(action: NotificationAction)

    val renderer: PlaybackRenderer

    /**
     * Applies a persisted queue snapshot without starting playback. At most one
     * call per process applies a snapshot; later calls (from the media service,
     * for instance) report `false` and leave the live queue untouched. Must run
     * on the engine thread because the queue machine commands the player inline.
     */
    suspend fun restoreQueue(): Boolean

    fun release()
}
