package app.omnisonic.feature.settings.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.providers.ProviderProbeResult
import app.omnisonic.core.data.providers.ProviderSetupForm
import app.omnisonic.core.data.providers.ProviderSetupIssue
import app.omnisonic.core.data.providers.ProviderSetupOutcome
import app.omnisonic.core.data.providers.ProviderSetupRules
import app.omnisonic.core.data.providers.ProviderSetupService
import app.omnisonic.core.data.providers.ProviderSetupValidation
import app.omnisonic.feature.settings.shell.OnboardingSteps
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * First-connection state machine. Saving never trusts a stale test: a successful
 * probe for the exact current input is reused, otherwise the save path probes
 * first and only persists a reachable server (v1 completes setup before home).
 * Completion marks the onboarding step done and starts the initial library walk
 * off the view-model scope, so leaving the screen cannot cancel it.
 */
@HiltViewModel
class ProviderSetupViewModel @Inject constructor(
    private val service: ProviderSetupService,
    private val shellPreferences: ShellPreferencesStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ProviderSetupUiState())
    val state: StateFlow<ProviderSetupUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ProviderSetupEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ProviderSetupEvent> = _events.asSharedFlow()

    private var testedForm: ProviderSetupForm? = null

    fun onServerUrlChange(value: String) = edit {
        copy(
            serverUrl = value,
            issues = issues - ProviderSetupIssue.SERVER_URL_REQUIRED - ProviderSetupIssue.SERVER_URL_INVALID,
        )
    }

    fun onUsernameChange(value: String) = edit {
        copy(username = value, issues = issues - ProviderSetupIssue.USERNAME_REQUIRED)
    }

    fun onPasswordChange(value: String) = edit {
        copy(password = value, issues = issues - ProviderSetupIssue.PASSWORD_REQUIRED)
    }

    fun onSecondaryUrlChange(value: String) = edit {
        copy(secondaryUrl = value, issues = issues - ProviderSetupIssue.SECONDARY_URL_INVALID)
    }

    fun onUseSecondaryChange(enabled: Boolean) = edit {
        copy(
            useSecondary = enabled,
            secondaryUrl = if (enabled) secondaryUrl else "",
            issues = issues - ProviderSetupIssue.SECONDARY_URL_INVALID,
        )
    }

    fun testConnection() {
        if (_state.value.busy != ProviderSetupBusy.NONE) return
        val form = currentForm()
        if (!acceptInput(form)) return
        _state.update { it.copy(busy = ProviderSetupBusy.TESTING, issues = emptySet()) }
        viewModelScope.launch {
            val result = service.probe(form)
            testedForm = form.takeIf { result is ProviderProbeResult.Success }
            _state.update {
                it.copy(busy = ProviderSetupBusy.NONE, connectionStatus = result.toStatus())
            }
        }
    }

    fun save() {
        if (_state.value.busy != ProviderSetupBusy.NONE) return
        val form = currentForm()
        if (!acceptInput(form)) return

        val reusingTest = testedForm == form
        _state.update {
            it.copy(
                busy = if (reusingTest) ProviderSetupBusy.SAVING else ProviderSetupBusy.TESTING,
                issues = emptySet(),
            )
        }
        viewModelScope.launch {
            if (!reusingTest) {
                val probe = service.probe(form)
                if (probe !is ProviderProbeResult.Success) {
                    _state.update {
                        it.copy(busy = ProviderSetupBusy.NONE, connectionStatus = probe.toStatus())
                    }
                    return@launch
                }
                testedForm = form
                _state.update {
                    it.copy(busy = ProviderSetupBusy.SAVING, connectionStatus = ProviderConnectionStatus.OK)
                }
            }

            val outcome = try {
                service.save(form)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _state.update {
                    it.copy(busy = ProviderSetupBusy.NONE, connectionStatus = ProviderConnectionStatus.UNEXPECTED)
                }
                return@launch
            }

            when (outcome) {
                is ProviderSetupOutcome.Saved -> {
                    shellPreferences.completeOnboardingStep(OnboardingSteps.PROVIDER_SETUP)
                    service.startInitialSync(outcome.providerId)
                    _state.value = ProviderSetupUiState()
                    testedForm = null
                    _events.tryEmit(ProviderSetupEvent.Completed)
                }

                is ProviderSetupOutcome.Invalid -> _state.update {
                    it.copy(busy = ProviderSetupBusy.NONE, issues = outcome.issues.toSet())
                }
            }
        }
    }

    private fun currentForm(): ProviderSetupForm = _state.value.let { state ->
        ProviderSetupForm(
            serverUrl = state.serverUrl,
            username = state.username,
            password = state.password,
            secondaryUrl = if (state.useSecondary) state.secondaryUrl else "",
        )
    }

    /** Validates once for both the test and save paths; issues render under the fields. */
    private fun acceptInput(form: ProviderSetupForm): Boolean =
        when (val validation = ProviderSetupRules.validate(form)) {
            is ProviderSetupValidation.Valid -> true
            is ProviderSetupValidation.Invalid -> {
                testedForm = null
                _state.update {
                    it.copy(
                        issues = validation.issues.toSet(),
                        connectionStatus = ProviderConnectionStatus.NONE,
                    )
                }
                false
            }
        }

    private fun edit(block: ProviderSetupUiState.() -> ProviderSetupUiState) {
        if (_state.value.busy != ProviderSetupBusy.NONE) return
        testedForm = null
        _state.update { it.block().copy(connectionStatus = ProviderConnectionStatus.NONE) }
    }

    private fun ProviderProbeResult.toStatus(): ProviderConnectionStatus = when (this) {
        is ProviderProbeResult.Success -> ProviderConnectionStatus.OK
        is ProviderProbeResult.Failure -> when (reason) {
            ProviderProbeResult.FailureReason.INVALID_INPUT -> ProviderConnectionStatus.INVALID_INPUT
            ProviderProbeResult.FailureReason.NETWORK -> ProviderConnectionStatus.NETWORK_ERROR
            ProviderProbeResult.FailureReason.AUTHENTICATION -> ProviderConnectionStatus.AUTH_ERROR
            ProviderProbeResult.FailureReason.SERVER_ERROR -> ProviderConnectionStatus.SERVER_ERROR
            ProviderProbeResult.FailureReason.UNSUPPORTED -> ProviderConnectionStatus.UNSUPPORTED
            ProviderProbeResult.FailureReason.UNEXPECTED -> ProviderConnectionStatus.UNEXPECTED
        }
    }
}
