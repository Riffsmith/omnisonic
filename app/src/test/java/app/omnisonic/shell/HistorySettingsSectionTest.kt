package app.omnisonic.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.feature.settings.ui.ShellSettingsScreen
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistorySettingsSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `history section shows the persisted defaults and the export entry point`() {
        composeRule.setContent {
            OmnisonicTheme {
                ShellSettingsScreen(
                    appVersion = "1.0.0",
                    onOpenProviders = {},
                    syncViewModel = testLibrarySyncManagerViewModel(),
                    pendingSyncViewModel = testPendingSyncViewModel(),
                    viewModel = ShellSettingsViewModel(
                        ShellPreferencesStore.createAt(
                            File("/tmp/opencode", "history-defaults-${System.nanoTime()}.preferences_pb"),
                        ),
                    ),
                    historySettingsViewModel = testHistorySettingsViewModel(),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Listen history").performScrollTo().assertExists()
        composeRule.onNodeWithText("Count a play after").performScrollTo().assertExists()
        composeRule.onNodeWithText("95%").performScrollTo().assertExists()
        composeRule.onNodeWithText("Save a resume point after").performScrollTo().assertExists()
        composeRule.onNodeWithText("10 s").performScrollTo().assertExists()
        composeRule.onNodeWithText("Record plays per provider").performScrollTo().assertExists()
        composeRule.onNodeWithText("Home server").performScrollTo().assertExists()
        composeRule.onNodeWithText("Album play count").performScrollTo().assertExists()
        composeRule.onNodeWithText("Average").performScrollTo().assertExists()
        composeRule.onNodeWithText("Count supporting artists").performScrollTo().assertExists()
        composeRule.onNodeWithText("Export history as CSV").performScrollTo().assertExists()
    }

    @Test
    fun `toggling a provider switch pauses recording and persists to the store`() {
        val history = testHistorySettings(tag = "history-toggle")

        composeRule.setContent {
            OmnisonicTheme {
                ShellSettingsScreen(
                    appVersion = "1.0.0",
                    onOpenProviders = {},
                    syncViewModel = testLibrarySyncManagerViewModel(),
                    pendingSyncViewModel = testPendingSyncViewModel(),
                    viewModel = ShellSettingsViewModel(
                        ShellPreferencesStore.createAt(
                            File("/tmp/opencode", "history-toggle-shell-${System.nanoTime()}.preferences_pb"),
                        ),
                    ),
                    historySettingsViewModel = history.viewModel,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Home server").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Recording for Home server")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertThat(history.gate.isPaused(1L)).isTrue()
        val persisted = runBlocking {
            withTimeoutOrNull(2_000) {
                while (1L !in history.store.current().pausedProviderIds) delay(10)
            }
        }
        assertThat(persisted).isNotNull()
    }
}
