package app.omnisonic.feature.library.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Detents, the drag clamps and both release rules of the player sheet
 * (ui/player-sheet.md §2, D-25): the pixel-for-pixel drag, the pulled queue
 * (never past now-playing), the full-range fling/nearest rule and the back
 * chain. Every release rule is total — it returns a detent for any input —
 * because that is what keeps a lifted finger from parking the sheet.
 */
class PlayerSheetRulesTest {

    private val viewport = 800f
    private val fling = 1_000f

    @Test
    fun `an upward drag moves the sheet toward the swipe-up target`() {
        assertThat(
            PlayerSheetRules.dragPosition(
                current = PlayerSheetStops.NOW_PLAYING,
                deltaYPx = -200f,
                viewportPx = viewport,
            ),
        ).isWithin(0.001f).of(-0.25f)
    }

    @Test
    fun `a downward drag moves the sheet toward the peek detent`() {
        assertThat(
            PlayerSheetRules.dragPosition(
                current = PlayerSheetStops.NOW_PLAYING,
                deltaYPx = 160f,
                viewportPx = viewport,
            ),
        ).isWithin(0.001f).of(0.2f)
    }

    @Test
    fun `the drag position clamps between the outermost detents`() {
        assertThat(
            PlayerSheetRules.dragPosition(current = 0f, deltaYPx = -5_000f, viewportPx = viewport),
        ).isEqualTo(PlayerSheetStops.SWIPE_UP_TARGET)
        assertThat(
            PlayerSheetRules.dragPosition(current = 0f, deltaYPx = 5_000f, viewportPx = viewport),
        ).isEqualTo(PlayerSheetStops.PEEK)
    }

