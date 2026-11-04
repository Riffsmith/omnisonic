package app.omnisonic.playback.service

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackWakeLockTest {

    @Test
    fun `holds an unrefcounted partial wake lock while playing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.getSystemService(PowerManager::class.java))
        val wakeLock = PlaybackWakeLock(context)

        wakeLock.setHeld(true)
        wakeLock.setHeld(true)

        val lock = ShadowPowerManager.getLatestWakeLock()
        assertThat(lock.isHeld).isTrue()
        assertThat(shadowOf(lock).isReferenceCounted).isFalse()
    }

    @Test
    fun `releasing twice stays safe and unheld`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.getSystemService(PowerManager::class.java))
        val wakeLock = PlaybackWakeLock(context)

        wakeLock.setHeld(true)
        wakeLock.setHeld(false)
        wakeLock.release()

        assertThat(ShadowPowerManager.getLatestWakeLock().isHeld).isFalse()
    }
}
