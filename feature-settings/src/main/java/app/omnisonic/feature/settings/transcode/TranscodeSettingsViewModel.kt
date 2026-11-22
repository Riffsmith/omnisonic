package app.omnisonic.feature.settings.transcode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.transcode.TranscodeRendererId
import app.omnisonic.core.data.transcode.TranscodeSettings
import app.omnisonic.core.data.transcode.TranscodeSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TranscodeSettingsUiState(
    val loading: Boolean = true,
    val vpnAsCellular: Boolean = true,
    val meteredAsCellular: Boolean = true,
)

/**
 * The D-60 per-renderer network toggles (`vpnAsCellular`/`meteredAsCellular`)
 * for the local renderer. Reads stream from [TranscodeSettingsStore]; every
 * toggle rewrites the whole renderer object atomically, serialized through a
 * mutex so rapid toggles cannot lose an update (D-61). The playback watcher
 * picks the change up from the same store, so the effective profile changes
 * without any coupling to this view model.
 */
@HiltViewModel
class TranscodeSettingsViewModel @Inject constructor(
    private val store: TranscodeSettingsStore,
) : ViewModel() {

    private val writeMutex = Mutex()

    val state: StateFlow<TranscodeSettingsUiState> = store
        .settingsFor(TranscodeRendererId.Local)
        .map { settings ->
            TranscodeSettingsUiState(
                loading = false,
                vpnAsCellular = settings.vpnAsCellular,
                meteredAsCellular = settings.meteredAsCellular,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TranscodeSettingsUiState())

    fun setVpnAsCellular(enabled: Boolean) = update { it.copy(vpnAsCellular = enabled) }

    fun setMeteredAsCellular(enabled: Boolean) = update { it.copy(meteredAsCellular = enabled) }

    private fun update(transform: (TranscodeSettings) -> TranscodeSettings) {
        viewModelScope.launch {
            try {
                writeMutex.withLock {
                    val current = store.current(TranscodeRendererId.Local)
                    store.setSettings(TranscodeRendererId.Local, transform(current))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The persisted value stays authoritative; the flow re-renders it.
            }
        }
    }
}
