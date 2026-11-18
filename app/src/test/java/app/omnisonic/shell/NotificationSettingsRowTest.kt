package app.omnisonic.shell

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.feature.settings.ui.ShellSettingsScreen
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSettingsRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `notification settings row opens the system screen for this app`() {
        val store = ShellPreferencesStore.createAt(
            File("/tmp/opencode", "notification-row-${System.nanoTime()}.preferences_pb"),
        )
        composeRule.setContent {
            OmnisonicTheme {
                ShellSettingsScreen(
                    appVersion = "1.0.0",
                    onOpenProviders = {},
                    syncViewModel = testLibrarySyncManagerViewModel(),
                    pendingSyncViewModel = testPendingSyncViewModel(),
                    viewModel = ShellSettingsViewModel(store),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Notification settings").performScrollTo().performClick()
        composeRule.waitForIdle()

        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertThat(started).isNotNull()
        assertThat(started!!.action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        assertThat(started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            .isEqualTo(composeRule.activity.packageName)
    }
}
