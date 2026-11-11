package app.omnisonic.core.data

import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderCountFlowTest : DatabaseTestBase() {

    @Test
    fun `count flow starts at zero and follows inserts and deletes`() = runBlocking {
        db = OmnisonicDatabaseFactory(appContext).openInMemory()
        try {
            val counts = Channel<Int>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.IO) {
                providerDao.countFlow().collect { counts.send(it) }
            }
            // The first value guarantees the observer is registered before the insert.
            assertThat(withTimeout(TIMEOUT_MS) { counts.receive() }).isEqualTo(0)

            newProvider(id = 1)
            assertThat(withTimeout(TIMEOUT_MS) { counts.receive() }).isEqualTo(1)

            providerDao.deleteById(1)
            assertThat(withTimeout(TIMEOUT_MS) { counts.receive() }).isEqualTo(0)

            collector.cancelAndJoin()
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
