package app.omnisonic.playback.focus

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import app.omnisonic.playback.api.settings.TransientLossAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Owns one audio-focus request for the local renderer. Focus changes are
 * translated into playback commands through [Callbacks]; the helper never
 * touches the player itself so the controller stays the single command hub.
 */
class AudioFocusHelper(
    context: Context,
    private val callbacks: Callbacks,
    transientLossAction: Flow<TransientLossAction>,
    scope: CoroutineScope,
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java),
    private val duckLinear: Float = DEFAULT_DUCK_LINEAR,
    looper: Looper = Looper.getMainLooper(),
) : AudioFocusOwner {
    interface Callbacks {
        fun isPlaying(): Boolean

        /** Lower the output by [factor] (linear); paired with [onUnduck]. */
        fun onDuck(factor: Float)
        fun onUnduck()

        /** Stop playback because another app took focus; paired with [onResume]. */
        fun onPauseForFocus()

        /** Focus came back after a focus-caused pause. */
        fun onResumeAfterFocus()
    }

    private val handler = Handler(looper)

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        handleFocusChange(change)
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        // We implement ducking ourselves; without this the system turns a
        // duckable loss into a full transient loss.
        .setWillPauseWhenDucked(false)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(focusListener, handler)
        .build()

    @Volatile
    private var transientAction: TransientLossAction = TransientLossAction.DUCK

    private val settingsJob: Job = scope.launch {
        transientLossAction.collect { transientAction = it }
    }

    private var focusHeld = false
    private var pausedByFocus = false
    private var ducked = false
    private var released = false

    val isFocusHeld: Boolean get() = focusHeld
    val isDucked: Boolean get() = ducked
    val isPausedByFocus: Boolean get() = pausedByFocus

    /** Returns whether the request was granted; denied requests leave focus unheld. */
    override fun requestFocus(): Boolean {
        if (released) return false
        val granted = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) return false

        focusHeld = true
        // An explicit re-request starts a new playback session; stale
        // focus-pause bookkeeping from the previous one must not trigger a
        // spurious resume on the next focus gain.
        pausedByFocus = false
        if (ducked) {
            ducked = false
            callbacks.onUnduck()
        }
        return true
    }

    /** Call when playback is idle: returns focus and clears focus bookkeeping. */
    override fun abandonFocus() {
        if (focusHeld) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            focusHeld = false
        }
        pausedByFocus = false
        if (ducked) {
            ducked = false
            callbacks.onUnduck()
        }
    }

    override fun release() {
        if (released) return
        released = true
        settingsJob.cancel()
        if (focusHeld) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            focusHeld = false
        }
        pausedByFocus = false
        ducked = false
    }

    private fun handleFocusChange(change: Int) {
        if (released) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (ducked) {
                    ducked = false
                    callbacks.onUnduck()
                }
                if (pausedByFocus) {
                    pausedByFocus = false
                    callbacks.onResumeAfterFocus()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                if (!callbacks.isPlaying()) return
                if (transientAction == TransientLossAction.PAUSE) {
                    pausedByFocus = true
                    callbacks.onPauseForFocus()
                } else {
                    ducked = true
                    callbacks.onDuck(duckLinear)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                focusHeld = false
                if (!callbacks.isPlaying()) return
                pausedByFocus = true
                callbacks.onPauseForFocus()
            }
        }
    }

    companion object {
        /** Matches the well-established duck level used by media apps. */
        const val DEFAULT_DUCK_LINEAR = 0.2f
    }
}
