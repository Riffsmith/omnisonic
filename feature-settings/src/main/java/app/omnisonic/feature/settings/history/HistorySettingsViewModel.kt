package app.omnisonic.feature.settings.history

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.backup.SafBackupDocumentSink
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.aggregation.AlbumCountRule
import app.omnisonic.core.data.db.aggregation.PlayAggregateSettings
import app.omnisonic.core.data.db.dao.ProviderIdentityRow
import app.omnisonic.core.data.export.PlayEventCsvExporter
import app.omnisonic.core.data.history.HistoryPreferencesStore
import app.omnisonic.core.data.history.HistoryRecordingGate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class HistorySettingsUiState(
    val loading: Boolean = true,
    val playThresholdPercent: Int = HistoryPreferencesStore.DEFAULT_PLAY_THRESHOLD_PERCENT,
    val resumeMinPositionMs: Long = HistoryPreferencesStore.DEFAULT_RESUME_MIN_POSITION_MS,
    val pausedProviderIds: Set<Long> = emptySet(),
    val providers: List<ProviderIdentityRow> = emptyList(),
    val albumCountRule: AlbumCountRule = AlbumCountRule.AVERAGE,
    val includeSupportingArtists: Boolean = false,
)

sealed interface HistoryCsvExportState {
    data object Idle : HistoryCsvExportState
    data object Running : HistoryCsvExportState
    data class Done(val eventCount: Int) : HistoryCsvExportState
    data class Failed(val message: String) : HistoryCsvExportState
}

/**
 * Listen-history settings (Phase 6 task 9): play-count threshold, per-provider
 * recording pause, minimum resume position, the aggregate options and the CSV
 * export. Every write goes to [HistoryPreferencesStore] (or through the
 * recording gate, which persists the same key), so the recorder and the
 * aggregate holder pick the change up from their own reads of the store.
 */
@HiltViewModel
class HistorySettingsViewModel @Inject constructor(
    private val store: HistoryPreferencesStore,
    private val recordingGate: HistoryRecordingGate,
    private val aggregateSettings: PlayAggregateSettings,
    db: OmnisonicDatabase,
    private val csvExporter: PlayEventCsvExporter,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val providerIdentities = db.providerDao().observeIdentities()

    private val _state = MutableStateFlow(HistorySettingsUiState())
    val state: StateFlow<HistorySettingsUiState> = _state.asStateFlow()

    private val _exportState = MutableStateFlow<HistoryCsvExportState>(HistoryCsvExportState.Idle)
    val exportState: StateFlow<HistoryCsvExportState> = _exportState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(store.preferences, recordingGate.pausedProviders, providerIdentities) { prefs, paused, providers ->
                HistorySettingsUiState(
                    loading = false,
                    playThresholdPercent = prefs.playThresholdPercent,
                    resumeMinPositionMs = prefs.resumeMinPositionMs,
                    pausedProviderIds = paused,
                    providers = providers,
                    albumCountRule = prefs.albumCountRule,
                    includeSupportingArtists = prefs.includeSupportingArtists,
                )
            }.collect { _state.value = it }
        }
    }

    fun setPlayThresholdPercent(percent: Int) {
        viewModelScope.launch { store.setPlayThresholdPercent(percent) }
    }

    fun setResumeMinPositionMs(ms: Long) {
        viewModelScope.launch { store.setResumeMinPositionMs(ms) }
    }

    fun setProviderRecordingPaused(providerId: Long, paused: Boolean) {
        recordingGate.setPaused(providerId, paused)
    }

    fun setAlbumCountRule(rule: AlbumCountRule) {
        viewModelScope.launch {
            aggregateSettings.awaitReady()
            aggregateSettings.update(aggregateSettings.options().copy(albumCountRule = rule))
        }
    }

    fun setIncludeSupportingArtists(include: Boolean) {
        viewModelScope.launch {
            aggregateSettings.awaitReady()
            aggregateSettings.update(aggregateSettings.options().copy(includeSupportingArtists = include))
        }
    }

    /** Writes the whole event log as CSV into the picked SAF document. */
    fun exportCsv(documentUri: Uri) {
        viewModelScope.launch {
            _exportState.value = HistoryCsvExportState.Running
            _exportState.value = try {
                val count = csvExporter.writeCsv(
                    SafBackupDocumentSink(context.contentResolver, documentUri),
                )
                HistoryCsvExportState.Done(count)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                HistoryCsvExportState.Failed(failure.message ?: "export failed")
            }
        }
    }

    fun resetExportState() {
        _exportState.value = HistoryCsvExportState.Idle
    }
}
