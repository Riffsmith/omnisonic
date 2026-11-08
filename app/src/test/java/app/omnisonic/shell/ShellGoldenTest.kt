package app.omnisonic.shell

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.style.OmnisonicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * First shell golden (D-14): portrait bottom bar + home empty state (fresh
 * install). Regenerate deliberately; never from a failing run
 * (R-25).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellGoldenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shellPortraitHomeEmptyState() {
        val settingsViewModel = ShellSettingsViewModel(
            ShellPreferencesStore.createAt(File("/tmp/opencode", "golden-${System.nanoTime()}.preferences_pb")),
        )
        val providerSetupViewModel = testProviderSetupViewModel()
        composeRule.setContent {
            OmnisonicTheme {
                OmnisonicShell(
                    state = fixedShellState(hasProvider = false),
                    settingsViewModel = settingsViewModel,
                    providerSetupViewModel = providerSetupViewModel,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onRoot().captureRoboImage("src/test/roborazzi/shell_home_portrait.png")
    }
}

private fun fixedShellState(hasProvider: Boolean): ShellUiState = ShellUiState(
    tabs = MainTab.DEFAULT,
    startRoute = ShellRoutes.HOME,
    onboardingComplete = true,
    hasProvider = hasProvider,
    portraitNavOverride = null,
    landscapeNavOverride = null,
)
