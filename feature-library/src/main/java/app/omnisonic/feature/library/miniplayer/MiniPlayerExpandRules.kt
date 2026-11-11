package app.omnisonic.feature.library.miniplayer

import app.omnisonic.style.SwipeZoneRules

/** Where a released mini-player drag settles (UI_SPEC.md §1.1 rule 4). */
enum class MiniPlayerSettle { COLLAPSED, EXPANDED }

/**
 * Expand/collapse state machine of the mini-player drag surface
 * (reference/docs/GESTURES.md §4.4, UI_SPEC.md §3.10). Pure logic over
 * pixel deltas and velocity so the feel is unit-testable; signing follows
 * Compose: an upward drag has a negative `dy` and a negative velocity.
 */
object MiniPlayerExpandRules {

    const val COLLAPSED = 0f
    const val EXPANDED = 1f

    /** Drag progress in 0..1; upward travel expands. */
    fun dragProgress(current: Float, dyPx: Float, travelPx: Float): Float {
        require(travelPx > 0f) { "travelPx must be positive (was $travelPx)" }
        return (current - dyPx / travelPx).coerceIn(COLLAPSED, EXPANDED)
    }

    /**
     * Velocity settle per the SwipeZone contract: a fling decides by
     * direction, a slow release by the side of the halfway point the drag
     * already passed.
     */
    fun settleTarget(
        velocityYPx: Float,
        fraction: Float,
        velocityThresholdPx: Float,
    ): MiniPlayerSettle =
        if (SwipeZoneRules.velocitySettle(-velocityYPx, fraction, velocityThresholdPx) >= EXPANDED) {
            MiniPlayerSettle.EXPANDED
        } else {
            MiniPlayerSettle.COLLAPSED
        }
}
