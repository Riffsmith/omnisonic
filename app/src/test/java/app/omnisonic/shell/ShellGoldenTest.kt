package app.omnisonic.shell

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.ObservableStyleTokenSource
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Before
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
 * (R-25). The theme comes from the production source (D-105), so this golden
 * proves what a fresh install resolves rather than the no-source fallback.
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellGoldenTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeRule = createComposeRule()

    @Inject
    lateinit var styleTokenSource: ObservableStyleTokenSource

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun shellPortraitHomeEmptyState() {
        val settingsViewModel = ShellSettingsViewModel(
            ShellPreferencesStore.createAt(File("/tmp/opencode", "golden-${System.nanoTime()}.preferences_pb")),
        )
        val providerSetupViewModel = testProviderSetupViewModel()
        val stepViewModel = testLibrarySyncStepViewModel()
        composeRule.setContent {
            OmnisonicTheme(source = styleTokenSource) {
                OmnisonicShell(
                    state = fixedShellState(hasProvider = false),
                    settingsViewModel = settingsViewModel,
                    historySettingsViewModel = testHistorySettingsViewModel(),
                    providerSetupViewModel = providerSetupViewModel,
                    browseViewModel = testBrowseViewModel(),
                    syncStepViewModelProvider = { stepViewModel },
                    syncManagerViewModel = testLibrarySyncManagerViewModel(),
                    pendingSyncViewModel = testPendingSyncViewModel(),
                    searchViewModel = testSearchViewModel(),
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
