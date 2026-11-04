package app.omnisonic.playback.focus

/**
 * The focus handle the controller drives. [AudioFocusHelper] is the production
 * implementation; the seam keeps the controller testable without an
 * `AudioManager` and leaves room for renderer-specific focus later.
 */
interface AudioFocusOwner {
    /** Returns whether the request was granted. */
    fun requestFocus(): Boolean
    fun abandonFocus()
    fun release()
}

/** Builds the focus owner bound to a controller's [AudioFocusHelper.Callbacks]. */
fun interface FocusOwnerFactory {
    fun create(callbacks: AudioFocusHelper.Callbacks): AudioFocusOwner
}

/** Focus-less owner used by tests and by consumers that drive focus elsewhere. */
object NoOpAudioFocusOwner : AudioFocusOwner {
    override fun requestFocus(): Boolean = true
    override fun abandonFocus() = Unit
    override fun release() = Unit
}
