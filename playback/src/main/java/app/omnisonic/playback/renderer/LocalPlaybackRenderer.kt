package app.omnisonic.playback.renderer

import app.omnisonic.playback.api.player.OmniPlayer
import app.omnisonic.playback.api.renderer.PlaybackRenderer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The on-device output renderer. Holds exactly one [OmniPlayer] created on
 * first activation; release is owned by [deactivate] so a later renderer swap
 * (cast) can take over the session.
 */
class LocalPlaybackRenderer(
    private val playerFactory: () -> OmniPlayer,
) : PlaybackRenderer {

    private val mutex = Mutex()
    private var player: OmniPlayer? = null

    override val rendererId: String = RENDERER_ID
    override val isLocal: Boolean = true

    override suspend fun activate(): OmniPlayer = mutex.withLock {
        player ?: playerFactory().also { player = it }
    }

    override fun deactivate() {
        val current = synchronized(this) {
            player.also { player = null }
        }
        current?.release()
    }

    override fun isActive(): Boolean = synchronized(this) { player != null }

    companion object {
        const val RENDERER_ID = "local"
    }
}
