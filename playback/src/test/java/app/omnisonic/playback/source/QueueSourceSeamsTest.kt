package app.omnisonic.playback.source

import app.omnisonic.playback.api.queue.QueueState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class QueueSourceSeamsTest {

    @Test
    fun `the seams are no-ops until their features land`() = runTest {
        for (source in listOf(RadioQueueSource, AutoplayQueueSource, SearchPlayQueueSource)) {
            assertThat(source.fillQueueTail(QueueState(), desiredCount = 5)).isEmpty()
            assertThat(source.playFromSearch("query")).isEmpty()
        }
    }
}
