package app.omnisonic.playback.engine

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.playback.api.player.DecoderPreference
import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.OffloadMode
import app.omnisonic.playback.audio.FadeAudioProcessor
import app.omnisonic.playback.audio.MonoAudioProcessor
import app.omnisonic.playback.audio.PeqAudioProcessor
import app.omnisonic.playback.audio.ReplayGainAudioProcessor
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExoEngineFactoryTest {

    @Test
    fun `engine builds with full processor chain and exposes player`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = ExoEngineFactory(context) {
            arrayOf(
                ReplayGainAudioProcessor(),
                PeqAudioProcessor(),
                MonoAudioProcessor(),
                FadeAudioProcessor(),
            )
        }
        val config = EngineConfig(
            decoderPreference = DecoderPreference.EXTENSION_FIRST,
            offloadMode = OffloadMode.AUTO,
        )
        val engine = factory.create(config)

        assertThat(engine.player).isNotNull()
        assertThat(engine.player.applicationLooper).isEqualTo(Looper.getMainLooper())
        assertThat(engine.info().config).isEqualTo(config)
        assertThat(engine.info().loudnessCodecAttached).isFalse()

        engine.release()
    }

    @Test
    fun `default config engine builds and releases`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ExoEngineFactory(context).create(EngineConfig())
        assertThat(engine.player.playbackState).isAnyOf(
            androidx.media3.common.Player.STATE_IDLE,
            androidx.media3.common.Player.STATE_READY,
        )
        engine.release()
    }

    @Test
    fun `offload is not effective while any sink processor is wired`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = ExoEngineFactory(context) {
            arrayOf(
                ReplayGainAudioProcessor(),
                PeqAudioProcessor(),
                MonoAudioProcessor(),
                FadeAudioProcessor(),
            )
        }
        val engine = factory.create(EngineConfig(offloadMode = OffloadMode.ENABLED))
        try {
            val info = engine.info()
            assertThat(info.offloadEffective).isFalse()
            assertThat(info.extensionAvailable).isFalse()
        } finally {
            engine.release()
        }
    }

    @Test
    fun `requested offload survives when no sink processors are wired`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ExoEngineFactory(context) { emptyArray() }
            .create(EngineConfig(offloadMode = OffloadMode.ENABLED))
        try {
            assertThat(engine.info().offloadEffective).isTrue()
        } finally {
            engine.release()
        }
    }

    @Test
    fun `offload stays ineffective when the request was disabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ExoEngineFactory(context) { emptyArray() }.create(EngineConfig())
        try {
            assertThat(engine.info().offloadEffective).isFalse()
        } finally {
            engine.release()
        }
    }
}
