package app.omnisonic.playback.service

import android.content.Context
import android.os.PowerManager

/**
 * Process-level partial wake lock held while the controller reports playback.
 * Unrefcounted so play/pause churn cannot leak holds; the engine additionally
 * carries its own media wake mode while the sink renders.
 */
class PlaybackWakeLock(context: Context, tag: String = DEFAULT_TAG) {

    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        .apply { setReferenceCounted(false) }

    @Volatile
    private var held = false

    fun setHeld(held: Boolean) {
        if (held == this.held) return
        this.held = held
        if (held) lock.acquire() else lock.release()
    }

    fun release() = setHeld(false)

    companion object {
        const val DEFAULT_TAG = "app.omnisonic:playback"
    }
}