    @Test
    fun `a positive viewport is required`() {
        val thrown = runCatching {
            PlayerSheetRules.dragPosition(current = 0f, deltaYPx = -10f, viewportPx = 0f)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a slow pull past the commit distance returns to now-playing`() {
        // 200 of 800 px: a quarter viewport, far short of the half-viewport
        // nearest-detent rule that made one slow pull snap back (D-21).
        assertThat(
            PlayerSheetRules.pullTarget(
                position = -0.75f,
                velocityYPx = 0f,
                flingVelocityPx = 300f,
                commitDistanceFraction = 0.04f,
            ),
        ).isEqualTo(PlayerSheetStops.NOW_PLAYING)
    }

    @Test
    fun `a tiny slow twitch at the swipe-up target snaps back`() {
        assertThat(
            PlayerSheetRules.pullTarget(
                position = -0.99f,
                velocityYPx = 0f,
                flingVelocityPx = 300f,
                commitDistanceFraction = 0.04f,
            ),
        ).isEqualTo(PlayerSheetStops.SWIPE_UP_TARGET)
    }

    @Test
    fun `a down-fling from the swipe-up target returns without waiting for distance`() {
        assertThat(
            PlayerSheetRules.pullTarget(
                position = PlayerSheetStops.SWIPE_UP_TARGET,
                velocityYPx = fling,
                flingVelocityPx = 300f,
                commitDistanceFraction = 0.04f,
            ),
        ).isEqualTo(PlayerSheetStops.NOW_PLAYING)
    }

    @Test
    fun `an up-flick cancels a pull past the commit distance`() {
        assertThat(
            PlayerSheetRules.pullTarget(
                position = -0.4f,
                velocityYPx = -fling,
                flingVelocityPx = 300f,
                commitDistanceFraction = 0.04f,
            ),
        ).isEqualTo(PlayerSheetStops.SWIPE_UP_TARGET)
    }

    @Test
    fun `a swipe-up-target release never reaches peek`() {
        assertThat(
            PlayerSheetRules.pullTarget(
                position = PlayerSheetStops.NOW_PLAYING,
                velocityYPx = fling * 3f,
                flingVelocityPx = 300f,
                commitDistanceFraction = 0.04f,
            ),
        ).isEqualTo(PlayerSheetStops.NOW_PLAYING)
    }

    @Test
    fun `a positive commit distance is required`() {
        val thrown = runCatching {
            PlayerSheetRules.pullTarget(
                position = -0.5f,
                velocityYPx = 0f,
                flingVelocityPx = 300f,
                commitDistanceFraction = 0f,
            )
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a swipe-up-target drag stops at now-playing and never pulls away`() {
        assertThat(
            PlayerSheetRules.dragPositionFromSwipeUpTarget(
                current = PlayerSheetStops.SWIPE_UP_TARGET,
                deltaYPx = 200f,
                viewportPx = viewport,
            ),
        ).isWithin(0.001f).of(-0.75f)
        assertThat(
            PlayerSheetRules.dragPositionFromSwipeUpTarget(
                current = PlayerSheetStops.SWIPE_UP_TARGET,
                deltaYPx = 5_000f,
                viewportPx = viewport,
            ),
        ).isEqualTo(PlayerSheetStops.NOW_PLAYING)
        assertThat(
            PlayerSheetRules.dragPositionFromSwipeUpTarget(
                current = PlayerSheetStops.NOW_PLAYING,
                deltaYPx = 400f,
                viewportPx = viewport,
            ),
        ).isEqualTo(PlayerSheetStops.NOW_PLAYING)
    }

    @Test
    fun `a slow full-range release settles on the nearest detent`() {
        assertThat(PlayerSheetRules.settleTarget(-0.2f, velocityYPx = 0f, flingVelocityPx = fling))
            .isEqualTo(PlayerSheetStops.NOW_PLAYING)
        assertThat(PlayerSheetRules.settleTarget(0.2f, velocityYPx = 0f, flingVelocityPx = fling))
            .isEqualTo(PlayerSheetStops.NOW_PLAYING)
        assertThat(PlayerSheetRules.settleTarget(-0.8f, velocityYPx = 0f, flingVelocityPx = fling))
            .isEqualTo(PlayerSheetStops.SWIPE_UP_TARGET)
        assertThat(PlayerSheetRules.settleTarget(0.8f, velocityYPx = 0f, flingVelocityPx = fling))
            .isEqualTo(PlayerSheetStops.PEEK)
    }

    @Test
    fun `a full-range fling carries the sheet one detent in its own direction`() {
        assertThat(PlayerSheetRules.settleTarget(-0.1f, velocityYPx = fling, flingVelocityPx = fling))
            .isEqualTo(PlayerSheetStops.NOW_PLAYING)
        assertThat(PlayerSheetRules.settleTarget(0f, velocityYPx = fling, flingVelocityPx = fling))
            .isEqualTo(PlayerSheetStops.PEEK)
        assertThat(PlayerSheetRules.settleTarget(0f, velocityYPx = -fling, flingVelocityPx = fling))
            .isEqualTo(PlayerSheetStops.SWIPE_UP_TARGET)
        assertThat(
            PlayerSheetRules.settleTarget(
                PlayerSheetStops.PEEK,
                velocityYPx = fling,
                flingVelocityPx = fling,
            ),
        ).isEqualTo(PlayerSheetStops.PEEK)
    }

    @Test
    fun `every release from any position lands exactly on a detent`() {
        val positions = generateSequence(-1f) { it + 0.05f }.takeWhile { it <= 1.0001f }
        val velocities = listOf(-5_000f, -fling, -1f, 0f, 1f, fling, 5_000f)
        for (position in positions) {
            for (velocity in velocities) {
                assertThat(PlayerSheetRules.settleTarget(position, velocity, fling))
                    .isIn(PlayerSheetStops.ORDERED)
                assertThat(PlayerSheetRules.pullTarget(position, velocity, fling, 0.04f))
                    .isIn(PlayerSheetStops.ORDERED)
            }
        }
    }

    @Test
    fun `back returns the swipe-up target to now-playing and everything else peeks`() {
        assertThat(PlayerSheetRules.backDestination(PlayerSheetStops.SWIPE_UP_TARGET))
            .isEqualTo(PlayerSheetStops.NOW_PLAYING)
        assertThat(PlayerSheetRules.backDestination(-0.4f))
            .isEqualTo(PlayerSheetStops.NOW_PLAYING)
        assertThat(PlayerSheetRules.backDestination(PlayerSheetStops.NOW_PLAYING))
            .isEqualTo(PlayerSheetStops.PEEK)
        assertThat(PlayerSheetRules.backDestination(0.4f))
            .isEqualTo(PlayerSheetStops.PEEK)
        assertThat(PlayerSheetRules.backDestination(PlayerSheetStops.PEEK))
            .isEqualTo(PlayerSheetStops.PEEK)
    }
}
