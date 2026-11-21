package app.omnisonic.feature.settings.downloads

import app.omnisonic.core.data.downloads.DownloadRootState
import app.omnisonic.core.data.downloads.DownloadSettings
import app.omnisonic.core.data.downloads.DownloadSettingsRules
import app.omnisonic.core.data.downloads.OfflineRoot
import app.omnisonic.core.data.downloads.OfflineRootChangeFailure
import app.omnisonic.core.data.downloads.OfflineRootChangeResult
import app.omnisonic.core.data.downloads.OfflineRootSelection
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var port: FakeDownloadSettingsPort
    private lateinit var viewModel: DownloadSettingsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        port = FakeDownloadSettingsPort()
        viewModel = DownloadSettingsViewModel(port)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `persisted settings and roots build the initial state`() = runTest(dispatcher) {
        port.settingsFlow.value = DownloadSettings(workerCount = 3, wifiOnly = false)
        port.rootFlow.value = DownloadRootState(
            selection = OfflineRootSelection.Volume("external"),
            activeVolumeId = "external",
        )
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.workerCount).isEqualTo(3)
        assertThat(state.wifiOnly).isFalse()
        assertThat(state.selection).isEqualTo(OfflineRootSelection.Volume("external"))
        assertThat(state.roots.map { it.volumeId }).containsExactly("internal", "external")
        assertThat(state.roots.map { it.internal }).containsExactly(true, false)
        assertThat(state.rootUnavailable).isFalse()
    }

    @Test
    fun `worker count and Wi-Fi writes are clamped before reaching storage`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.setWorkerCount(9)
        viewModel.setWorkerCount(0)
        viewModel.setWifiOnly(false)
        advanceUntilIdle()

        assertThat(port.workerWrites)
            .containsExactly(DownloadSettingsRules.MAX_WORKER_COUNT, DownloadSettingsRules.MIN_WORKER_COUNT)
        assertThat(port.wifiWrites).containsExactly(false)
        assertThat(viewModel.state.value.workerCount).isEqualTo(DownloadSettingsRules.MIN_WORKER_COUNT)
        assertThat(viewModel.state.value.wifiOnly).isFalse()
    }

    @Test
    fun `a root move requires confirmation and is single flight`() = runTest(dispatcher) {
        port.rootFlow.value = DownloadRootState(
            selection = OfflineRootSelection.Volume("internal"),
            activeVolumeId = "internal",
        )
        port.changeGate = CompletableDeferred()
        advanceUntilIdle()

        viewModel.requestRootChange(OfflineRootSelection.Volume("external"))
        advanceUntilIdle()

        assertThat(port.changeCalls).isEmpty()
        assertThat(viewModel.state.value.move)
            .isEqualTo(DownloadRootMoveUi.Confirming(OfflineRootSelection.Volume("external")))

        viewModel.confirmRootChange()
        viewModel.confirmRootChange()
        advanceUntilIdle()

        assertThat(port.changeCalls).hasSize(1)
        assertThat(viewModel.state.value.move).isEqualTo(DownloadRootMoveUi.Running)

        port.changeResult = OfflineRootChangeResult.Completed(
            "external",
            movedFiles = 4,
            generation = 2,
            cleanupPending = true,
        )
        port.changeGate?.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.state.value.move).isEqualTo(DownloadRootMoveUi.Done(4))
    }

    @Test
    fun `an already active root completes without files and cancelling never calls storage`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.requestRootChange(OfflineRootSelection.Volume("external"))
        viewModel.cancelRootChange()
        advanceUntilIdle()

        assertThat(viewModel.state.value.move).isEqualTo(DownloadRootMoveUi.Idle)
        assertThat(port.changeCalls).isEmpty()

        viewModel.requestRootChange(OfflineRootSelection.Volume("external"))
        port.changeResult = OfflineRootChangeResult.AlreadyActive
        viewModel.confirmRootChange()
        advanceUntilIdle()

        assertThat(viewModel.state.value.move).isEqualTo(DownloadRootMoveUi.Done(0))
    }

    @Test
    fun `selecting the current root is a no-op and a failed move renders its reason`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.requestRootChange(OfflineRootSelection.Auto)
        assertThat(viewModel.state.value.move).isEqualTo(DownloadRootMoveUi.Idle)

        viewModel.requestRootChange(OfflineRootSelection.Volume("external"))
        port.changeResult = OfflineRootChangeResult.Failed(
            OfflineRootChangeFailure.LOW_SPACE,
            detail = "needed 10 GB",
        )
        viewModel.confirmRootChange()
        advanceUntilIdle()

        assertThat(viewModel.state.value.move)
            .isEqualTo(DownloadRootMoveUi.Failed(OfflineRootChangeFailure.LOW_SPACE))

        viewModel.dismissMoveResult()
        advanceUntilIdle()
        assertThat(viewModel.state.value.move).isEqualTo(DownloadRootMoveUi.Idle)
    }

    @Test
    fun `a thrown move is mapped to commit failure and recovery stays available`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.requestRootChange(OfflineRootSelection.Volume("external"))
        port.throwOnChange = true
        viewModel.confirmRootChange()
        advanceUntilIdle()

        assertThat(viewModel.state.value.move)
            .isEqualTo(DownloadRootMoveUi.Failed(OfflineRootChangeFailure.COMMIT_FAILED))

        port.throwOnChange = false
        viewModel.dismissMoveResult()
        viewModel.requestRootChange(OfflineRootSelection.Volume("external"))
        viewModel.confirmRootChange()
        advanceUntilIdle()

        assertThat(viewModel.state.value.move).isEqualTo(DownloadRootMoveUi.Done(1))
    }

    @Test
    fun `a pending journal root is surfaced as unavailable`() = runTest(dispatcher) {
        port.rootFlow.value = DownloadRootState(
            selection = OfflineRootSelection.Volume("external"),
            activeVolumeId = "internal",
            pendingVolumeId = "external",
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.rootUnavailable).isTrue()
    }

    private class FakeDownloadSettingsPort : DownloadSettingsPort {

        val settingsFlow = MutableStateFlow(DownloadSettings())
        val rootFlow = MutableStateFlow(DownloadRootState())
        val workerWrites = mutableListOf<Int>()
        val wifiWrites = mutableListOf<Boolean>()
        val changeCalls = mutableListOf<OfflineRootSelection>()
        var changeGate: CompletableDeferred<Unit>? = null
        var changeResult: OfflineRootChangeResult =
            OfflineRootChangeResult.Completed("external", movedFiles = 1, generation = 2)
        var throwOnChange = false

        override val settings: Flow<DownloadSettings> = settingsFlow
        override val rootState: Flow<DownloadRootState> = rootFlow

        override fun availableRoots(): List<OfflineRoot> = listOf(
            OfflineRoot(OfflineRoot.INTERNAL_VOLUME, File("/internal")),
            OfflineRoot("external", File("/external")),
        )

        override suspend fun setWorkerCount(count: Int) {
            workerWrites += count
            settingsFlow.update { it.copy(workerCount = DownloadSettingsRules.clampWorkerCount(count)) }
        }

        override suspend fun setWifiOnly(enabled: Boolean) {
            wifiWrites += enabled
            settingsFlow.update { it.copy(wifiOnly = enabled) }
        }

        override suspend fun changeRoot(selection: OfflineRootSelection): OfflineRootChangeResult {
            changeCalls += selection
            changeGate?.await()
            if (throwOnChange) error("storage failed")
            if (changeResult is OfflineRootChangeResult.Completed) {
                val completed = changeResult as OfflineRootChangeResult.Completed
                rootFlow.update {
                    it.copy(
                        selection = selection,
                        activeVolumeId = completed.activeVolumeId,
                        generation = completed.generation,
                        pendingVolumeId = null,
                    )
                }
            }
            return changeResult
        }
    }
}
