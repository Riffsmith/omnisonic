package app.omnisonic.core.data.downloads

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueSettingsStoreTest {

    @TempDir
    lateinit var tempDir: File

    private var scope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private fun newDataStore(file: File): DataStore<Preferences> {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = storeScope
        return PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file })
    }

    private fun newStore(file: File = File(tempDir, "downloads-${System.nanoTime()}.preferences_pb")) =
        DownloadQueueSettingsStore(newDataStore(file))

    @Test
    fun `a fresh store is not globally paused`() = runTest {
        assertThat(newStore().globallyPaused.first()).isFalse()
    }

    @Test
    fun `the gate round-trips`() = runTest {
        val store = newStore()

        store.setGloballyPaused(true)
        assertThat(store.isGloballyPaused()).isTrue()

        store.setGloballyPaused(false)
        assertThat(store.isGloballyPaused()).isFalse()
    }

    @Test
    fun `the in-memory store round-trips without a file`() = runTest {
        val store = DownloadQueueSettingsStore.inMemory()

        store.setGloballyPaused(true)

        assertThat(store.isGloballyPaused()).isTrue()
    }

    @Test
    fun `the gate persists across store instances even with no queue rows`() = runTest {
        val file = File(tempDir, "shared.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val first = DownloadQueueSettingsStore(
            PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }),
        )
        first.setGloballyPaused(true)
        firstScope.cancel()
        withTimeoutOrNull(2_000) { while (firstJob.children.any()) delay(10) }

        val second = newStore(file)

        assertThat(second.isGloballyPaused()).isTrue()
    }
}
