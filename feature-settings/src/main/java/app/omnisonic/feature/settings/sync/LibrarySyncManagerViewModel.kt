package app.omnisonic.feature.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.sync.LibrarySyncControl
import app.omnisonic.core.data.sync.LibrarySyncProgress
import app.omnisonic.core.data.sync.LibrarySyncProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One provider row in the settings sync manager. */
data class LibrarySyncManagerEntry(
    val provider: LibrarySyncProvider,
    val live: LibrarySyncProgress?,
) {

    val isRunning: Boolean
        get() = live?.isRunning == true
}

data class LibrarySyncManagerUiState(
    val entries: List<LibrarySyncManagerEntry> = emptyList(),
    val loading: Boolean = true,
    val confirmingProviderId: Long? = null,
) {

    fun isRunning(providerId: Long): Boolean =
        entries.firstOrNull { it.provider.providerId == providerId }?.isRunning == true
}

/**
 * Settings sync manager (D-16): last recorded outcome per provider, the live
 * walk when one is running, and the two manual triggers. "Full resync" asks for
 * confirmation first; both triggers are no-ops while that provider's walk is
 * live (the launcher's run gate would drop them anyway).
 */
@HiltViewModel
class LibrarySyncManagerViewModel @Inject constructor(
    private val sync: LibrarySyncControl,
) : ViewModel() {

    private val _state = MutableStateFlow(LibrarySyncManagerUiState())
    val state: StateFlow<LibrarySyncManagerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var providers = sync.providers()
            _state.update { it.render(providers, sync.progress.value) }
            sync.progress.collect { progress ->
                if (progress == null || progress.phase.isTerminal) {
                    providers = sync.providers()
                }
                _state.update { it.render(providers, progress) }
            }
        }
    }

    /**
     * Re-resolves the provider rows whenever the settings screen enters
     * composition: this VM is scoped to the shell pager, so its init may run at
     * app launch before the first provider exists (4c defect).
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.render(sync.providers(), sync.progress.value) }
        }
    }

    fun syncNow(providerId: Long) {
        if (_state.value.isRunning(providerId)) return
        sync.startDiffSync(providerId)
    }

    fun requestFullResync(providerId: Long) {
        if (_state.value.isRunning(providerId)) return
        _state.update { it.copy(confirmingProviderId = providerId) }
    }

    fun dismissFullResync() {
        _state.update { it.copy(confirmingProviderId = null) }
    }

    fun confirmFullResync() {
        val providerId = _state.value.confirmingProviderId ?: return
        _state.update { it.copy(confirmingProviderId = null) }
        if (_state.value.isRunning(providerId)) return
        sync.startFullSync(providerId)
    }

    private fun LibrarySyncManagerUiState.render(
        providers: List<LibrarySyncProvider>,
        progress: LibrarySyncProgress?,
    ): LibrarySyncManagerUiState = copy(
        entries = providers
            .filter { it.supportsLibrarySync }
            .map { provider ->
                LibrarySyncManagerEntry(
                    provider = provider,
                    live = progress?.takeIf { it.providerId == provider.providerId },
                )
            },
        loading = false,
    )
}
