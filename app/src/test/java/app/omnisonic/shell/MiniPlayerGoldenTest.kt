package app.omnisonic.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.omnisonic.feature.library.miniplayer.MiniPlayerUiState
import app.omnisonic.feature.library.nowplaying.NowPlayingEntry
import app.omnisonic.feature.library.ui.MiniPlayerBar
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.rememberSwipeZoneRegistry
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the mini-player bar's resting states (D-14). Record
 * deliberately with `:app:recordRoborazziDebug`; plain `test` compares (R-25).
 *
 * The "expanded transition" has no separate resting frame to capture: the
 * settle animation runs before the shell swaps the bar for the now-playing
 * route, whose golden (`now_playing_portrait.png`) is the expanded state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiniPlayerGoldenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setBar(state: MiniPlayerUiState) {
        composeRule.setContent {
            OmnisonicTheme {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    MiniPlayerBar(
                        state = state,
                        swipeZoneRegistry = rememberSwipeZoneRegistry(),
                        onTogglePlayPause = {},
                        onNext = {},
                        onExpand = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun miniPlayerBarVisible() {
        setBar(
            MiniPlayerUiState(
                entry = NowPlayingEntry(
                    queueItemId = 7,
                    title = "Night Ride",
                    artistName = "The Late Shift",
                    cover = null,
                ),
                isPlaying = true,
            ),
        )

        composeRule.onRoot().captureRoboImage("src/test/roborazzi/mini_player_bar_visible.png")
    }

    @Test
    fun miniPlayerBarHidden() {
        setBar(MiniPlayerUiState())

        composeRule.onRoot().captureRoboImage("src/test/roborazzi/mini_player_bar_hidden.png")
    }
}
