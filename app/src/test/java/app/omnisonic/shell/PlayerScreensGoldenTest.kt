package app.omnisonic.shell

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.omnisonic.feature.library.nowplaying.NowPlayingEntry
import app.omnisonic.feature.library.nowplaying.NowPlayingUiState
import app.omnisonic.feature.library.queue.QueueRow
import app.omnisonic.feature.library.queue.QueueUiState
import app.omnisonic.feature.library.ui.NowPlayingScreen
import app.omnisonic.feature.library.ui.QueueScreen
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.ObservableStyleTokenSource
import com.github.takahirom.roborazzi.captureRoboImage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the player screens (D-14). Record deliberately with
 * `:app:recordRoborazziDebug`; plain `test` does not verify them — use
 * `:app:verifyRoborazziDebug` to compare (R-25). The theme comes from the
 * production source (D-105), so the goldens prove the fresh-install baseline.
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerScreensGoldenTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeRule = createComposeRule()

    @Inject
    lateinit var styleTokenSource: ObservableStyleTokenSource

    @Before
    fun inject() {
        hiltRule.inject()
    }

    private fun queueEntry(id: Long, title: String) = PlaybackQueueEntry(
        providerId = 1,
        externalId = "ext-$id",
        queueItemId = id,
        title = title,
        artistName = "The Late Shift",
        durationMs = 200_000,
    )

    @Test
    fun nowPlayingPortrait() {
        composeRule.setContent {
            OmnisonicTheme(source = styleTokenSource) {
                NowPlayingScreen(
                    state = NowPlayingUiState(
                        entry = NowPlayingEntry(
                            queueItemId = 7,
                            title = "Night Ride",
                            artistName = "The Late Shift",
                            cover = null,
                        ),
                        isPlaying = true,
                        positionMs = 61_000,
                        durationMs = 200_000,
                        repeatMode = RepeatMode.ALL,
                    ),
                    onTogglePlayPause = {},
                    onNext = {},
                    onPrevious = {},
                    onSeek = {},
                    onToggleShuffle = {},
                    onCycleRepeat = {},
                    onCollapse = {},
                    onOpenQueue = {},
                    onOpenLibrary = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onRoot().captureRoboImage("src/test/roborazzi/now_playing_portrait.png")
    }

    @Test
    fun queuePortrait() {
        composeRule.setContent {
            OmnisonicTheme(source = styleTokenSource) {
                QueueScreen(
                    state = QueueUiState(
                        rows = listOf(
                            QueueRow(queueEntry(1, "Opener"), orderIndex = 0, isCurrent = false),
                            QueueRow(queueEntry(2, "Night Ride"), orderIndex = 1, isCurrent = true),
                            QueueRow(queueEntry(3, "Encore"), orderIndex = 2, isCurrent = false),
                        ),
                        currentOrderIndex = 1,
                        shuffled = false,
                    ),
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
        composeRule.waitForIdle()

        composeRule.onRoot().captureRoboImage("src/test/roborazzi/queue_portrait.png")
    }
}
