package app.omnisonic.playback.api.renderer

import app.omnisonic.playback.api.player.OmniPlayer

/** One output device seam; Phase 4 ships only the local path. */
interface PlaybackRenderer {
    val rendererId: String
    val isLocal: Boolean

    suspend fun activate(): OmniPlayer
    fun deactivate()
    fun isActive(): Boolean
}
