package app.omnisonic.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.omnisonic.core.data.db.dao.ProviderIdentityRow
import app.omnisonic.core.data.sync.OutboxPendingProvider
import app.omnisonic.core.data.sync.OutboxPendingRepository
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.feature.settings.sync.PendingSyncViewModel
import app.omnisonic.feature.settings.ui.ShellSettingsScreen
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingSyncSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `queued changes show the provider error and retry asks the coordinator`() {
        val control = FakeOutboxSyncControl()
        val viewModel = PendingSyncViewModel(
            pendingRepository(
                OutboxPendingProvider(1, "Home server", pendingCount = 3, failingCount = 1, lastError = "server said no"),
            ),
            control,
        )

        composeRule.setContent {
            OmnisonicTheme {
                ShellSettingsScreen(
                    appVersion = "1.0.0",
                    onOpenProviders = {},
                    syncViewModel = testLibrarySyncManagerViewModel(),
                    pendingSyncViewModel = viewModel,
                    viewModel = ShellSettingsViewModel(
                        ShellPreferencesStore.createAt(
                            File("/tmp/opencode", "pending-sync-${System.nanoTime()}.preferences_pb"),
                        ),
                    ),
                    historySettingsViewModel = testHistorySettingsViewModel(
                        listOf(ProviderIdentityRow(1, "History provider")),
                    ),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Pending sync").performScrollTo()
        composeRule.onNodeWithText("3 changes waiting to sync").performScrollTo().assertExists()
        composeRule.onNodeWithText("Home server").performScrollTo().assertExists()
        composeRule.onNodeWithText("3 changes queued").performScrollTo().assertExists()
        composeRule.onNodeWithText("1 failed").performScrollTo().assertExists()
        composeRule.onNodeWithText("Last error: server said no").performScrollTo().assertExists()
        composeRule.onNodeWithText("Retry now").performScrollTo().performClick()

        assertThat(control.retryRequests).isEqualTo(1)
    }

    @Test
    fun `an empty outbox says everything is synced`() {
        val viewModel = PendingSyncViewModel(pendingRepository(), FakeOutboxSyncControl())

        composeRule.setContent {
            OmnisonicTheme {
                ShellSettingsScreen(
                    appVersion = "1.0.0",
                    onOpenProviders = {},
                    syncViewModel = testLibrarySyncManagerViewModel(),
                    pendingSyncViewModel = viewModel,
                    viewModel = ShellSettingsViewModel(
                        ShellPreferencesStore.createAt(
                            File("/tmp/opencode", "pending-sync-empty-${System.nanoTime()}.preferences_pb"),
                        ),
                    ),
                    historySettingsViewModel = testHistorySettingsViewModel(),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Everything is synced.").performScrollTo().assertExists()
    }

    private fun pendingRepository(
        vararg providers: OutboxPendingProvider,
    ): OutboxPendingRepository {
        val repository = mockk<OutboxPendingRepository>()
        every { repository.providers } returns flowOf(providers.toList())
        return repository
    }
}
