package app.omnisonic.core.data.search

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
class ProviderVisibilityStoreTest {

    @TempDir
    lateinit var tempDir: File

    private var scope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private fun newStore(file: File = File(tempDir, "visibility-${System.nanoTime()}.preferences_pb")): ProviderVisibilityStore {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = storeScope
        return ProviderVisibilityStore(
            PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file }),
        )
    }

    @Test
    fun `fresh store hides nothing`() = runTest {
        val store = newStore()

        assertThat(store.hiddenProviderIds.first()).isEmpty()
    }

    @Test
    fun `hidden ids flip on and off per provider`() = runTest {
        val store = newStore()

        store.setHidden(3, true)
        store.setHidden(7, true)
        assertThat(store.hiddenProviderIds.first()).containsExactly(3L, 7L)

        store.setHidden(3, false)
        assertThat(store.hiddenProviderIds.first()).containsExactly(7L)
    }

    @Test
    fun `replace overwrites the hidden set and clear empties it`() = runTest {
        val store = newStore()

        store.setHidden(1, true)
        store.replaceHidden(listOf(9, 2))
        assertThat(store.hiddenProviderIds.first()).containsExactly(9L, 2L)

        store.clear()
        assertThat(store.hiddenProviderIds.first()).isEmpty()
    }

    @Test
    fun `hidden ids persist across store instances on one file`() = runTest {
        val file = File(tempDir, "shared.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val first = ProviderVisibilityStore(
            PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }),
        )
        first.setHidden(4, true)
        firstScope.cancel()
        withTimeoutOrNull(2_000) { while (firstJob.children.any()) delay(10) }

        val second = newStore(file)

        assertThat(second.hiddenProviderIds.first()).containsExactly(4L)
    }
}
