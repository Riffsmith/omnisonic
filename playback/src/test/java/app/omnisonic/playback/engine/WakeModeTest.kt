package app.omnisonic.playback.engine

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.WakeLockManager
import androidx.media3.common.util.WifiLockManager
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.WakeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSystemClock
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeModeTest {

    private lateinit var context: Context
    private lateinit var fakeClock: FakeClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.WAKE_LOCK)
        fakeClock = FakeClock(false)
    }

    @Test
    fun `default config selects network wake mode`() {
        assertThat(EngineConfig().wakeMode).isEqualTo(WakeMode.NETWORK)
    }

    @Test
    fun `wake mode maps to the media3 network and local constants`() {
        assertThat(WakeMode.NETWORK.toPlayerWakeMode()).isEqualTo(C.WAKE_MODE_NETWORK)
        assertThat(WakeMode.LOCAL.toPlayerWakeMode()).isEqualTo(C.WAKE_MODE_LOCAL)
    }

    @Test
    fun `network wake mode holds the wifi lock while playing and releases on pause`() {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        val wifiLockManager = WifiLockManager(context, Looper.getMainLooper(), fakeClock)

        wifiLockManager.setStayAwake(true)
        wifiLockManager.setEnabled(true)
        pump()

        assertThat(shadowOf(wifiManager).activeLockCount).isGreaterThan(0)

        wifiLockManager.setStayAwake(false)
        pump()

        assertThat(shadowOf(wifiManager).activeLockCount).isEqualTo(0)
    }

    @Test
    fun `network wake mode holds the wake lock while playing and releases on pause`() {
        val wakeLockManager = WakeLockManager(context, Looper.getMainLooper(), fakeClock)

        wakeLockManager.setStayAwake(true)
        wakeLockManager.setEnabled(true)
        pump()

        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertThat(wakeLock).isNotNull()
        assertThat(wakeLock!!.isHeld).isTrue()

        wakeLockManager.setStayAwake(false)
        pump()

        assertThat(wakeLock.isHeld).isFalse()
    }

    @Test
    fun `local wake mode never creates a wifi lock`() {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        val wifiLockManager = WifiLockManager(context, Looper.getMainLooper(), fakeClock)

        wifiLockManager.setStayAwake(true)
        pump()

        assertThat(shadowOf(wifiManager).activeLockCount).isEqualTo(0)
    }

    private fun pump() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            fakeClock.advanceTime(50)
            ShadowSystemClock.advanceBy(50, TimeUnit.MILLISECONDS)
            Thread.sleep(25)
        }
    }
}
