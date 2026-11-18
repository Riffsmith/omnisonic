package app.omnisonic.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.omnisonic.feature.library.miniplayer.MiniPlayerUiState
import app.omnisonic.feature.library.nowplaying.NowPlayingEntry
import app.omnisonic.feature.library.player.PlayerSheetStops
import app.omnisonic.feature.library.ui.MiniPlayerBar
import app.omnisonic.feature.settings.shell.MainTab
import app.omnisonic.feature.settings.shell.ShellSettingsViewModel
import app.omnisonic.playback.api.queue.QueueState
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
import kotlin.math.abs

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
        onPrevious: () -> Unit = {},
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
                        onPrevious = onPrevious,
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
        composeRule.onNodeWithContentDescription("Previous").assertExists()
        composeRule.onNodeWithContentDescription("Next").assertExists()
        composeRule.onNodeWithContentDescription("Expand player").assertExists()
    }

    @Test
    fun hairlineProgressComesFromState() {
        setBar(
            playingState().copy(positionMs = 25_000L, durationMs = 100_000L),
        )

        composeRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f)))
            .assertExists()
    }

    @Test
    fun hairlineReadsZeroWithoutADuration() {
        setBar()

        composeRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0f, 0f..1f)))
            .assertExists()
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
        var previous = 0
        var next = 0
        setBar(
            onTogglePlayPause = { toggles += 1 },
            onPrevious = { previous += 1 },
            onNext = { next += 1 },
        )

        composeRule.onNodeWithContentDescription("Previous").performClick()
        composeRule.onNodeWithContentDescription("Play or pause").performClick()
        composeRule.onNodeWithContentDescription("Next").performClick()

        assertThat(previous).isEqualTo(1)
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

    private lateinit var sheet: PlayerSheetState
    private lateinit var backDispatcher: OnBackPressedDispatcher

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
        queueState: QueueState = QueueState(),
    ): NavHostController {
        lateinit var nav: NavHostController
        val settingsViewModel = ShellSettingsViewModel(tempShellStore("miniplayer"))
        val providerSetupViewModel = testProviderSetupViewModel()
        val stepViewModel = testLibrarySyncStepViewModel()
        val queueViewModel = testQueueViewModel(queueState)
        composeRule.setContent {
            OmnisonicTheme {
                backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
                    .onBackPressedDispatcher
                val sheetState = rememberPlayerSheetState()
                sheet = sheetState
                OmnisonicShell(
                    state = shellState(startRoute),
                    settingsViewModel = settingsViewModel,
                    providerSetupViewModel = providerSetupViewModel,
                    browseViewModel = testBrowseViewModel(),
                    syncStepViewModelProvider = { stepViewModel },
                    syncManagerViewModel = testLibrarySyncManagerViewModel(),
                    pendingSyncViewModel = testPendingSyncViewModel(),
                    searchViewModel = testSearchViewModel(),
                    miniPlayerState = miniPlayerState,
                    navController = rememberNavController().also { nav = it },
                    sheetState = sheetState,
                    nowPlayingViewModelProvider = { testNowPlayingViewModel() },
                    queueViewModelProvider = { queueViewModel },
                    detailViewModelProvider = { testDetailViewModel() },
                )
            }
        }
        composeRule.waitForIdle()
        return nav
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

    /** The test doubles have no playback state, so the sheet shows its empty state. */
    private fun sheetVisible() = composeRule.onAllNodesWithText("Nothing playing yet")
        .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun expandingOpensThePlayerSheetOverTheCurrentRoute() {
        val nav = setShell(playingState())

        composeRule.onNodeWithContentDescription("Expand player").performClick()

        // The sheet slides over the route instead of navigating (ui/player-sheet.md),
        // so the route underneath stays put and is what a downward drag reveals.
        awaitCondition { sheetVisible() }
        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.LIBRARY)
    }

    /**
     * Regression for the fast-fling bug (ui/player-sheet.md §2): one coalesced
     * move gives the release only two velocity samples and the pre-fix tracker
     * released at 0 px/s, so this settled back to peek. The flick travels 31%
     * of the viewport, well past the framework's fling gate.
     */
    @Test
    fun fastPartialUpwardFlingFromTheBarOpensNowPlaying() {
        setShell(playingState())

        flingOn(BAR_TITLE, dyFraction = -0.31f)

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertSheetStaysAt(PlayerSheetStops.NOW_PLAYING)
    }

    /** A slow short drag keeps the existing behaviour: it still opens. */
    @Test
    fun slowShortUpwardDragFromTheBarStillOpensNowPlaying() {
        setShell(playingState())

        composeRule.onNodeWithText(BAR_TITLE).performTouchInput {
            down(center)
            repeat(40) { moveBy(Offset(0f, -3f)) }
            up()
        }

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
    }

    @Test
    fun downwardFlingOnTheCollapsedBarStaysCollapsed() {
        setShell(playingState())

        flingOn(BAR_TITLE, dyFraction = 0.31f)

        Thread.sleep(200)
        composeRule.waitForIdle()
        assertThat(sheet.position).isWithin(0.002f).of(PlayerSheetStops.PEEK)
        assertThat(sheet.isVisible).isFalse()
    }

    @Test
    fun fastDownwardFlingOnNowPlayingCollapses() {
        setShell(playingState())
        composeRule.onNodeWithContentDescription("Expand player").performClick()
        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)

        flingOn(NOW_PLAYING_BODY, dyFraction = 0.31f)

        awaitSheetAt(PlayerSheetStops.PEEK)
        assertThat(sheet.isVisible).isFalse()
    }

    @Test
    fun fastUpwardFlingOnNowPlayingOpensTheQueue() {
        setShell(playingState())
        composeRule.onNodeWithContentDescription("Expand player").performClick()
        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)

        flingOn(NOW_PLAYING_BODY, dyFraction = -0.31f)

        awaitSheetAt(PlayerSheetStops.SWIPE_UP_TARGET)
        assertThat(sheet.swipeUpTargetRevealed).isTrue()
    }

    @Test
    fun queueBackReturnsToNowPlaying() {
        setShell(playingState())
        composeRule.onNodeWithContentDescription("Expand player").performClick()
        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        composeRule.onRoot().performTouchInput {
            swipeUp(startY = height * 0.7f, endY = height * 0.15f, durationMillis = 120)
        }
        awaitSheetAt(PlayerSheetStops.SWIPE_UP_TARGET)

        composeRule.onNodeWithContentDescription("Go back").performClick()

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
    }

    @Test
    fun swipingUpOnTheSheetPullsTheQueueIn() {
        val nav = setShell(playingState())
        composeRule.onNodeWithContentDescription("Expand player").performClick()
        awaitCondition { sheetVisible() }

        composeRule.onRoot().performTouchInput {
            swipeUp(startY = height * 0.7f, endY = height * 0.15f, durationMillis = 120)
        }

        awaitCondition {
            composeRule.onAllNodesWithContentDescription("Go back")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Other than the default: the swipe-up target is a slot (ui/player-sheet.md).
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.LIBRARY)
    }

    /** A short queue fits the viewport, so its list is at the top from the start. */
    private fun shortQueue(): QueueState = testQueueState(count = 3, playIndex = 1)

    private fun longQueue(): QueueState = testQueueState(count = 60, playIndex = 30)

    private fun openQueue() {
        composeRule.onNodeWithContentDescription("Expand player").performClick()
        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        composeRule.onRoot().performTouchInput {
            swipeUp(startY = height * 0.7f, endY = height * 0.15f, durationMillis = 120)
        }
        awaitSheetAt(PlayerSheetStops.SWIPE_UP_TARGET)
    }

    private fun pullDownOn(
        text: String,
        dyPxPerStep: Float = 15f,
        steps: Int = 60,
        millisPerStep: Long = 0L,
    ) {
        composeRule.onNodeWithText(text).performTouchInput {
            down(center)
            repeat(steps) {
                if (millisPerStep > 0L) advanceEventTime(millisPerStep)
                moveBy(Offset(0f, dyPxPerStep))
            }
            up()
        }
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread { backDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun swipingDownAtTheQueueTopReturnsToNowPlaying() {
        setShell(playingState(), queueState = shortQueue())
        openQueue()

        pullDownOn(QUEUE_LAST_ROW)

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertSheetStaysAt(PlayerSheetStops.NOW_PLAYING)
    }

    /**
     * Regression for D-21: a deliberate but slow pull reports almost no
     * release velocity, and the pre-fix half-viewport rule snapped it back.
     * 160 px of an 891 px viewport is well under that half, yet well past the
     * commit distance (32 dp).
     */
    @Test
    fun aSlowShortPullFromTheQueueTopReturnsToNowPlaying() {
        setShell(playingState(), queueState = shortQueue())
        openQueue()

        pullDownOn(QUEUE_LAST_ROW, dyPxPerStep = 4f, steps = 40, millisPerStep = 100L)

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertSheetStaysAt(PlayerSheetStops.NOW_PLAYING)
    }

    /**
     * Regression for D-24: a long queue opens with the current row second, so
     * the true top is off-screen. The reveal position is eligible instead, and
     * one deliberate pull returns on the first attempt.
     */
    @Test
    fun aPullAtTheRevealPositionOfALongQueueReturnsOnTheFirstAttempt() {
        setShell(playingState(), queueState = longQueue())
        openQueue()
        awaitCondition { rowExists(CURRENT_ROW) }
        assertThat(sheet.position).isWithin(0.002f).of(PlayerSheetStops.SWIPE_UP_TARGET)

        pullDownOn(CURRENT_ROW)

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertSheetStaysAt(PlayerSheetStops.NOW_PLAYING)
    }

    @Test
    fun aTinySlowTwitchAtTheRevealPositionSnapsBack() {
        setShell(playingState(), queueState = longQueue())
        openQueue()
        awaitCondition { rowExists(CURRENT_ROW) }

        pullDownOn(CURRENT_ROW, dyPxPerStep = 1f, steps = 20)

        assertSheetStaysAt(PlayerSheetStops.SWIPE_UP_TARGET)
    }

    @Test
    fun aTinySlowTwitchAtTheQueueTopSnapsBack() {
        setShell(playingState(), queueState = shortQueue())
        openQueue()

        pullDownOn(QUEUE_LAST_ROW, dyPxPerStep = 1f, steps = 20)

        assertSheetStaysAt(PlayerSheetStops.SWIPE_UP_TARGET)
    }

    /**
     * A pointer stream can end without the dragging surface seeing the up
     * (system cancellation, focus loss). The overlay's gesture-end observer is
     * the safety net: the sheet must settle instead of staying parked halfway.
     */
    @Test
    fun aPullWhoseReleaseNeverArrivesStillSettles() {
        setShell(playingState(), queueState = shortQueue())
        openQueue()

        composeRule.runOnUiThread {
            sheet.onPointerDown()
            sheet.dragFromSwipeUpTarget(160f)
            sheet.onAllPointersUp(flingVelocityPx = 139f, commitDistancePx = 89f)
        }

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertSheetStaysAt(PlayerSheetStops.NOW_PLAYING)
    }

    @Test
    fun aLostReleaseBeforeTheCommitDistanceSnapsBack() {
        setShell(playingState(), queueState = shortQueue())
        openQueue()

        composeRule.runOnUiThread {
            sheet.onPointerDown()
            sheet.dragFromSwipeUpTarget(20f)
            sheet.onAllPointersUp(flingVelocityPx = 139f, commitDistancePx = 89f)
        }

        awaitSheetAt(PlayerSheetStops.SWIPE_UP_TARGET)
        assertSheetStaysAt(PlayerSheetStops.SWIPE_UP_TARGET)
    }

    @Test
    fun aCompletedPullNeverCollapsesTheSheet() {
        setShell(playingState(), queueState = shortQueue())
        openQueue()

        // Another screen-height of downward drag after the player arrived must
        // be swallowed instead of continuing into the collapsed detent.
        pullDownOn(QUEUE_LAST_ROW, steps = 120)

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertThat(sheet.isVisible).isTrue()
    }

    @Test
    fun swipingDownOnAScrolledQueueLeavesTheSheetAlone() {
        setShell(playingState(), queueState = longQueue())
        openQueue()
        awaitCondition { rowExists(CURRENT_ROW) }
        assertThat(rowExists(QUEUE_FIRST_HIDDEN)).isFalse()

        // One user scroll drops the reveal-position eligibility (D-24); the
        // jump pill then returns the list to the current row so the pull below
        // starts exactly where it used to enrol.
        composeRule.onRoot().performTouchInput {
            swipeUp(startY = height * 0.8f, endY = height * 0.1f, durationMillis = 300)
        }
        awaitCondition { jumpPillExists() }
        composeRule.onNodeWithContentDescription(JUMP_LABEL).performClick()
        awaitCondition { rowExists(CURRENT_ROW) }

        pullDownOn(CURRENT_ROW, dyPxPerStep = 3f, steps = 40)

        assertSheetStaysAt(PlayerSheetStops.SWIPE_UP_TARGET)
        assertThat(sheet.isVisible).isTrue()
        // The drag scrolled the list instead: the row above arrived.
        assertThat(
            composeRule.onAllNodesWithText("Track 30").fetchSemanticsNodes(),
        ).isNotEmpty()
    }

    @Test
    fun emptyQueueSwipesDownToNowPlaying() {
        setShell(playingState())
        openQueue()

        pullDownOn(QUEUE_EMPTY_BODY)

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertThat(sheet.isVisible).isTrue()
    }

    @Test
    fun systemBackOnTheQueueGoesToNowPlayingThenCollapses() {
        val nav = setShell(playingState(), queueState = shortQueue())
        openQueue()

        pressBack()

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.LIBRARY)

        pressBack()

        awaitSheetAt(PlayerSheetStops.PEEK)
        assertThat(sheet.isVisible).isFalse()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.LIBRARY)
    }

    /**
     * Regression for the open P1 "Navigation from the queue/nps screen"
     * (ui/player-sheet-back-navigation.md): the NavHost registers its own back
     * callback and, being composed after the shell's sheet handler, wins the
     * LIFO dispatch while the back stack is deeper than its root — so system
     * back walked the hidden route beneath the expanded sheet. The old code
     * pops album -> library on the first press with the sheet still up.
     */
    @Test
    fun systemBackOverTheSheetDoesNotWalkTheRouteBeneath() {
        val nav = setShell(playingState())
        nav.navigate(ShellRoutes.album(1L))
        composeRule.waitForIdle()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.ALBUM)

        composeRule.onNodeWithContentDescription("Expand player").performClick()
        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)

        pressBack()

        // The sheet owns back while visible: collapse to peek, route untouched.
        awaitSheetAt(PlayerSheetStops.PEEK)
        assertThat(sheet.isVisible).isFalse()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.ALBUM)

        pressBack()

        // Sheet at peek no longer owns back; the route pops now.
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.LIBRARY)
    }

    @Test
    fun systemBackOnTheQueueOverADeepStack() {
        val nav = setShell(playingState(), queueState = shortQueue())
        nav.navigate(ShellRoutes.album(1L))
        composeRule.waitForIdle()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.ALBUM)

        openQueue()
        val depth = nav.currentBackStack.value.size

        pressBack()

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.ALBUM)
        assertThat(nav.currentBackStack.value.size).isEqualTo(depth)

        pressBack()

        awaitSheetAt(PlayerSheetStops.PEEK)
        assertThat(sheet.isVisible).isFalse()
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.ALBUM)
        assertThat(nav.currentBackStack.value.size).isEqualTo(depth)
    }

    @Test
    fun queueOpensAtTheCurrentRow() {
        setShell(playingState(), queueState = longQueue())
        openQueue()

        awaitCondition { rowExists(CURRENT_ROW) }
        assertThat(rowExists(QUEUE_FIRST_HIDDEN)).isFalse()
        assertThat(
            composeRule.onAllNodesWithText("Track 29").fetchSemanticsNodes(),
        ).isNotEmpty()
    }

    @Test
    fun jumpToCurrentAfterScrolling() {
        setShell(playingState(), queueState = longQueue())
        openQueue()
        awaitCondition { rowExists(CURRENT_ROW) }
        composeRule.onRoot().performTouchInput {
            swipeUp(startY = height * 0.8f, endY = height * 0.1f, durationMillis = 300)
        }
        composeRule.waitForIdle()

        // The pill appears as soon as the current row stops being fully visible,
        // which may still be a partially composed row.
        awaitCondition { jumpPillExists() }

        composeRule.onNodeWithContentDescription(JUMP_LABEL).performClick()

        awaitCondition { rowExists(CURRENT_ROW) }
        awaitCondition { !jumpPillExists() }
    }

    private fun rowExists(title: String) = composeRule.onAllNodesWithText(title)
        .fetchSemanticsNodes().isNotEmpty()

    private fun jumpPillExists() = composeRule.onAllNodesWithContentDescription(JUMP_LABEL)
        .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun swipingDownOnTheSheetRevealsTheRouteBeneath() {
        val nav = setShell(playingState())
        composeRule.onNodeWithContentDescription("Expand player").performClick()
        awaitCondition { sheetVisible() }

        composeRule.onRoot().performTouchInput {
            swipeDown(startY = height * 0.2f, endY = height * 0.9f, durationMillis = 120)
        }

        awaitCondition { !sheetVisible() }
        assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.LIBRARY)
    }

    /**
     * One quick move over [dyFraction] of the viewport then lift — the event
     * shape of a fast flick that the input system may coalesce into a single
     * batch. Negative fractions move up.
     */
    private fun flingOn(text: String, dyFraction: Float) {
        val viewportPx = composeRule.onRoot().fetchSemanticsNode().size.height.toFloat()
        composeRule.onNodeWithText(text).performTouchInput {
            down(center)
            advanceEventTime(16)
            moveBy(Offset(0f, viewportPx * dyFraction))
            advanceEventTime(16)
            up()
        }
    }

    private fun awaitSheetAt(target: Float) {
        repeat(80) {
            composeRule.waitForIdle()
            if (abs(sheet.position - target) <= 0.002f) return
            Thread.sleep(20)
        }
        assertThat(sheet.position).isWithin(0.002f).of(target)
    }

    private fun assertSheetStaysAt(target: Float) {
        Thread.sleep(200)
        composeRule.waitForIdle()
        assertThat(sheet.position).isWithin(0.002f).of(target)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        repeat(40) {
            composeRule.waitForIdle()
            if (condition()) return
            Thread.sleep(25)
        }
        assertThat(condition()).isTrue()
    }

    private companion object {
        const val BAR_TITLE = "Night Ride"
        // Unique to now-playing; "Nothing playing yet" is shared with the queue.
        const val NOW_PLAYING_BODY = "Pick something from your library and it will start right here."
        const val QUEUE_EMPTY_BODY =
            "Start something from your library and it will line up here."
        const val QUEUE_LAST_ROW = "Track 3"
        const val QUEUE_FIRST_HIDDEN = "Track 1"
        const val CURRENT_ROW = "Track 31"
        const val JUMP_LABEL = "Jump to Track 31"
    }
}
