package app.omnisonic.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.omnisonic.core.data.db.RecentPlayRow
import app.omnisonic.feature.library.ui.HistoryRoute
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.ShellPreferencesStore
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun historyRow(
    eventId: Long,
    externalId: String,
    playedAtMs: Long,
    skipped: Boolean,
    durationMs: Long,
    songTitle: String,
    artist: String,
    album: String,
) = RecentPlayRow(
    eventId = eventId,
    providerId = 1,
    externalId = externalId,
    playedAtMs = playedAtMs,
    played = !skipped,
    skipped = skipped,
    durationMs = durationMs,
    songId = eventId,
    songTitle = songTitle,
    displayArtist = artist,
    albumTitle = album,
    imagePath = null,
)

private fun tabItem(label: String): SemanticsMatcher = SemanticsMatcher("tab '$label'") { node ->
    node.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HistoryScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `recent plays render song artist album duration and the skipped marker`() {
        val viewModel = testHistoryViewModel(
            StaticHistorySource(
                listOf(
                    historyRow(2, "e-2", 2_000, skipped = false, 65_000, "Second Song", "Artist B", "Album B"),
                    historyRow(1, "e-1", 1_000, skipped = true, 60_000, "First Song", "Artist A", "Album A"),
                ),
            ),
        )
        composeRule.setContent {
            OmnisonicTheme {
                HistoryRoute(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Recent plays").assertExists()
        composeRule.onNodeWithText("2").assertExists()
        composeRule.onNodeWithText("Second Song").assertExists()
        composeRule.onNodeWithText("Artist B · Album B").assertExists()
        composeRule.onNodeWithText("First Song").assertExists()
        composeRule.onNodeWithText("Skipped · Artist A · Album A").assertExists()
        composeRule.onNodeWithText("1:05").assertExists()
        composeRule.onNodeWithText("1:00").assertExists()
    }

    @Test
    fun `an event without a song row falls back to the external id`() {
        val viewModel = testHistoryViewModel(
            StaticHistorySource(
                listOf(
                    RecentPlayRow(
                        eventId = 7,
                        providerId = 1,
                        externalId = "gone-1",
                        playedAtMs = 1_000,
                        played = true,
                        skipped = false,
                        durationMs = 30_000,
                        songId = null,
                        songTitle = null,
                        displayArtist = null,
                        albumTitle = null,
                        imagePath = null,
                    ),
                ),
            ),
        )
        composeRule.setContent {
            OmnisonicTheme {
                HistoryRoute(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("gone-1").assertExists()
        composeRule.onNodeWithText("0:30").assertExists()
    }

    @Test
    fun `empty history shows the empty state`() {
        val viewModel = testHistoryViewModel(EmptyHistorySource())
        composeRule.setContent {
            OmnisonicTheme {
                HistoryRoute(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Nothing played yet. Plays you listen to will show up here.")
            .assertExists()
    }

    @Test
    fun `the library hub entry navigates to the history route`() {
        lateinit var nav: NavHostController
        val settingsViewModel = ShellSettingsViewModel(
            ShellPreferencesStore.createAt(File("/tmp/opencode", "history-nav-${System.nanoTime()}.preferences_pb")),
        )
        val historyViewModel = testHistoryViewModel()
        composeRule.setContent {
            OmnisonicTheme {
                OmnisonicShell(
                    state = ShellUiState(
                        tabs = MainTab.DEFAULT,
                        startRoute = ShellRoutes.HOME,
                        onboardingComplete = true,
                        hasProvider = true,
                        portraitNavOverride = null,
                        landscapeNavOverride = null,
                    ),
                    settingsViewModel = settingsViewModel,
                    providerSetupViewModel = testProviderSetupViewModel(),
                    browseViewModel = testBrowseViewModel(),
                    syncManagerViewModel = testLibrarySyncManagerViewModel(),
                    pendingSyncViewModel = testPendingSyncViewModel(),
                    searchViewModel = testSearchViewModel(),
                    historyViewModelProvider = { historyViewModel },
                    navController = rememberNavController().also { nav = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(tabItem("Library")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Recent plays").performClick()
        composeRule.waitForIdle()

        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.HISTORY)
        composeRule.onNodeWithText("Nothing played yet. Plays you listen to will show up here.")
            .assertExists()
    }
}
