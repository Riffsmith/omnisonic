package app.omnisonic.feature.library.player

import kotlin.math.abs

/**
 * Vertical detents of the player surface, expressed in fractions of the sheet
 * viewport: `-1` = the swipe-up target fully shown, `0` = now-playing fully
 * shown, `1` = peek (the sheet off-screen, only the mini player visible).
 *
 * A detent's value is also its rest position, which is what the shell's sheet
 * state writes on every release (ui/player-sheet.md §2).
 */
object PlayerSheetStops {
    const val SWIPE_UP_TARGET = -1f
    const val NOW_PLAYING = 0f
    const val PEEK = 1f

    val ORDERED: List<Float> = listOf(SWIPE_UP_TARGET, NOW_PLAYING, PEEK)
}

/**
 * Drag and release rules of the player sheet (ui/player-sheet.md §2). Pure
 * functions over screen-height fractions so the whole motion contract is
 * unit-testable, and total on purpose: every release rule returns one of
 * [PlayerSheetStops.ORDERED], so a lifted finger can never leave the sheet
 * parked between detents (D-25).
 *
 * Signs follow Compose: an upward drag has a negative `dy` and a negative
 * velocity, a downward one is positive.
 */
object PlayerSheetRules {

    /** Not enough movement to be a different detent. */
    private const val DETENT_EPSILON = 0.001f

    /** Live drag position, clamped to the outermost detents. */
    fun dragPosition(current: Float, deltaYPx: Float, viewportPx: Float): Float {
        require(viewportPx > 0f) { "viewportPx must be positive (was $viewportPx)" }
        return (current + deltaYPx / viewportPx)
            .coerceIn(PlayerSheetStops.SWIPE_UP_TARGET, PlayerSheetStops.PEEK)
    }

    /**
     * Live drag of a gesture that started at the top of the swipe-up target's
     * content: the same finger-following movement as any other sheet drag, but
     * the sheet can never travel past now-playing, so continuing to pull after
     * the player arrives does nothing instead of collapsing the sheet.
     */
    fun dragPositionFromSwipeUpTarget(
        current: Float,
        deltaYPx: Float,
        viewportPx: Float,
    ): Float = dragPosition(current, deltaYPx, viewportPx)
        .coerceIn(PlayerSheetStops.SWIPE_UP_TARGET, PlayerSheetStops.NOW_PLAYING)

    /**
     * Release of an unbounded drag (mini bar, now-playing surface, empty
     * target): a fling carries the sheet one detent in the direction it was
     * thrown, anything slower settles on the nearest detent. [flingVelocityPx]
     * is the device fling gate from `ViewConfiguration`, passed in rather than
     * baked into a fraction so every sheet path uses one threshold.
     */
    fun settleTarget(
        position: Float,
        velocityYPx: Float,
        flingVelocityPx: Float,
    ): Float = when {
        velocityYPx >= flingVelocityPx -> stopBeyond(position, direction = 1f)
        velocityYPx <= -flingVelocityPx -> stopBeyond(position, direction = -1f)
        else -> nearestStop(position)
    }

    /**
     * Release of a pull that started at the top of the swipe-up target's
     * content, bounded to `[SWIPE_UP_TARGET, NOW_PLAYING]`. A held-then-lifted
     * pull reports almost no velocity, so a nearest-detent rule would need half
     * the viewport of travel before one deliberate pull returns
     * (ui/player-queue-return.md §4.1). Distance decides instead: past
     * `commitDistanceFraction` of the viewport the pull commits to
     * [PlayerSheetStops.NOW_PLAYING], so anything beyond a tiny twitch returns
     * on the first try. A fling still wins over distance, in its own direction.
     */
    fun pullTarget(
        position: Float,
        velocityYPx: Float,
        flingVelocityPx: Float,
        commitDistanceFraction: Float,
    ): Float {
        require(commitDistanceFraction > 0f) {
            "commitDistanceFraction must be positive (was $commitDistanceFraction)"
        }
        val commitPosition = PlayerSheetStops.SWIPE_UP_TARGET + commitDistanceFraction
        return when {
            velocityYPx >= flingVelocityPx -> PlayerSheetStops.NOW_PLAYING
            velocityYPx <= -flingVelocityPx -> PlayerSheetStops.SWIPE_UP_TARGET
            position >= commitPosition - DETENT_EPSILON -> PlayerSheetStops.NOW_PLAYING
            else -> PlayerSheetStops.SWIPE_UP_TARGET
        }
    }

    /**
     * System back and the in-screen arrow share one chain: the swipe-up target
     * returns to now-playing, everything else peeks (ui/player-queue-return.md
     * §4).
     */
    fun backDestination(position: Float): Float =
        if (position < PlayerSheetStops.NOW_PLAYING - DETENT_EPSILON) {
            PlayerSheetStops.NOW_PLAYING
        } else {
            PlayerSheetStops.PEEK
        }

    private fun nearestStop(position: Float): Float =
        PlayerSheetStops.ORDERED.minByOrNull { stop -> abs(stop - position) }
            ?: PlayerSheetStops.NOW_PLAYING

    /** The next detent strictly past [position] in [direction], or the end of the range. */
    private fun stopBeyond(position: Float, direction: Float): Float {
        val ordered = if (direction > 0f) {
            PlayerSheetStops.ORDERED
        } else {
            PlayerSheetStops.ORDERED.asReversed()
        }
        return ordered.firstOrNull { stop -> (stop - position) * direction > DETENT_EPSILON }
            ?: ordered.last()
    }
}
