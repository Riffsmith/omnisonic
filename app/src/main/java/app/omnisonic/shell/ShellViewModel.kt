package app.omnisonic.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.OnboardingGate
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.style.NavigationLayout
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

data class ShellUiState(
    val tabs: List<MainTab>,
    val startRoute: String,
    val onboardingComplete: Boolean,
    val hasProvider: Boolean,
    val portraitNavOverride: NavigationLayout?,
    val landscapeNavOverride: NavigationLayout?,
)

@HiltViewModel
class ShellViewModel @Inject constructor(
    shellPreferences: ShellPreferencesStore,
    database: OmnisonicDatabase,
) : ViewModel() {

    private val providerCount = flow { emit(database.providerDao().count()) }

    val state: StateFlow<ShellUiState?> = combine(
        shellPreferences.preferences,
        providerCount,
    ) { prefs, count ->
        ShellUiState(
            tabs = prefs.visibleTabs,
            startRoute = OnboardingGate.startRoute(prefs),
            onboardingComplete = prefs.onboardingComplete,
            hasProvider = count > 0,
            portraitNavOverride = prefs.portraitNavOverride,
            landscapeNavOverride = prefs.landscapeNavOverride,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
