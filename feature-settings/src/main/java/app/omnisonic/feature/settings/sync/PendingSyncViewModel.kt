package app.omnisonic.feature.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.sync.OutboxPendingProvider
import app.omnisonic.core.data.sync.OutboxPendingRepository
import app.omnisonic.core.data.sync.OutboxSyncControl
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PendingSyncUiState(
    val providers: List<OutboxPendingProvider> = emptyList(),
    val totalPending: Int = 0,
    val draining: Boolean = false,
    val loading: Boolean = true,
)

/**
 * Pending-sync view model: queued user-data changes per provider, the newest
 * recorded error and the manual retry. Counts come from an aggregate read model
 * and refresh through Room invalidation, so no explicit reload is needed.
 */
@HiltViewModel
class PendingSyncViewModel @Inject constructor(
    pending: OutboxPendingRepository,
    private val outboxSync: OutboxSyncControl,
) : ViewModel() {

    private val _state = MutableStateFlow(PendingSyncUiState())
    val state: StateFlow<PendingSyncUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(pending.providers, outboxSync.state) { providers, sync ->
                PendingSyncUiState(
                    providers = providers,
                    totalPending = providers.sumOf { it.pendingCount },
                    draining = sync.running,
                    loading = false,
                )
            }.collect { _state.value = it }
        }
    }

    fun retryNow() {
        outboxSync.retryNow()
    }
}
