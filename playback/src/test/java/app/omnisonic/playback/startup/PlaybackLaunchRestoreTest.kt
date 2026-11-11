package app.omnisonic.playback.startup

import app.omnisonic.playback.api.controller.PlaybackController
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.system.measureTimeMillis

class PlaybackLaunchRestoreTest {

    @Test
    fun `restore applies the snapshot once on the engine scope`() = runBlocking {
        var calls = 0
        val controller = mockk<PlaybackController> {
            coEvery { restoreQueue() } coAnswers {
                calls += 1
                true
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        PlaybackLaunchRestore(controller, scope).restore()

        assertThat(calls).isEqualTo(1)
        coVerify(exactly = 1) { controller.restoreQueue() }
        scope.cancel()
    }

    @Test
    fun `restore does not hold up launch on a stuck store`() = runBlocking {
        val controller = mockk<PlaybackController> {
            coEvery { restoreQueue() } coAnswers {
                delay(10_000)
                true
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val elapsed = measureTimeMillis {
            PlaybackLaunchRestore(controller, scope, timeoutMs = 50).restore()
        }

        assertThat(elapsed).isLessThan(1_000)
        scope.cancel()
    }
}
