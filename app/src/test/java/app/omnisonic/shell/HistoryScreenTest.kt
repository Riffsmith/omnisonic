package app.omnisonic.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
    playCount: Int = 0,
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
    playCount = playCount,
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
    fun `row overflow remove asks for confirmation and deletes the event`() {
        val controls = RecordingHistoryControls()
        val viewModel = testHistoryViewModel(
            StaticHistorySource(
                listOf(historyRow(2, "e-2", 2_000, skipped = false, 65_000, "Second Song", "Artist B", "Album B")),
            ),
            controls,
        )
        composeRule.setContent {
            OmnisonicTheme {
                HistoryRoute(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("More options for Second Song").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Remove this play").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Remove this play?").assertExists()
        composeRule.onNodeWithText("Remove").performClick()
        composeRule.waitForIdle()

        assertThat(controls.deletedEventIds).containsExactly(2L)
    }

    @Test
    fun `clear provider asks for confirmation and wipes that provider`() {
        val controls = RecordingHistoryControls()
        val viewModel = testHistoryViewModel(
            StaticHistorySource(
                listOf(historyRow(2, "e-2", 2_000, skipped = false, 65_000, "Second Song", "Artist B", "Album B")),
            ),
            controls,
        )
        composeRule.setContent {
            OmnisonicTheme {
                HistoryRoute(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("More options for Second Song").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear all history for this provider").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear all history for this provider?").assertExists()
        composeRule.onNodeWithText("Clear").performClick()
        composeRule.waitForIdle()

        assertThat(controls.wipedProviders).containsExactly(1L)
        assertThat(controls.deletedEventIds).isEmpty()
    }

    @Test
    fun `pause recording toggles the provider and relabels the menu item`() {
        val controls = RecordingHistoryControls()
        val viewModel = testHistoryViewModel(
            StaticHistorySource(
                listOf(historyRow(2, "e-2", 2_000, skipped = false, 65_000, "Second Song", "Artist B", "Album B")),
            ),
            controls,
        )
        composeRule.setContent {
            OmnisonicTheme {
                HistoryRoute(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("More options for Second Song").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Pause recording for this provider").performClick()
        composeRule.waitForIdle()
        assertThat(controls.pausedProviders.value).containsExactly(1L)

        composeRule.onNodeWithContentDescription("More options for Second Song").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Resume recording for this provider").assertExists()
    }

    @Test
    fun `edit play count dialog submits the entered value`() {
        val controls = RecordingHistoryControls()
        val viewModel = testHistoryViewModel(
            StaticHistorySource(
                listOf(
                    historyRow(
                        2, "e-2", 2_000, skipped = false, 65_000,
                        "Second Song", "Artist B", "Album B", playCount = 3,
                    ),
                ),
            ),
            controls,
        )
        composeRule.setContent {
            OmnisonicTheme {
                HistoryRoute(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("More options for Second Song").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edit play count").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("3").assertExists()
        val field = composeRule.onNode(hasSetTextAction())
        field.performTextClearance()
        field.performTextInput("7")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()

        assertThat(controls.playCounts).containsExactly(Triple(1L, "e-2", 7))
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
                    historySettingsViewModel = testHistorySettingsViewModel(),
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
