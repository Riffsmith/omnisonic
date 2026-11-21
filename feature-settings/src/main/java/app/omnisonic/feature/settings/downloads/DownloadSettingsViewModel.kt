package app.omnisonic.feature.settings.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.downloads.DownloadSettingsRules
import app.omnisonic.core.data.downloads.OfflineRoot
import app.omnisonic.core.data.downloads.OfflineRootChangeFailure
import app.omnisonic.core.data.downloads.OfflineRootChangeResult
import app.omnisonic.core.data.downloads.OfflineRootSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadRootOptionUi(
    val volumeId: String,
    val internal: Boolean,
)

/** Root-move presentation; a confirmation always precedes a destructive move. */
sealed interface DownloadRootMoveUi {
    data object Idle : DownloadRootMoveUi
    data class Confirming(val target: OfflineRootSelection) : DownloadRootMoveUi
    data object Running : DownloadRootMoveUi
    data class Done(val movedFiles: Int) : DownloadRootMoveUi
    data class Failed(val reason: OfflineRootChangeFailure) : DownloadRootMoveUi
}

data class DownloadSettingsUiState(
    val loading: Boolean = true,
    val workerCount: Int = DownloadSettingsRules.DEFAULT_WORKER_COUNT,
    val wifiOnly: Boolean = DownloadSettingsRules.DEFAULT_WIFI_ONLY,
    val activeVolumeId: String? = null,
    val selection: OfflineRootSelection = OfflineRootSelection.Auto,
    val roots: List<DownloadRootOptionUi> = emptyList(),
    val move: DownloadRootMoveUi = DownloadRootMoveUi.Idle,
    val rootUnavailable: Boolean = false,
)

/**
 * Download settings (task 9d). Worker count is clamped by the persisted store,
 * Wi-Fi-only is the global gate, and a root change is only sent after the user
 * confirms; the result is rendered from the storage journal outcome rather than
 * assumed. The store/storage own all persistence and recovery.
 */
@HiltViewModel
class DownloadSettingsViewModel @Inject constructor(
    private val port: DownloadSettingsPort,
) : ViewModel() {

    private val roots = MutableStateFlow<List<DownloadRootOptionUi>>(emptyList())
    private val move = MutableStateFlow<DownloadRootMoveUi>(DownloadRootMoveUi.Idle)
    private val moving = AtomicBoolean(false)

    val state: StateFlow<DownloadSettingsUiState> = combine(
        port.settings,
        port.rootState,
        roots,
        move,
    ) { settings, rootState, roots, move ->
        DownloadSettingsUiState(
            loading = false,
            workerCount = settings.workerCount,
            wifiOnly = settings.wifiOnly,
            activeVolumeId = rootState.activeVolumeId,
            selection = rootState.selection,
            roots = roots,
            move = move,
            rootUnavailable = rootState.pendingVolumeId != null ||
                (rootState.activeVolumeId != null && roots.none { it.volumeId == rootState.activeVolumeId }),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadSettingsUiState())

    init {
        roots.value = port.availableRoots().map {
            DownloadRootOptionUi(it.volumeId, it.volumeId == OfflineRoot.INTERNAL_VOLUME)
        }
    }

    fun setWorkerCount(count: Int) {
        viewModelScope.launch {
            try {
                port.setWorkerCount(DownloadSettingsRules.clampWorkerCount(count))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The persisted value stays authoritative; the flow re-renders it.
            }
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            try {
                port.setWifiOnly(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // See setWorkerCount.
            }
        }
    }

    fun requestRootChange(selection: OfflineRootSelection) {
        if (selection == state.value.selection && move.value !is DownloadRootMoveUi.Failed) return
        move.value = DownloadRootMoveUi.Confirming(selection)
    }

    fun cancelRootChange() {
        if (move.value is DownloadRootMoveUi.Confirming) move.value = DownloadRootMoveUi.Idle
    }

    fun confirmRootChange() {
        val pending = move.value as? DownloadRootMoveUi.Confirming ?: return
        if (!moving.compareAndSet(false, true)) return
        move.value = DownloadRootMoveUi.Running
        viewModelScope.launch {
            try {
                move.value = when (val result = port.changeRoot(pending.target)) {
                    is OfflineRootChangeResult.Completed ->
                        DownloadRootMoveUi.Done(result.movedFiles)
                    OfflineRootChangeResult.AlreadyActive -> DownloadRootMoveUi.Done(0)
                    is OfflineRootChangeResult.Failed ->
                        DownloadRootMoveUi.Failed(result.reason)
                    OfflineRootChangeResult.NoChange -> DownloadRootMoveUi.Idle
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                move.value = DownloadRootMoveUi.Failed(OfflineRootChangeFailure.COMMIT_FAILED)
            } finally {
                moving.set(false)
            }
        }
    }

    fun dismissMoveResult() {
        if (move.value !is DownloadRootMoveUi.Confirming) move.value = DownloadRootMoveUi.Idle
    }
}
