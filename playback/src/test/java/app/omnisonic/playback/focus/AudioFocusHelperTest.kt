package app.omnisonic.playback.focus

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.playback.api.settings.TransientLossAction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioFocusHelperTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var shadowAudio: ShadowAudioManager
    private lateinit var callbacks: RecordingCallbacks
    private lateinit var action: MutableStateFlow<TransientLossAction>
    private lateinit var scope: CoroutineScope
    private lateinit var helper: AudioFocusHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        shadowAudio = shadowOf(audioManager)
        callbacks = RecordingCallbacks()
        action = MutableStateFlow(TransientLossAction.DUCK)
        scope = CoroutineScope(Dispatchers.Unconfined)
        helper = newHelper()
    }

    @After
    fun tearDown() {
        helper.release()
        scope.cancel()
    }

    @Test
    fun `request uses media usage and music content type`() {
        grantFocus()

        val request = shadowAudio.lastAudioFocusRequest.audioFocusRequest

        assertThat(request.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_MEDIA)
        assertThat(request.audioAttributes.contentType).isEqualTo(AudioAttributes.CONTENT_TYPE_MUSIC)
        assertThat(helper.isFocusHeld).isTrue()
    }

    @Test
    fun `denied request leaves focus unheld`() {
        shadowAudio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        assertThat(helper.requestFocus()).isFalse()
        assertThat(helper.isFocusHeld).isFalse()
    }

    @Test
    fun `transient loss ducks and focus gain unducks`() {
        val listener = grantFocus().listener

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertThat(callbacks.ducks).containsExactly(AudioFocusHelper.DEFAULT_DUCK_LINEAR)
        assertThat(helper.isDucked).isTrue()
        assertThat(callbacks.pauses).isEqualTo(0)

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertThat(callbacks.unducks).isEqualTo(1)
        assertThat(helper.isDucked).isFalse()
        assertThat(callbacks.resumes).isEqualTo(0)
    }

    @Test
    fun `duckable transient loss ducks by default`() {
        val listener = grantFocus().listener

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertThat(callbacks.ducks).containsExactly(AudioFocusHelper.DEFAULT_DUCK_LINEAR)
        assertThat(helper.isDucked).isTrue()
    }

    @Test
    fun `transient loss pauses when the setting asks for pause and focus gain resumes`() {
        action.value = TransientLossAction.PAUSE
        val listener = grantFocus().listener

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertThat(callbacks.pauses).isEqualTo(1)
        assertThat(callbacks.ducks).isEmpty()
        assertThat(helper.isPausedByFocus).isTrue()

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertThat(callbacks.resumes).isEqualTo(1)
        assertThat(helper.isPausedByFocus).isFalse()
    }

    @Test
    fun `full loss pauses and focus gain resumes`() {
        val listener = grantFocus().listener

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertThat(callbacks.pauses).isEqualTo(1)
        assertThat(helper.isPausedByFocus).isTrue()

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertThat(callbacks.resumes).isEqualTo(1)
        assertThat(helper.isPausedByFocus).isFalse()
    }

    @Test
    fun `focus loss while already paused records nothing and never resumes`() {
        val listener = grantFocus().listener
        callbacks.playing = false

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertThat(callbacks.pauses).isEqualTo(0)
        assertThat(callbacks.ducks).isEmpty()
        assertThat(helper.isPausedByFocus).isFalse()

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertThat(callbacks.resumes).isEqualTo(0)
    }

    @Test
    fun `abandon returns focus and clears ducking`() {
        val listener = grantFocus().listener
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        helper.abandonFocus()

        assertThat(shadowAudio.lastAbandonedAudioFocusRequest).isNotNull()
        assertThat(helper.isFocusHeld).isFalse()
        assertThat(helper.isDucked).isFalse()
        assertThat(callbacks.unducks).isEqualTo(1)

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertThat(callbacks.resumes).isEqualTo(0)
    }

    @Test
    fun `switch to pause applies to the next transient loss`() {
        val listener = grantFocus().listener
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        action.value = TransientLossAction.PAUSE
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertThat(callbacks.ducks).containsExactly(AudioFocusHelper.DEFAULT_DUCK_LINEAR)
        assertThat(callbacks.pauses).isEqualTo(1)
    }

    @Test
    fun `explicit re-request clears a stale focus pause`() {
        action.value = TransientLossAction.PAUSE
        val listener = grantFocus().listener
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertThat(helper.isPausedByFocus).isTrue()

        helper.requestFocus()

        assertThat(helper.isPausedByFocus).isFalse()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertThat(callbacks.resumes).isEqualTo(0)
    }

    @Test
    fun `released helper ignores late focus changes and stops abandoning twice`() {
        val listener = grantFocus().listener

        helper.release()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertThat(callbacks.ducks).isEmpty()
        assertThat(callbacks.pauses).isEqualTo(0)
        assertThat(callbacks.resumes).isEqualTo(0)
        assertThat(helper.isFocusHeld).isFalse()
    }

    private fun newHelper(): AudioFocusHelper =
        AudioFocusHelper(
            context = context,
            callbacks = callbacks,
            transientLossAction = action,
            scope = scope,
            audioManager = audioManager,
        )

    private fun grantFocus(): ShadowAudioManager.AudioFocusRequest {
        shadowAudio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertThat(helper.requestFocus()).isTrue()
        return requireNotNull(shadowAudio.lastAudioFocusRequest)
    }

    private class RecordingCallbacks : AudioFocusHelper.Callbacks {
        var playing = true
        val ducks = mutableListOf<Float>()
        var unducks = 0
        var pauses = 0
        var resumes = 0

        override fun isPlaying(): Boolean = playing

        override fun onDuck(factor: Float) {
            ducks += factor
        }

        override fun onUnduck() {
            unducks++
        }

        override fun onPauseForFocus() {
            pauses++
        }

        override fun onResumeAfterFocus() {
            resumes++
        }
    }
}
