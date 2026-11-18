package app.omnisonic.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import app.omnisonic.feature.library.nowplaying.LyricsUiState
import app.omnisonic.feature.library.nowplaying.NowPlayingEntry
import app.omnisonic.feature.library.nowplaying.NowPlayingPane
import app.omnisonic.feature.library.nowplaying.NowPlayingUiState
import app.omnisonic.feature.library.queue.PendingUndoUi
import app.omnisonic.feature.library.queue.QueueRow
import app.omnisonic.feature.library.queue.QueueUiState
import app.omnisonic.feature.library.ui.NowPlayingScreen
import app.omnisonic.feature.library.ui.PlayerSheetDrag
import app.omnisonic.feature.library.ui.QueueScreen
import app.omnisonic.feature.library.ui.nowPlayingIndicatorAnimates
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.style.ButtonTwinAudit
import app.omnisonic.style.GestureTwin
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders both player screens' states and proves the button-twin rule
 * (UI_SPEC.md §1.3): every art gesture has its visible control. View-model
 * mapping and command dispatch live in `:feature-library` tests; these are the
 * compose-level wiring checks plus the Roborazzi goldens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlayerScreensRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun playingState(): NowPlayingUiState = NowPlayingUiState(
        entry = NowPlayingEntry(
            queueItemId = 7,
            title = "Night Ride",
            artistName = "The Late Shift",
            cover = null,
        ),
        isPlaying = true,
        positionMs = 61_000,
        durationMs = 200_000,
        shuffleEnabled = false,
        repeatMode = RepeatMode.ALL,
    )

    private fun queueEntry(id: Long, title: String) = PlaybackQueueEntry(
        providerId = 1,
        externalId = "ext-$id",
        queueItemId = id,
        title = title,
        artistName = "The Late Shift",
        durationMs = 200_000,
    )

    private fun queueState(): QueueUiState = QueueUiState(
        rows = listOf(
            QueueRow(entry = queueEntry(1, "Opener"), orderIndex = 0, isCurrent = false),
            QueueRow(entry = queueEntry(2, "Night Ride"), orderIndex = 1, isCurrent = true),
            QueueRow(entry = queueEntry(3, "Encore"), orderIndex = 2, isCurrent = false),
        ),
        currentOrderIndex = 1,
        shuffled = false,
    )

    private fun setNowPlaying(
        state: NowPlayingUiState = playingState(),
        onTogglePlayPause: () -> Unit = {},
        onNext: () -> Unit = {},
        onPrevious: () -> Unit = {},
        onSeek: (Long) -> Unit = {},
        onToggleShuffle: () -> Unit = {},
        onCycleRepeat: () -> Unit = {},
        onCollapse: () -> Unit = {},
        onOpenQueue: () -> Unit = {},
        onOpenLibrary: () -> Unit = {},
        pane: NowPlayingPane = NowPlayingPane.COVER,
        onTogglePane: () -> Unit = {},
        lyrics: LyricsUiState = LyricsUiState(),
    ) {
        composeRule.setContent {
            OmnisonicTheme {
                NowPlayingScreen(
                    state = state,
                    onTogglePlayPause = onTogglePlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onSeek = onSeek,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    onCollapse = onCollapse,
                    onOpenQueue = onOpenQueue,
                    onOpenLibrary = onOpenLibrary,
                    pane = pane,
                    onTogglePane = onTogglePane,
                    lyrics = lyrics,
                )
            }
        }
    }

    private fun longQueueState(currentOrderIndex: Int): QueueUiState = QueueUiState(
        rows = (0 until 60).map { index ->
            QueueRow(
                entry = queueEntry(index.toLong() + 1L, "Track ${index + 1}"),
                orderIndex = index,
                isCurrent = index == currentOrderIndex,
            )
        },
        currentOrderIndex = currentOrderIndex,
        shuffled = false,
    )

    private fun setQueue(
        state: QueueUiState = queueState(),
        onJump: (Int) -> Unit = {},
        onRemove: (Long) -> Unit = {},
        onUndo: () -> Unit = {},
        onUndoExpired: (Long) -> Unit = {},
        onMoveUp: (Long) -> Unit = {},
        onMoveDown: (Long) -> Unit = {},
        onClearRemaining: () -> Unit = {},
        onBack: () -> Unit = {},
        onOpenLibrary: () -> Unit = {},
        sheetDrag: PlayerSheetDrag? = null,
        isRevealed: Boolean = true,
    ) {
        composeRule.setContent {
            OmnisonicTheme {
                QueueScreen(
                    state = state,
                    onJump = onJump,
                    onRemove = onRemove,
                    onUndo = onUndo,
                    onUndoExpired = onUndoExpired,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onClearRemaining = onClearRemaining,
                    onBack = onBack,
                    onOpenLibrary = onOpenLibrary,
                    sheetDrag = sheetDrag,
                    isRevealed = isRevealed,
                )
            }
        }
    }

    @Test
    fun nowPlayingRendersTheTrackAndTransportControls() {
        setNowPlaying()

        composeRule.onAllNodesWithText("Night Ride").onFirst().assertExists()
        composeRule.onAllNodesWithText("The Late Shift").onFirst().assertExists()
        composeRule.onAllNodesWithContentDescription("Play or pause").onFirst().assertExists()
        composeRule.onNodeWithContentDescription("Previous").assertExists()
        composeRule.onNodeWithContentDescription("Next").assertExists()
        composeRule.onNodeWithContentDescription("Shuffle").assertExists()
        composeRule.onNodeWithContentDescription("Repeat").assertExists()
        composeRule.onNodeWithContentDescription("Queue").assertExists()
        composeRule.onNodeWithContentDescription("Lyrics").assertExists()
        composeRule.onNodeWithContentDescription("Collapse player").assertExists()
    }

    @Test
    fun artTapDispatchesPlayPauseOnce() {
        var toggles = 0
        setNowPlaying(onTogglePlayPause = { toggles += 1 })

        composeRule.onAllNodesWithContentDescription("Play or pause").onFirst().performClick()

        assertThat(toggles).isEqualTo(1)
    }

    @Test
    fun artHorizontalDragMapsLeftToNextAndRightToPrevious() {
        var next = 0
        var previous = 0
        setNowPlaying(onNext = { next += 1 }, onPrevious = { previous += 1 })
        val art = composeRule.onAllNodesWithContentDescription("Play or pause").onFirst()

        art.performTouchInput {
            swipe(
                start = center,
                end = center - Offset(width * 0.4f, 0f),
                durationMillis = 300,
            )
        }

        assertThat(next).isEqualTo(1)
        assertThat(previous).isEqualTo(0)

        art.performTouchInput {
            swipe(
                start = center,
                end = center + Offset(width * 0.4f, 0f),
                durationMillis = 300,
            )
        }

        assertThat(previous).isEqualTo(1)
        assertThat(next).isEqualTo(1)
    }

    @Test
    fun transportAndSecondaryButtonsDispatch() {
        var next = 0
        var previous = 0
        var queue = 0
        setNowPlaying(
            onNext = { next += 1 },
            onPrevious = { previous += 1 },
            onOpenQueue = { queue += 1 },
        )

        composeRule.onNodeWithContentDescription("Next").performClick()
        composeRule.onNodeWithContentDescription("Previous").performClick()
        composeRule.onNodeWithContentDescription("Queue").performClick()

        assertThat(next).isEqualTo(1)
        assertThat(previous).isEqualTo(1)
        assertThat(queue).isEqualTo(1)
    }

    @Test
    fun lyricsButtonTogglesThePane() {
        var toggles = 0
        setNowPlaying(onTogglePane = { toggles += 1 })

        composeRule.onNodeWithContentDescription("Lyrics").performClick()

        assertThat(toggles).isEqualTo(1)
    }

    @Test
    fun lyricsPaneShowsItsEmptyState() {
        setNowPlaying(pane = NowPlayingPane.LYRICS)

        composeRule.onNodeWithText("No lyrics for this track").assertExists()
    }

    @Test
    fun lyricsPaneRendersItsLines() {
        setNowPlaying(
            pane = NowPlayingPane.LYRICS,
            lyrics = LyricsUiState(lines = listOf("First light", "Last call")),
        )

        composeRule.onNodeWithText("First light").assertExists()
        composeRule.onNodeWithText("Last call").assertExists()
        composeRule.onNodeWithText("No lyrics for this track").assertDoesNotExist()
    }

    @Test
    fun theCoverPaneHidesTheLyricsPane() {
        setNowPlaying()

        composeRule.onNodeWithText("No lyrics for this track").assertDoesNotExist()
    }

    @Test
    fun nowPlayingEmptyStateOpensTheLibrary() {
        var opened = 0
        setNowPlaying(state = NowPlayingUiState(), onOpenLibrary = { opened += 1 })

        composeRule.onNodeWithText("Nothing playing yet").assertExists()
        composeRule.onNodeWithText("Open library").performClick()

        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun nowPlayingGestureTwinsHaveVisibleControls() {
        setNowPlaying()

        ButtonTwinAudit.assertTwinsPresent(
            composeRule.onRoot().fetchSemanticsNode(),
            listOf(
                GestureTwin("art-tap", "Play or pause", "Play or pause"),
                GestureTwin("art-previous", "Previous", "Previous"),
                GestureTwin("art-next", "Next", "Next"),
                GestureTwin("art-queue", "Queue", "Queue"),
                GestureTwin("art-collapse", "Collapse player", "Collapse player"),
            ),
        )
    }

    @Test
    fun queueRendersTheSummaryRowsAndCurrentMarker() {
        setQueue()

        composeRule.onNodeWithText("Playing 2 of 3").assertExists()
        composeRule.onNodeWithText("Opener").assertExists()
        composeRule.onNodeWithText("Night Ride").assertExists()
        composeRule.onNodeWithText("Encore").assertExists()
        composeRule.onAllNodesWithContentDescription("Now playing").onFirst().assertExists()
        composeRule.onNodeWithText("Clear remaining").assertIsEnabled()
    }

    @Test
    fun queueRowTapJumpsToThePlaybackOrderIndex() {
        val jumped = mutableListOf<Int>()
        setQueue(onJump = { jumped.add(it) })

        composeRule.onNodeWithText("Encore").performClick()

        assertThat(jumped).containsExactly(2)
    }

    @Test
    fun queueRemoveAndClearDispatch() {
        val removed = mutableListOf<Long>()
        var cleared = 0
        setQueue(onRemove = { removed.add(it) }, onClearRemaining = { cleared += 1 })

        composeRule.onNodeWithContentDescription("Remove Encore").performClick()
        composeRule.onNodeWithText("Clear remaining").performClick()

        assertThat(removed).containsExactly(3L)
        assertThat(cleared).isEqualTo(1)
    }

    @Test
    fun queueMoveActionsAreDisabledAtTheEnds() {
        setQueue()

        composeRule.onNodeWithContentDescription("More options for Opener").performClick()
        composeRule.onNodeWithText("Move Opener up").assertIsNotEnabled()
    }

    @Test
    fun queueGestureTwinsHaveVisibleControls() {
        setQueue()

        ButtonTwinAudit.assertTwinsPresent(
            composeRule.onRoot().fetchSemanticsNode(),
            listOf(
                GestureTwin("queue-remove", "Remove Opener", "Remove Opener"),
                GestureTwin("queue-remove", "Remove Night Ride", "Remove Night Ride"),
                GestureTwin("queue-remove", "Remove Encore", "Remove Encore"),
            ),
        )
    }

    private fun queueScrollOffsets(): List<Float> =
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.VerticalScrollAxisRange].value()
        }

    @Test
    fun queueRowSwipeRevealsTheDeleteBackgroundAndFollowsTheFinger() {
        val removed = mutableListOf<Long>()
        setQueue(onRemove = { removed.add(it) })
        val row = composeRule.onNodeWithTag("queue-swipe-reveal-3")
        val restingLeft = row.fetchSemanticsNode().boundsInRoot.left

        row.performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.3f, 0f))
        }
        composeRule.waitForIdle()

        assertThat(composeRule.onNodeWithText("Encore").fetchSemanticsNode().boundsInRoot.left)
            .isLessThan(restingLeft)

        row.performTouchInput { up() }
        composeRule.waitForIdle()

        assertThat(composeRule.onNodeWithText("Encore").fetchSemanticsNode().boundsInRoot.left)
            .isWithin(1f).of(restingLeft)
        assertThat(removed).isEmpty()
    }

    @Test
    fun queueSwipePastTheThresholdRemovesTheRow() {
        val removed = mutableListOf<Long>()
        setQueue(onRemove = { removed.add(it) })

        composeRule.onNodeWithTag("queue-swipe-reveal-3").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertThat(removed).containsExactly(3L)
    }

    @Test
    fun queueSwipeUnderTheThresholdKeepsTheRow() {
        val removed = mutableListOf<Long>()
        setQueue(onRemove = { removed.add(it) })
        val row = composeRule.onNodeWithTag("queue-swipe-reveal-3")

        row.performTouchInput {
            swipe(
                start = center,
                end = center - Offset(width * 0.2f, 0f),
                durationMillis = 300,
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Encore").assertExists()
        assertThat(removed).isEmpty()
    }

    @Test
    fun queueVerticalDragStillScrollsTheList() {
        setQueue(state = longQueueState(currentOrderIndex = 0))
        assertThat(queueScrollOffsets().all { it == 0f }).isTrue()

        composeRule.onNodeWithTag("queue-swipe-reveal-1").performTouchInput {
            swipeUp()
        }
        composeRule.waitForIdle()

        assertThat(queueScrollOffsets().any { it > 0f }).isTrue()
    }

    @Test
    fun queueJumpPillAppearsWhenTheCurrentRowIsOffScreen() {
        // Not revealed yet: the sheet keeps the list at the top while the queue
        // layer waits under now-playing, so the current row is off-screen.
        setQueue(state = longQueueState(currentOrderIndex = 40), isRevealed = false)

        composeRule.onNodeWithContentDescription("Jump to Track 41").assertExists()
    }

    @Test
    fun queueHasNoJumpPillWhenEveryRowFits() {
        setQueue()

        assertThat(
            composeRule.onAllNodesWithContentDescription("Jump to Night Ride")
                .fetchSemanticsNodes(),
        ).isEmpty()
    }

    @Test
    fun queueWithoutASheetHostStillOpensAtTheCurrentRow() {
        setQueue(state = longQueueState(currentOrderIndex = 40))

        composeRule.onNodeWithText("Track 41").assertExists()
        assertThat(composeRule.onAllNodesWithText("Track 1").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun queueRemovalShowsAnActionableUndo() {
        var undos = 0
        setQueue(
            state = queueState().copy(
                pendingUndo = PendingUndoUi(2, "Night Ride", "The Late Shift"),
            ),
            onUndo = { undos += 1 },
        )

        composeRule.onNodeWithText("Removed Night Ride").assertExists()
        composeRule.onNodeWithText("Undo").performClick()

        assertThat(undos).isEqualTo(1)
    }

    @Test
    fun queueSecondRemovalReplacesTheVisibleSnackbar() {
        val pending = mutableStateOf(PendingUndoUi(2, "Night Ride", "The Late Shift"))
        composeRule.setContent {
            OmnisonicTheme {
                QueueScreen(
                    state = queueState().copy(pendingUndo = pending.value),
                    onJump = {},
                    onRemove = {},
                    onUndo = {},
                    onUndoExpired = {},
                    onMoveUp = {},
                    onMoveDown = {},
                    onClearRemaining = {},
                    onBack = {},
                    onOpenLibrary = {},
                )
            }
        }
        composeRule.onNodeWithText("Removed Night Ride").assertExists()

        composeRule.runOnIdle { pending.value = PendingUndoUi(3, "Encore", "The Late Shift") }

        composeRule.onNodeWithText("Removed Encore").assertExists()
        composeRule.onNodeWithText("Removed Night Ride").assertDoesNotExist()
    }

    @Test
    fun queueSnackbarTimeoutExpiresThePendingUndoWithoutUndoing() {
        var undos = 0
        val expired = mutableListOf<Long>()
        setQueue(
            state = queueState().copy(
                pendingUndo = PendingUndoUi(2, "Night Ride", "The Late Shift"),
            ),
            onUndo = { undos += 1 },
            onUndoExpired = { expired.add(it) },
        )

        composeRule.mainClock.advanceTimeBy(12_000)
        composeRule.waitForIdle()

        assertThat(expired).containsExactly(2L)
        assertThat(undos).isEqualTo(0)
    }

    @Test
    fun queueHiddenMarkerStaysStaticWhilePlaying() {
        setQueue(
            state = queueState().copy(isPlaying = true),
            isRevealed = false,
        )

        composeRule.onAllNodesWithContentDescription("Now playing").onFirst().assertExists()
    }

    @Test
    fun nowPlayingIndicatorAnimatesOnlyWhenEveryGateHolds() {
        assertThat(
            nowPlayingIndicatorAnimates(
                isPlaying = true,
                active = true,
                durationScale = 1f,
                animatorsEnabled = true,
            ),
        ).isTrue()
        assertThat(
            nowPlayingIndicatorAnimates(
                isPlaying = false,
                active = true,
                durationScale = 1f,
                animatorsEnabled = true,
            ),
        ).isFalse()
        assertThat(
            nowPlayingIndicatorAnimates(
                isPlaying = true,
                active = false,
                durationScale = 1f,
                animatorsEnabled = true,
            ),
        ).isFalse()
        assertThat(
            nowPlayingIndicatorAnimates(
                isPlaying = true,
                active = true,
                durationScale = 0f,
                animatorsEnabled = true,
            ),
        ).isFalse()
        assertThat(
            nowPlayingIndicatorAnimates(
                isPlaying = true,
                active = true,
                durationScale = 1f,
                animatorsEnabled = false,
            ),
        ).isFalse()
    }

    @Test
    fun queueEmptyStateOpensTheLibrary() {
        var opened = 0
        setQueue(state = QueueUiState(), onOpenLibrary = { opened += 1 })

        composeRule.onNodeWithText("Nothing playing yet").assertExists()
        composeRule.onNodeWithText("Open library").performClick()

        assertThat(opened).isEqualTo(1)
    }
}
