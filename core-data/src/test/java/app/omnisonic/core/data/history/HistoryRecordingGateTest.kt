package app.omnisonic.core.data.history

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRecordingGateTest {

    @TempDir
    lateinit var tempDir: File

    private fun newStore(tag: String) = HistoryPreferencesStore.createAt(
        File(tempDir, "$tag-${System.nanoTime()}.preferences_pb"),
    )

    @Test
    fun `setPaused flips the in-memory flag immediately`() = runTest {
        val gate = HistoryRecordingGate()

        gate.setPaused(1L, true)
        assertThat(gate.isPaused(1L)).isTrue()
        assertThat(gate.pausedProviders.value).containsExactly(1L)

        gate.setPaused(1L, false)
        assertThat(gate.isPaused(1L)).isFalse()
        assertThat(gate.pausedProviders.value).isEmpty()
    }

    @Test
    fun `setPaused persists to the store`(): Unit = runBlocking {
        val store = newStore("persist")
        val gate = HistoryRecordingGate(store)

        gate.setPaused(4L, true)
        gate.setPaused(5L, true)
        gate.setPaused(4L, false)

        withTimeoutOrNull(2_000) {
            while (store.current().pausedProviderIds != setOf(5L)) delay(10)
        }
        assertThat(store.current().pausedProviderIds).containsExactly(5L)
    }

    @Test
    fun `a fresh gate is seeded from the persisted pause`() = runTest {
        val file = seedFile("seed") { store ->
            store.setPaused(9L, true)
            withTimeoutOrNull(2_000) { while (store.current().pausedProviderIds != setOf(9L)) delay(10) }
        }

        val gate = HistoryRecordingGate(HistoryPreferencesStore.createAt(file))
        withTimeoutOrNull(2_000) {
            while (9L !in gate.pausedProviders.value) delay(10)
        }

        assertThat(gate.isPaused(9L)).isTrue()
        assertThat(gate.pausedProviders.first()).containsExactly(9L)
    }

    @Test
    fun `a pause made while the seed is blocked is not overwritten`(): Unit = runBlocking {
        val pausedKey = stringSetPreferencesKey("history.paused_provider_ids")
        val initial = preferencesOf(pausedKey to setOf("9"))
        val dataStore = BlockingHistoryDataStore(initial)
        val gate = HistoryRecordingGate(HistoryPreferencesStore(dataStore))
        withTimeout(2_000) { dataStore.readStarted.await() }

        gate.setPaused(6L, true)
        dataStore.releaseRead.complete(Unit)
        withTimeout(2_000) { gate.awaitReady() }

        assertThat(gate.pausedProviders.value).containsExactly(6L, 9L)
        assertThat(dataStore.persisted[pausedKey]).containsExactly("6", "9")
    }

    /** Writes through a store instance that is closed (and flushed) before the file is reopened. */
    private suspend fun seedFile(tag: String, block: suspend (HistoryPreferencesStore) -> Unit): File {
        val file = File(tempDir, "$tag-${System.nanoTime()}.preferences_pb")
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val store = HistoryPreferencesStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        )
        block(store)
        scope.cancel()
        withTimeoutOrNull(2_000) { while (job.children.any()) delay(10) }
        return file
    }

    @Test
    fun `the in-memory gate stays process-local`() = runTest {
        val gate = HistoryRecordingGate()

        gate.setPaused(2L, true)

        assertThat(gate.isPaused(2L)).isTrue()
        assertThat(gate.isPaused(3L)).isFalse()
    }
}
