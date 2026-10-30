package app.omnisonic.playback.engine

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.WakeMode
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSystemClock
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeModeTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var fakeClock: FakeClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        fakeClock = FakeClock(false)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `default config selects network wake mode`() {
        assertThat(EngineConfig().wakeMode).isEqualTo(WakeMode.NETWORK)
    }

    @Test
    fun `default engine acquires the wifi lock while playing and releases the wake lock on pause`() {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        val engine = ExoEngineFactory(context, fakeClock).create(EngineConfig())

        val wifiCountAfterBuild = shadowOf(wifiManager).activeLockCount
        assertThat(wifiCountAfterBuild).isNotEqualTo(0)

        serveWav()
        engine.player.setMediaItem(MediaItem.fromUri(server.url("/track.wav").toString()))
        engine.player.prepare()
        engine.player.play()
        await("ready") { engine.player.playbackState == Player.STATE_READY }

        val wakeLock = ShadowPowerManager.getLatestWakeLock()

        assertThat(shadowOf(wifiManager).activeLockCount).isGreaterThan(wifiCountAfterBuild)
        assertThat(wakeLock).isNotNull()
        assertThat(wakeLock!!.isHeld).isTrue()

        engine.player.pause()
        idleMainLooper()

        assertThat(wakeLock.isHeld).isFalse()

        engine.release()
    }

    @Test
    fun `local wake mode never creates a wifi lock`() {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        val engine = ExoEngineFactory(context, fakeClock).create(EngineConfig(wakeMode = WakeMode.LOCAL))

        val wifiCountAfterBuild = shadowOf(wifiManager).activeLockCount

        serveWav()
        engine.player.setMediaItem(MediaItem.fromUri(server.url("/track.wav").toString()))
        engine.player.prepare()
        engine.player.play()
        await("ready") { engine.player.playbackState == Player.STATE_READY }

        assertThat(wifiCountAfterBuild).isEqualTo(0)
        assertThat(shadowOf(wifiManager).activeLockCount).isEqualTo(0)

        engine.release()
    }

    private fun serveWav() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(wavBytes(300))))
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
    }

    private fun await(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) fail("timeout: $message")
            idleMainLooper()
            fakeClock.advanceTime(25)
            ShadowSystemClock.advanceBy(25, TimeUnit.MILLISECONDS)
            Thread.sleep(25)
        }
    }

    private fun wavBytes(durationMs: Int = 300, sampleRate: Int = 8_000): ByteArray {
        val sampleCount = durationMs * sampleRate / 1000
        val dataSize = sampleCount * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun le16(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        fun le32(value: Int) {
            le16(value and 0xFFFF)
            le16((value shr 16) and 0xFFFF)
        }
        out.write("RIFF".toByteArray())
        le32(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1)
        le16(1)
        le32(sampleRate)
        le32(sampleRate * 2)
        le16(2)
        le16(16)
        out.write("data".toByteArray())
        le32(dataSize)
        var phase = 0.0
        repeat(sampleCount) {
            val sample = (Math.sin(phase) * 8000).toInt()
            le16(sample and 0xFFFF)
            phase += 2 * Math.PI * 440 / sampleRate
        }
        return out.toByteArray()
    }
}
