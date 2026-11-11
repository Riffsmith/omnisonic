package app.omnisonic.shell

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.omnisonic.feature.library.nowplaying.NowPlayingEntry
import app.omnisonic.feature.library.nowplaying.NowPlayingUiState
import app.omnisonic.feature.library.queue.QueueRow
import app.omnisonic.feature.library.queue.QueueUiState
import app.omnisonic.feature.library.ui.NowPlayingScreen
import app.omnisonic.feature.library.ui.QueueScreen
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
                )
            }
        }
    }

    private fun setQueue(
        state: QueueUiState = queueState(),
        onJump: (Int) -> Unit = {},
        onRemove: (Long) -> Unit = {},
        onMoveUp: (Long) -> Unit = {},
        onMoveDown: (Long) -> Unit = {},
        onClearRemaining: () -> Unit = {},
        onBack: () -> Unit = {},
        onOpenLibrary: () -> Unit = {},
    ) {
        composeRule.setContent {
            OmnisonicTheme {
                QueueScreen(
                    state = state,
                    onJump = onJump,
                    onRemove = onRemove,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onClearRemaining = onClearRemaining,
                    onBack = onBack,
                    onOpenLibrary = onOpenLibrary,
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

    @Test
    fun queueEmptyStateOpensTheLibrary() {
        var opened = 0
        setQueue(state = QueueUiState(), onOpenLibrary = { opened += 1 })

        composeRule.onNodeWithText("Nothing playing yet").assertExists()
        composeRule.onNodeWithText("Open library").performClick()

        assertThat(opened).isEqualTo(1)
    }
}
