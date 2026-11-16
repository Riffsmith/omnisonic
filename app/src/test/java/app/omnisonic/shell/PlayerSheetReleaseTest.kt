package app.omnisonic.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import app.omnisonic.feature.library.player.PlayerSheetStops
import app.omnisonic.style.OmnisonicTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Release contract of the sheet state (D-25): once the finger is gone the sheet
 * is at a detent and stays there. These tests drive [PlayerSheetState] directly
 * because the failure they cover is invisible to a synthetic gesture — a delta
 * that arrives after the lift has no event to carry it, so only a direct call
 * can reproduce the park it used to cause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlayerSheetReleaseTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var sheet: PlayerSheetState

    @Before
    fun setUp() {
        composeRule.setContent {
            OmnisonicTheme {
                val state = rememberPlayerSheetState()
                sheet = state
                Box(Modifier.fillMaxSize())
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { sheet.viewportPx = VIEWPORT_PX }
    }

    /**
     * Regression: a nested-scroll delta that lands after the queue reported the
     * lift used to re-enter the drag path, cancel the settle and leave the sheet
     * parked with no animation and no owner.
     */
    @Test
    fun `a delta after the queue release cannot park the sheet`() {
        openQueueTarget()

        composeRule.runOnUiThread {
            sheet.onPointerDown()
            sheet.dragFromSwipeUpTarget(160f)
            sheet.releaseFromSwipeUpTarget(0f, FLING_PX, COMMIT_PX)
            sheet.dragFromSwipeUpTarget(120f)
        }

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertSheetStaysAt(PlayerSheetStops.NOW_PLAYING)
    }

    @Test
    fun `a delta after a full-range release cannot park the sheet`() {
        composeRule.runOnUiThread { sheet.openNowPlaying() }
        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)

        composeRule.runOnUiThread {
            sheet.onPointerDown()
            sheet.drag(80f)
            sheet.release(0f, FLING_PX)
            sheet.drag(400f)
        }

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertSheetStaysAt(PlayerSheetStops.NOW_PLAYING)
    }

    /**
     * A pointer stream can end without the owning surface seeing the up (system
     * cancellation, focus loss, a second pointer lifting last). The root's
     * all-up still has to place the sheet on a detent.
     */
    @Test
    fun `a pull whose release never arrives still settles`() {
        openQueueTarget()

        composeRule.runOnUiThread {
            sheet.onPointerDown()
            sheet.dragFromSwipeUpTarget(160f)
            sheet.onAllPointersUp(FLING_PX, COMMIT_PX)
        }

        awaitSheetAt(PlayerSheetStops.NOW_PLAYING)
        assertSheetStaysAt(PlayerSheetStops.NOW_PLAYING)
    }

    @Test
    fun `a lost release before the commit distance snaps back`() {
        openQueueTarget()

        composeRule.runOnUiThread {
            sheet.onPointerDown()
            sheet.dragFromSwipeUpTarget(20f)
            sheet.onAllPointersUp(FLING_PX, COMMIT_PX)
        }

        awaitSheetAt(PlayerSheetStops.SWIPE_UP_TARGET)
        assertSheetStaysAt(PlayerSheetStops.SWIPE_UP_TARGET)
    }

    @Test
    fun `every release from a dragged position rests on a detent`() {
        val releases = listOf(0f, FLING_PX, -FLING_PX)
        for (pullPx in listOf(8f, 60f, 160f, 420f)) {
            for (velocity in releases) {
                openQueueTarget()
                composeRule.runOnUiThread {
                    sheet.onPointerDown()
                    sheet.dragFromSwipeUpTarget(pullPx)
                    sheet.releaseFromSwipeUpTarget(velocity, FLING_PX, COMMIT_PX)
                }
                Thread.sleep(220)
                composeRule.waitForIdle()
                assertThat(sheet.position).isIn(PlayerSheetStops.ORDERED)
            }
        }
    }

    private fun openQueueTarget() {
        composeRule.runOnUiThread { sheet.openSwipeUpTarget() }
        awaitSheetAt(PlayerSheetStops.SWIPE_UP_TARGET)
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

    private companion object {
        const val VIEWPORT_PX = 800f
        const val FLING_PX = 1_000f
        const val COMMIT_PX = 90f
    }
}
