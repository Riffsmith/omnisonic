package app.omnisonic.feature.library.player

/**
 * One vertical gesture on the swipe-up target's scrollable content
 * (ui/player-queue-return.md §3). The list owns the gesture unless it was
 * eligible when the finger went down — either at its very start, or still at
 * the position it was scrolled to on reveal (the current row second, with no
 * user scroll since). The queue opens there, so a true-top-only latch left the
 * return gesture dead on open for almost every queue (D-24).
 *
 * Eligibility is latched at the down and never re-read mid-gesture, so a fling
 * that reaches the top does not hand the rest of the drag to the sheet. Once
 * the user scrolls away from the reveal position, only the true top enrols.
 *
 * The gesture is also closed at the lift: [shouldHandOff] refuses every delta
 * after [onUp], so a nested-scroll callback that arrives once the finger is
 * gone cannot drive the sheet, and [onUp] reports the release to the sheet at
 * most once (D-25).
 *
 * Kept free of Compose state so the decisions are unit-testable; the caller
 * feeds it dispatched pointer and nested-scroll events.
 */
class QueuePullHandoff {

    /** Whether the content was eligible for the handoff when the gesture went down. */
    var eligibleAtDown = false
        private set

    /** Whether any delta has been forwarded to the sheet in this gesture. */
    var didHandOff = false
        private set

    /** True between the down and the lift; false for a gesture that never started. */
    var isOpen = false
        private set

    /**
     * [atTop] is the true list start; [atRevealPosition] is the unscrolled
     * reveal position, which the caller computes as "positioned on reveal and
     * the user has not scrolled since".
     */
    fun onDown(atTop: Boolean, atRevealPosition: Boolean) {
        isOpen = true
        eligibleAtDown = atTop || atRevealPosition
        didHandOff = false
    }

    /** Downward deltas only: upward ones scroll the list and always belong to it. */
    fun shouldHandOff(deltaY: Float): Boolean = isOpen && eligibleAtDown && deltaY > 0f

    fun onHandedOff() {
        didHandOff = true
    }

    /**
     * Closes the gesture and reports whether the sheet owns the release. A
     * second call for the same gesture answers false, so a duplicated lift
     * cannot settle the sheet twice.
     */
    fun onUp(): Boolean {
        val owned = isOpen && didHandOff
        isOpen = false
        didHandOff = false
        return owned
    }

    /** The pointer stream ended without a lift (second pointer, system cancel). */
    fun onCancel() {
        isOpen = false
        didHandOff = false
    }
}
