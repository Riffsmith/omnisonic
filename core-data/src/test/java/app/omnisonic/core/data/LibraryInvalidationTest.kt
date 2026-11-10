package app.omnisonic.core.data

import app.omnisonic.core.data.browse.BrowseRepository
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.sync.LibraryInvalidation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryInvalidationTest : DatabaseTestBase() {

    @Test
    fun `catalog writes emit through the repository change feed`() = runBlocking {
        db = OmnisonicDatabaseFactory(appContext).openInMemory()
        try {
            val repository = BrowseRepository(db, LibraryInvalidation(db))
            val firstChange = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.IO) {
                repository.libraryChanges.collect {
                    if (!firstChange.isCompleted) firstChange.complete(Unit)
                }
            }
            delay(OBSERVER_SETTLE_MS)

            assertThat(repository.albumCount()).isEqualTo(0)
            newAlbum(id = 1, providerId = 1, title = "Live")

            withTimeout(CHANGE_TIMEOUT_MS) { firstChange.await() }
            assertThat(repository.albumCount()).isEqualTo(1)
            collector.cancelAndJoin()
        } finally {
            db.close()
        }
    }

    private companion object {
        const val OBSERVER_SETTLE_MS = 200L
        const val CHANGE_TIMEOUT_MS = 5_000L
    }
}
