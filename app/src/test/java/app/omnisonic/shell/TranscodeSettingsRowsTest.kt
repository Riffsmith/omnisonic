package app.omnisonic.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.omnisonic.core.data.transcode.TranscodeRendererId
import app.omnisonic.core.data.transcode.TranscodeSettingsStore
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.feature.settings.transcode.TranscodeSettingsViewModel
import app.omnisonic.feature.settings.ui.ShellSettingsScreen
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscodeSettingsRowsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val transcodeStore = TranscodeSettingsStore.inMemory()

    private fun showSettings() {
        val shellStore = ShellPreferencesStore.createAt(
            File("/tmp/opencode", "transcode-rows-${System.nanoTime()}.preferences_pb"),
        )
        composeRule.setContent {
            OmnisonicTheme {
                ShellSettingsScreen(
                    appVersion = "1.0.0",
                    onOpenProviders = {},
                    syncViewModel = testLibrarySyncManagerViewModel(),
                    pendingSyncViewModel = testPendingSyncViewModel(),
                    viewModel = ShellSettingsViewModel(shellStore),
                    historySettingsViewModel = testHistorySettingsViewModel(),
                    transcodeSettingsViewModel = TranscodeSettingsViewModel(transcodeStore),
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `both transcode rows render as labelled switches that are on by default`() {
        showSettings()

        composeRule.onNodeWithText("Treat VPN as cellular").performScrollTo().assertExists()
        composeRule.onNodeWithText("Treat metered networks as cellular").performScrollTo().assertExists()
        composeRule.onNodeWithContentDescription("Treat VPN as cellular").assertIsOn()
        composeRule.onNodeWithContentDescription("Treat metered networks as cellular").assertIsOn()
    }

    @Test
    fun `toggling a transcode row writes the whole renderer settings`() {
        showSettings()

        composeRule.onNodeWithContentDescription("Treat VPN as cellular")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        val afterVpn = runBlocking { transcodeStore.current(TranscodeRendererId.Local) }
        assertThat(afterVpn.vpnAsCellular).isFalse()
        assertThat(afterVpn.meteredAsCellular).isTrue()
        composeRule.onNodeWithContentDescription("Treat VPN as cellular").assertIsOff()

        composeRule.onNodeWithContentDescription("Treat metered networks as cellular")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        val afterMetered = runBlocking { transcodeStore.current(TranscodeRendererId.Local) }
        assertThat(afterMetered.vpnAsCellular).isFalse()
        assertThat(afterMetered.meteredAsCellular).isFalse()
        composeRule.onNodeWithContentDescription("Treat metered networks as cellular").assertIsOff()
    }
}
