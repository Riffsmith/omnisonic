package app.omnisonic.feature.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.sync.LibrarySyncControl
import app.omnisonic.core.data.sync.LibrarySyncPhase
import app.omnisonic.core.data.sync.LibrarySyncProgress
import app.omnisonic.feature.settings.shell.OnboardingSteps
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * First-run sync step (D-16). Owns no walk of its own: it adopts the provider's
 * live progress, restarts a failed walk, and completes the onboarding step when
 * the user moves on. Leaving the screen never cancels the walk — the launcher
 * runs it on a process-scoped coroutine scope.
 */
data class LibrarySyncStepUiState(
    val progress: LibrarySyncProgress? = null,
    val providerId: Long? = null,
    val recordedComplete: Boolean = false,
    val loaded: Boolean = false,
    val continuing: Boolean = false,
) {

    val phase: LibrarySyncPhase?
        get() = progress?.phase

    val isRunning: Boolean
        get() = progress?.isRunning == true

    val isComplete: Boolean
        get() = !isRunning &&
            (phase == LibrarySyncPhase.DONE || (phase == null && recordedComplete))

    val isFailed: Boolean
        get() = progress?.phase.let { it != null && it.isTerminal && it != LibrarySyncPhase.DONE }

    /** Never a dead end: a provider-less install can leave the step untouched. */
    val canContinue: Boolean
        get() = loaded && (isComplete || isFailed || providerId == null)
}

sealed interface LibrarySyncStepEvent {
    data object Completed : LibrarySyncStepEvent
}

@HiltViewModel
class LibrarySyncStepViewModel @Inject constructor(
    private val sync: LibrarySyncControl,
    private val shellPreferences: ShellPreferencesStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LibrarySyncStepUiState())
    val state: StateFlow<LibrarySyncStepUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<LibrarySyncStepEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<LibrarySyncStepEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val providers = sync.providers()
            val active = providers.firstOrNull { it.supportsLibrarySync && it.lastFullSyncAt == null }
                ?: providers.firstOrNull { it.supportsLibrarySync }
            _state.update {
                it.copy(
                    loaded = true,
                    providerId = active?.providerId,
                    recordedComplete = active?.lastFullSyncAt != null,
                )
            }
            if (active != null && active.lastFullSyncAt == null && sync.progress.value?.isRunning != true) {
                sync.startFullSync(active.providerId)
            }
            sync.progress.collect { progress ->
                if (progress != null && progress.providerId != _state.value.providerId) return@collect
                _state.update { it.copy(progress = progress) }
            }
        }
    }

    /** Restarts a failed or blocked walk; the launcher's run gate ignores duplicates. */
    fun retry() {
        val providerId = _state.value.providerId ?: return
        sync.startFullSync(providerId)
    }

    fun continueIntoApp() {
        if (_state.value.continuing) return
        _state.update { it.copy(continuing = true) }
        viewModelScope.launch {
            shellPreferences.completeOnboardingStep(OnboardingSteps.LIBRARY_SYNC)
            _events.tryEmit(LibrarySyncStepEvent.Completed)
        }
    }
}
