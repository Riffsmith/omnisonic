package app.omnisonic.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.omnisonic.feature.library.miniplayer.MiniPlayerUiState
import app.omnisonic.feature.library.nowplaying.NowPlayingEntry
import app.omnisonic.feature.library.ui.MiniPlayerBar
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.style.ButtonTwinAudit
import app.omnisonic.style.GestureTwin
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.rememberSwipeZoneRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mini-player bar wiring (UI_SPEC.md §3.10): the drag/expand state machine's
 * pure logic lives in `:feature-library` (`MiniPlayerExpandRulesTest`); these
 * tests drive the bar itself through the real pointer pipeline and prove the
 * button twin (UI_SPEC.md §1.3), plus the shell placement rules (visible only
 * with playback, never on the expanded route).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MiniPlayerBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun playingState() = MiniPlayerUiState(
        entry = NowPlayingEntry(
            queueItemId = 7,
            title = "Night Ride",
            artistName = "The Late Shift",
            cover = null,
        ),
        isPlaying = true,
    )

    private fun setBar(
        state: MiniPlayerUiState = playingState(),
        onTogglePlayPause: () -> Unit = {},
        onNext: () -> Unit = {},
        onExpand: () -> Unit = {},
    ) {
        composeRule.setContent {
            OmnisonicTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MiniPlayerBar(
                        state = state,
                        swipeZoneRegistry = rememberSwipeZoneRegistry(),
                        onTogglePlayPause = onTogglePlayPause,
                        onNext = onNext,
                        onExpand = onExpand,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * A drag whose move events stay under touch slop until the cumulative
     * distance crosses it — the event shape of a real drag start. Large
     * `swipe()` steps would cross slop on the first move and hide
     * ownership/consumption bugs (the task 4e lesson).
     */
    private fun slowDrag(dyPxPerStep: Float) {
        composeRule.onNodeWithText("Night Ride").performTouchInput {
            down(center)
            repeat(40) { moveBy(Offset(0f, dyPxPerStep)) }
            up()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun rendersTrackAndTransportControls() {
        setBar()

        composeRule.onNodeWithText("Night Ride").assertExists()
        composeRule.onNodeWithText("The Late Shift").assertExists()
        composeRule.onNodeWithContentDescription("Play or pause").assertExists()
        composeRule.onNodeWithContentDescription("Next").assertExists()
        composeRule.onNodeWithContentDescription("Expand player").assertExists()
    }

    @Test
    fun tappingTheBarExpandsOnce() {
        var expands = 0
        setBar(onExpand = { expands += 1 })

        composeRule.onNodeWithText("Night Ride").performClick()

        assertThat(expands).isEqualTo(1)
    }

    @Test
    fun dragUpSettlesExpanded() {
        var expands = 0
        setBar(onExpand = { expands += 1 })

        slowDrag(-3f)

        assertThat(expands).isEqualTo(1)
    }

    @Test
    fun dragDownStaysCollapsed() {
        // The bar is the collapsed resting state, so a downward drag must not
        // expand it; the pure rules cover the expanded -> collapsed half.
        var expands = 0
        setBar(onExpand = { expands += 1 })

        slowDrag(3f)

        assertThat(expands).isEqualTo(0)
    }

    @Test
    fun transportButtonsDispatch() {
        var toggles = 0
        var next = 0
        setBar(onTogglePlayPause = { toggles += 1 }, onNext = { next += 1 })

        composeRule.onNodeWithContentDescription("Play or pause").performClick()
        composeRule.onNodeWithContentDescription("Next").performClick()

        assertThat(toggles).isEqualTo(1)
        assertThat(next).isEqualTo(1)
    }

    @Test
    fun expandGestureHasItsVisibleTwin() {
        setBar()

        ButtonTwinAudit.assertTwinsPresent(
            composeRule.onRoot().fetchSemanticsNode(),
            listOf(GestureTwin("mini-expand", "Expand player", "Expand player")),
        )
    }

    @Test
    fun rendersNothingWhenNothingIsPlaying() {
        setBar(state = MiniPlayerUiState())

        composeRule.onNodeWithText("Night Ride").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Expand player").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Play or pause").assertDoesNotExist()
    }
}

/** Shell placement of the bar: playback-only, hidden on the expanded route. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MiniPlayerShellTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun playingState() = MiniPlayerUiState(
        entry = NowPlayingEntry(
            queueItemId = 7,
            title = "Night Ride",
            artistName = "The Late Shift",
            cover = null,
        ),
        isPlaying = true,
    )

    private fun shellState(startRoute: String) = ShellUiState(
        tabs = MainTab.DEFAULT,
        startRoute = startRoute,
        onboardingComplete = true,
        hasProvider = true,
        portraitNavOverride = null,
        landscapeNavOverride = null,
    )

    private fun setShell(
        miniPlayerState: MiniPlayerUiState,
        startRoute: String = ShellRoutes.LIBRARY,
    ): NavHostController {
        lateinit var nav: NavHostController
        val settingsViewModel = ShellSettingsViewModel(tempShellStore("miniplayer"))
        val providerSetupViewModel = testProviderSetupViewModel()
        val stepViewModel = testLibrarySyncStepViewModel()
        composeRule.setContent {
            OmnisonicTheme {
                OmnisonicShell(
                    state = shellState(startRoute),
                    settingsViewModel = settingsViewModel,
                    providerSetupViewModel = providerSetupViewModel,
                    browseViewModel = testBrowseViewModel(),
                    syncStepViewModelProvider = { stepViewModel },
                    syncManagerViewModel = testLibrarySyncManagerViewModel(),
                    searchViewModel = testSearchViewModel(),
                    miniPlayerState = miniPlayerState,
                    navController = rememberNavController().also { nav = it },
                    nowPlayingViewModelProvider = { testNowPlayingViewModel() },
                    queueViewModelProvider = { testQueueViewModel() },
                )
            }
        }
        composeRule.waitForIdle()
        return nav
    }

    private fun awaitRoute(nav: NavHostController, route: String) {
        repeat(20) {
            composeRule.waitForIdle()
            if (nav.currentDestination?.route == route) return
            Thread.sleep(25)
        }
        assertThat(nav.currentDestination?.route).isEqualTo(route)
    }

    @Test
    fun barShowsAboveTheTabsWhileSomethingIsPlaying() {
        setShell(playingState())

        composeRule.onNodeWithContentDescription("Expand player").assertExists()
    }

    @Test
    fun barIsHiddenWhenNothingIsPlaying() {
        setShell(MiniPlayerUiState())

        composeRule.onNodeWithContentDescription("Expand player").assertDoesNotExist()
    }

    @Test
    fun barIsHiddenOnTheNowPlayingRoute() {
        // The expanded player owns the screen; the compact bar would duplicate it.
        setShell(playingState(), startRoute = ShellRoutes.NOW_PLAYING)

        composeRule.onNodeWithContentDescription("Expand player").assertDoesNotExist()
    }

    @Test
    fun expandingOpensNowPlaying() {
        val nav = setShell(playingState())

        composeRule.onNodeWithContentDescription("Expand player").performClick()

        awaitRoute(nav, ShellRoutes.NOW_PLAYING)
    }
}
