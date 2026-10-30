package app.omnisonic.playback.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.request.Options
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverKeyerTest {

    @Test
    fun `cover key is stable across auth salt changes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keyer = CoverKeyer()
        val options = Options(context)

        val first = keyer.key(CoverImageKey(providerId = 7L, imageId = "ca-9", sizePx = 300), options)
        val second = keyer.key(CoverImageKey(providerId = 7L, imageId = "ca-9", sizePx = 300), options)

        assertThat(first).isEqualTo("cover:7:ca-9:300")
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `cover key distinguishes size and provider`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val options = Options(context)
        val keyer = CoverKeyer()

        assertThat(keyer.key(CoverImageKey(1L, "ca-9"), options)).isEqualTo("cover:1:ca-9:original")
        assertThat(keyer.key(CoverImageKey(2L, "ca-9", 300), options)).isNotEqualTo(
            keyer.key(CoverImageKey(1L, "ca-9", 300), options),
        )
    }
}
