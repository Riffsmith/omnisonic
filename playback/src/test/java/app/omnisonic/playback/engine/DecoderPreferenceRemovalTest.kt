package app.omnisonic.playback.engine

import androidx.test.core.app.ApplicationProvider
import app.omnisonic.playback.api.player.EngineConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the T15 decision: a decoder extension is not scheduled until Phase 4, so the
 * engine config carries no decoder preference knob and the factory never wires an
 * extension renderer preference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DecoderPreferenceRemovalTest {

    @Test
    fun `engine config has no decoder preference knob`() {
        val names = EngineConfig::class.java.declaredFields.map { it.name }
        assertThat(names).doesNotContain("decoderPreference")
    }

    @Test
    fun `factory builds engines without an extension renderer preference`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ExoEngineFactory(context).create(EngineConfig())
        try {
            assertThat(engine.info().extensionAvailable).isFalse()
            assertThat(engine.info().config).isEqualTo(EngineConfig())
        } finally {
            engine.release()
        }
    }
}
