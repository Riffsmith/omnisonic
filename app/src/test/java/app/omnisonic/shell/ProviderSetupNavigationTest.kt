package app.omnisonic.shell

import androidx.compose.ui.test.hasSetTextAction
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
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.provider.api.ProviderCapabilities
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * First-run gate (D-12): the shell starts on provider setup and only a saved,
 * reachable server moves it to home. The setup form itself is covered in
 * `:feature-settings`; here the contract under test is the route hand-off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ProviderSetupNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun savingAReachableServerLeavesSetupForHome() {
        val service = mockk<ProviderSetupService>(relaxed = true)
        coEvery { service.probe(any()) } returns ProviderProbeResult.Success(ProviderCapabilities())
        coEvery { service.save(any()) } returns ProviderSetupOutcome.Saved(1L)

        lateinit var nav: NavHostController
        // Onboarding flag persistence is covered in :feature-settings; a relaxed
        // store keeps this route test independent of DataStore's IO dispatcher.
        val setupViewModel = testProviderSetupViewModel(
            service = service,
            shellPreferences = mockk(relaxed = true),
        )
        composeRule.setContent {
            OmnisonicTheme {
                OmnisonicShell(
                    state = onboardingShellState(),
                    settingsViewModel = ShellSettingsViewModel(
                        ShellPreferencesStore.createAt(
                            File("/tmp/opencode", "setup-nav-${System.nanoTime()}.preferences_pb"),
                        ),
                    ),
                    providerSetupViewModel = setupViewModel,
                    browseViewModel = testBrowseViewModel(),
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

        composeRule.waitUntil(timeoutMillis = 5_000) {
            nav.currentDestination?.route == ShellRoutes.HOME
        }
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.HOME)
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
