package app.omnisonic.core.data.downloads

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSettingsStoreTest {

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

    private fun newStore(file: File = File(tempDir, "settings-${System.nanoTime()}.preferences_pb")) =
        DownloadQueueSettingsStore(newDataStore(file))

    @Test
    fun `defaults follow the confirmed worker and wifi choices`() = runTest {
        val store = newStore()

        val settings = store.awaitReady()

        assertThat(settings.workerCount).isEqualTo(2)
        assertThat(settings.wifiOnly).isTrue()
        assertThat(settings.workerCount).isAtLeast(DownloadSettingsRules.MIN_WORKER_COUNT)
        assertThat(settings.workerCount).isAtMost(DownloadSettingsRules.MAX_WORKER_COUNT)
    }

    @Test
    fun `settings round-trip and persist across instances`() = runTest {
        val file = File(tempDir, "shared-settings.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        val first = DownloadQueueSettingsStore(
            PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }),
        )
        first.setWorkerCount(3)
        first.setWifiOnly(false)
        firstScope.cancel()
        withTimeoutOrNull(2_000) { while (firstJob.children.any()) delay(10) }

        val second = newStore(file)

        assertThat(second.awaitReady().workerCount).isEqualTo(3)
        assertThat(second.awaitReady().wifiOnly).isFalse()
    }

    @Test
    fun `worker count clamps on write`() = runTest {
        val store = newStore()

        store.setWorkerCount(0)
        assertThat(store.currentSettings().workerCount).isEqualTo(1)

        store.setWorkerCount(99)
        assertThat(store.currentSettings().workerCount).isEqualTo(4)

        store.setWorkerCount(-7)
        assertThat(store.currentSettings().workerCount).isEqualTo(1)
    }

    @Test
    fun `a corrupt persisted worker count decodes clamped`() = runTest {
        val file = File(tempDir, "corrupt.preferences_pb")
        val raw = newDataStore(file)
        raw.edit {
            it[intPreferencesKey("downloads.worker_count")] = 99
        }
        val store = DownloadQueueSettingsStore(raw)

        assertThat(store.awaitReady().workerCount).isEqualTo(4)
    }

    @Test
    fun `readiness returns the persisted value rather than a default`() = runTest {
        val file = File(tempDir, "ready.preferences_pb")
        val writerJob = SupervisorJob()
        val writerScope = CoroutineScope(writerJob + Dispatchers.IO)
        DownloadQueueSettingsStore(
            PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file }),
        ).setWorkerCount(1)
        writerScope.cancel()
        withTimeoutOrNull(2_000) { while (writerJob.children.any()) delay(10) }

        val reopened = newStore(file)

        assertThat(reopened.awaitReady().workerCount).isEqualTo(1)
    }

    @Test
    fun `concurrent settings writes all land`() = runTest {
        val writes = AtomicInteger()
        val delegate = newDataStore(File(tempDir, "concurrent.preferences_pb"))
        val counting = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = delegate.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                writes.incrementAndGet()
                return delegate.updateData(transform)
            }
        }
        val store = DownloadQueueSettingsStore(counting)

        coroutineScope {
            repeat(20) { index ->
                launch(Dispatchers.IO) {
                    store.setWorkerCount(1 + index % 4)
                    store.setWifiOnly(index % 2 == 0)
                }
            }
        }

        val settings = store.currentSettings()
        assertThat(writes.get()).isEqualTo(40)
        assertThat(settings.workerCount).isIn(1..4)
        assertThat(store.rootState.first().pendingVolumeId).isNull()
    }

    @Test
    fun `root journal publishes begins commits and cleans up`() = runTest {
        val store = newStore()
        val target = OfflineRootSelection.Volume("volume-b")

        store.publishRoot(OfflineRootSelection.Volume("volume-a"), "volume-a", 2)
        var root = store.currentRootState()
        assertThat(root.selection).isEqualTo(OfflineRootSelection.Volume("volume-a"))
        assertThat(root.activeVolumeId).isEqualTo("volume-a")
        assertThat(root.generation).isEqualTo(2)

        store.beginRootMove(target, "volume-b", 3)
        root = store.currentRootState()
        assertThat(root.pendingVolumeId).isEqualTo("volume-b")
        assertThat(root.pendingSelection).isEqualTo(target)
        assertThat(root.pendingGeneration).isEqualTo(3)
        assertThat(root.activeVolumeId).isEqualTo("volume-a")

        store.commitRootMove(target, "volume-b", 3, "volume-a")
        root = store.currentRootState()
        assertThat(root.selection).isEqualTo(target)
        assertThat(root.activeVolumeId).isEqualTo("volume-b")
        assertThat(root.generation).isEqualTo(3)
        assertThat(root.pendingVolumeId).isNull()
        assertThat(root.cleanupVolumeId).isEqualTo("volume-a")

        store.finishRootCleanup()
        assertThat(store.currentRootState().cleanupVolumeId).isNull()
    }

    @Test
    fun `root rollback drops only the pending journal`() = runTest {
        val store = newStore()
        store.publishRoot(OfflineRootSelection.Auto, "external", 0)
        store.beginRootMove(OfflineRootSelection.Volume("target"), "target", 1)

        store.rollbackRootMove()

        val root = store.currentRootState()
        assertThat(root.pendingVolumeId).isNull()
        assertThat(root.activeVolumeId).isEqualTo("external")
        assertThat(root.generation).isEqualTo(0)
    }

    @Test
    fun `read failures never decode as unpaused auto defaults`() = runTest {
        val failing = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("read failed") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("write not expected")
        }
        val store = DownloadQueueSettingsStore(failing)

        assertThat(runCatching { store.currentRootState() }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(runCatching { store.isGloballyPaused() }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(runCatching { store.awaitReady() }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
    }

    @Test
    fun `the in-memory store keeps settings and root state`() = runTest {
        val store = DownloadQueueSettingsStore.inMemory()

        store.setWorkerCount(4)
        store.setWifiOnly(false)
        store.publishRoot(OfflineRootSelection.Auto, "external", 1)

        val settings = store.awaitReady()
        assertThat(settings.workerCount).isEqualTo(4)
        assertThat(settings.wifiOnly).isFalse()
        assertThat(store.currentRootState().activeVolumeId).isEqualTo("external")
    }
}
