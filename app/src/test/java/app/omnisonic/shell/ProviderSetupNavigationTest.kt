package app.omnisonic.shell

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.omnisonic.core.data.providers.ProviderProbeResult
import app.omnisonic.core.data.providers.ProviderSetupOutcome
import app.omnisonic.core.data.providers.ProviderSetupService
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.OnboardingGate
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.provider.api.ProviderCapabilities
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * First-run gate (D-12 extended by D-16): the shell starts on provider setup,
 * a saved reachable server hands over to the library-sync step, and Continue
 * lands on home. The later settings path keeps the old behavior (setup → home)
 * because the sync step belongs to onboarding only.
 *
 * Route waits poll with `waitForIdle` + a short sleep: the view-model
 * coroutines ride the Robolectric main looper, which `waitUntil` does not pump
 * reliably in this setup (the settled-view-model pattern from T2b).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ProviderSetupNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstRunWalksSetupThenSyncThenHome() {
        val service = mockk<ProviderSetupService>(relaxed = true)
        coEvery { service.probe(any()) } returns ProviderProbeResult.Success(ProviderCapabilities())
        coEvery { service.save(any()) } returns ProviderSetupOutcome.Saved(1L)
        val syncControl = FakeLibrarySyncControl(
            initialProgress = syncCompletedProgress(),
            initialProviders = listOf(syncDoneProvider()),
        )

        lateinit var nav: NavHostController
        val stepViewModel = testLibrarySyncStepViewModel(syncControl)
        // Onboarding flag persistence is covered in :feature-settings; a relaxed
        // store keeps this route test independent of DataStore's IO dispatcher.
        composeRule.setContent {
            OmnisonicTheme {
                OmnisonicShell(
                    state = onboardingShellState(),
                    settingsViewModel = ShellSettingsViewModel(tempShellStore("setup-nav")),
                    providerSetupViewModel = testProviderSetupViewModel(service = service),
                    browseViewModel = testBrowseViewModel(),
                    syncStepViewModelProvider = { stepViewModel },
                    syncManagerViewModel = testLibrarySyncManagerViewModel(),
                    searchViewModel = testSearchViewModel(),
                    navController = rememberNavController().also { nav = it },
                )
            }
        }
        composeRule.waitForIdle()

        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("https://music.example.com")
        fields[1].performTextInput("admin")
        fields[2].performTextInput("hunter2")
        composeRule.onNodeWithText("Save and continue").performScrollTo().performClick()

        awaitRoute(nav, ShellRoutes.LIBRARY_SYNC)
        composeRule.onNodeWithText("Continue").performClick()
        awaitRoute(nav, ShellRoutes.HOME)
    }

    /**
     * 4d regression: the step view model resolves the provider row in its
     * `init`, so it must be created when the destination opens — not at app
     * launch (a fresh install had no provider row yet and the screen stayed
     * cue-less until relaunch).
     */
    @Test
    fun syncStepViewModelIsOnlyCreatedWhenTheRouteOpens() {
        var providerInvocations = 0
        val stepViewModel = testLibrarySyncStepViewModel()
        lateinit var nav: NavHostController
        composeRule.setContent {
            OmnisonicTheme {
                OmnisonicShell(
                    state = completedShellState(),
                    settingsViewModel = ShellSettingsViewModel(tempShellStore("step-scope")),
                    providerSetupViewModel = testProviderSetupViewModel(),
                    browseViewModel = testBrowseViewModel(),
                    syncStepViewModelProvider = {
                        providerInvocations += 1
                        stepViewModel
                    },
                    syncManagerViewModel = testLibrarySyncManagerViewModel(),
                    searchViewModel = testSearchViewModel(),
                    navController = rememberNavController().also { nav = it },
                )
            }
        }
        composeRule.waitForIdle()
        assertThat(providerInvocations).isEqualTo(0)

        composeRule.runOnIdle { nav.navigate(ShellRoutes.LIBRARY_SYNC) { launchSingleTop = true } }
        composeRule.waitForIdle()

        assertThat(providerInvocations).isAtLeast(1)
        composeRule.onNodeWithText("Continue").assertExists()
    }

    @Test
    fun laterSetupFromSettingsReturnsHomeAfterSave() {
        val service = mockk<ProviderSetupService>(relaxed = true)
        coEvery { service.probe(any()) } returns ProviderProbeResult.Success(ProviderCapabilities())
        coEvery { service.save(any()) } returns ProviderSetupOutcome.Saved(2L)

        lateinit var nav: NavHostController
        val stepViewModel = testLibrarySyncStepViewModel()
        composeRule.setContent {
            OmnisonicTheme {
                OmnisonicShell(
                    state = completedShellState(),
                    settingsViewModel = ShellSettingsViewModel(tempShellStore("setup-settings-path")),
                    providerSetupViewModel = testProviderSetupViewModel(service = service),
                    browseViewModel = testBrowseViewModel(),
                    syncStepViewModelProvider = { stepViewModel },
                    syncManagerViewModel = testLibrarySyncManagerViewModel(),
                    searchViewModel = testSearchViewModel(),
                    navController = rememberNavController().also { nav = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(tabItem("Settings")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Manage providers").performClick()
        composeRule.waitForIdle()
        awaitRoute(nav, ShellRoutes.PROVIDER_SETUP)

        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("https://music.example.com")
        fields[1].performTextInput("admin")
        fields[2].performTextInput("hunter2")
        composeRule.onNodeWithText("Save and continue").performScrollTo().performClick()

        awaitRoute(nav, ShellRoutes.HOME)
    }

    private fun awaitRoute(nav: NavHostController, route: String, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            val current = composeRule.runOnIdle { nav.currentDestination?.route }
            if (current == route) return
            Thread.sleep(100)
        }
        val final = composeRule.runOnIdle { nav.currentDestination?.route }
        assertThat(final).isEqualTo(route)
    }
}

private fun onboardingShellState(): ShellUiState = ShellUiState(
    tabs = MainTab.DEFAULT,
    startRoute = ShellRoutes.PROVIDER_SETUP,
    onboardingComplete = false,
    hasProvider = false,
    portraitNavOverride = null,
    landscapeNavOverride = null,
)

private fun completedShellState(): ShellUiState = ShellUiState(
    tabs = MainTab.DEFAULT,
    startRoute = OnboardingGate.HOME_ROUTE,
    onboardingComplete = true,
    hasProvider = true,
    portraitNavOverride = null,
    landscapeNavOverride = null,
)

/** Bottom-bar items carry Role.Tab; the settings screen's tab rows do not. */
private fun tabItem(label: String): SemanticsMatcher = SemanticsMatcher("tab '$label'") { node ->
    node.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
}
