package app.omnisonic.playback.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSessionHostTest {

    @Test
    fun connectBindsOncePerProcess() {
        var connects = 0
        val host = PlaybackSessionHost { connects++ }

        host.connect()
        host.connect()
        host.connect()

        assertEquals(1, connects)
    }

    @Test
    fun failedConnectIsSwallowedAndNotRetried() {
        var connects = 0
        val host = PlaybackSessionHost {
            connects++
            error("service unavailable")
        }

        host.connect()
        host.connect()

        assertEquals(1, connects)
    }
}
