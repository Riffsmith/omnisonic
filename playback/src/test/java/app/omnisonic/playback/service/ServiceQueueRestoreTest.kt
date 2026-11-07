package app.omnisonic.playback.service

import app.omnisonic.playback.api.controller.PlaybackController
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.system.measureTimeMillis

class ServiceQueueRestoreTest {

    @Test
    fun `restoreQueueWithin gives up on a stuck store instead of blocking the start path`() = runBlocking {
        val controller = mockk<PlaybackController> {
            coEvery { restoreQueue() } coAnswers {
                delay(10_000)
                true
            }
        }

        val elapsed = measureTimeMillis {
            assertThat(restoreQueueWithin(controller, timeoutMs = 50)).isFalse()
        }

        assertThat(elapsed).isLessThan(1_000)
    }
}
