package app.omnisonic.log

import android.util.Log
import app.omnisonic.core.model.log.LogLevel
import app.omnisonic.core.model.log.OmnisonicLogger
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidLogSinkTest {

    @Test
    fun `maps every level onto the matching logcat priority`() {
        ShadowLog.reset()
        val logger = OmnisonicLogger(AndroidLogSink())

        logger.debug("T", "d")
        logger.info("T", "i")
        logger.warn("T", "w")
        logger.error("T", "e")

        val entries = ShadowLog.getLogs()
        assertThat(entries.map { it.type }).containsExactly(
            Log.DEBUG,
            Log.INFO,
            Log.WARN,
            Log.ERROR,
        ).inOrder()
        assertThat(entries.map { it.msg }).containsExactly("d", "i", "w", "e").inOrder()
    }

    @Test
    fun `the scrubbed throwable reaches logcat instead of the raw error`() {
        ShadowLog.reset()
        val logger = OmnisonicLogger(AndroidLogSink())

        logger.error("T", "drain failed", IllegalStateException("password=hunter2"))

        val entry = ShadowLog.getLogs().single()
        assertThat(entry.throwable).isNotNull()
        assertThat(entry.throwable!!.message).contains("[REDACTED]")
        assertThat(entry.throwable!!.message).doesNotContain("hunter2")
    }
}
