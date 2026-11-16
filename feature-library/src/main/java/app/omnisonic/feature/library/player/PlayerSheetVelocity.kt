package app.omnisonic.feature.library.player

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * Release velocity of a player-sheet drag, in Compose-signed px/s (an upward
 * gesture is negative). Used by the mini player and the now-playing surface
 * before handing the value to [PlayerSheetRules.settleTarget]
 * (ui/player-sheet.md §2).
 *
 * A bare [VelocityTracker] is not enough for this hand-rolled
 * `awaitEachGesture` loop, and both gaps below made a fast flick read as a
 * stationary release:
 *
 * - Coalesced moves carry history in [PointerInputChange.historical]; feed it
 *   in, exactly like the foundation drag detectors do.
 * - Compose's default Lsq2 estimator needs three samples and silently returns
 *   zero for fewer (a quadratic fit over two points is underdetermined). A
 *   flick delivered as a down plus one move therefore has no measured speed.
 *   When the whole gesture produced fewer than three samples, the exact
 *   two-sample velocity is used instead.
 */
class PlayerSheetVelocity {

    private val tracker = VelocityTracker()
    private var sampleCount = 0
    private var firstTimeMillis = 0L
    private var firstYPx = 0f
    private var lastTimeMillis = 0L
    private var lastYPx = 0f

    /** Adds one dispatched change: the down, every move, and the up. */
    @OptIn(ExperimentalComposeUiApi::class)
    fun add(change: PointerInputChange) {
        if (change.changedToDownIgnoreConsumed()) {
            tracker.resetTracking()
            sampleCount = 0
        }
        if (change.pressed) {
            change.historical.forEach {
                tracker.addPosition(it.uptimeMillis, it.position)
                addSample(it.uptimeMillis, it.position.y)
            }
            tracker.addPosition(change.uptimeMillis, change.position)
            addSample(change.uptimeMillis, change.position.y)
        }
    }

    /** Velocity of the latest movement in px/s; negative when moving up. */
    fun velocityY(): Float {
        val velocity = tracker.calculateVelocity().y
        if (velocity != 0f || sampleCount >= LEAST_SQUARES_MIN_SAMPLES) return velocity
        val elapsedMillis = lastTimeMillis - firstTimeMillis
        if (elapsedMillis <= 0L) return 0f
        return (lastYPx - firstYPx) * 1000f / elapsedMillis
    }

    private fun addSample(timeMillis: Long, yPx: Float) {
        if (sampleCount == 0) {
            firstTimeMillis = timeMillis
            firstYPx = yPx
        }
        lastTimeMillis = timeMillis
        lastYPx = yPx
        sampleCount++
    }

    private companion object {
        /** A quadratic least-squares fit is underdetermined below three samples. */
        const val LEAST_SQUARES_MIN_SAMPLES = 3
    }
}
