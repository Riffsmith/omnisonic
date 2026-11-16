package app.omnisonic.feature.library.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Ownership of one queue pull (ui/player-queue-return.md §3, D-25): the latch is
 * taken at the down, the gesture is closed at the lift, and nothing after the
 * lift can move the sheet.
 */
class QueuePullHandoffTest {

    private fun handoff(atTop: Boolean = false, atRevealPosition: Boolean = false) =
        QueuePullHandoff().apply { onDown(atTop, atRevealPosition) }

    @Test
    fun `the true top of the list hands the downward drag to the sheet`() {
        val pull = handoff(atTop = true)

        assertThat(pull.shouldHandOff(12f)).isTrue()
    }

    @Test
    fun `the reveal position hands the downward drag to the sheet`() {
        val pull = handoff(atRevealPosition = true)

        assertThat(pull.shouldHandOff(12f)).isTrue()
    }

    @Test
    fun `a list scrolled away from the reveal position keeps the gesture`() {
        val pull = handoff()

        assertThat(pull.shouldHandOff(12f)).isFalse()
        assertThat(pull.shouldHandOff(-12f)).isFalse()
    }

    @Test
    fun `upward deltas always scroll the list`() {
        val pull = handoff(atTop = true)

        assertThat(pull.shouldHandOff(-8f)).isFalse()
    }

    @Test
    fun `eligibility is latched at the down`() {
        // The latch is read once: a gesture that becomes eligible mid-flight
        // (a fling reaching the top) still belongs to the list.
        val pull = handoff()
        assertThat(pull.eligibleAtDown).isFalse()

        assertThat(pull.shouldHandOff(40f)).isFalse()
    }

    @Test
    fun `a delta after the lift is refused`() {
        val pull = handoff(atTop = true)
        assertThat(pull.shouldHandOff(40f)).isTrue()
        pull.onHandedOff()

        assertThat(pull.onUp()).isTrue()

        // The nested-scroll callback that can arrive once the finger is gone.
        assertThat(pull.shouldHandOff(400f)).isFalse()
        assertThat(pull.isOpen).isFalse()
    }

    @Test
    fun `a lift without a handoff is not reported to the sheet`() {
        val pull = handoff(atTop = true)

        assertThat(pull.shouldHandOff(-40f)).isFalse()
        assertThat(pull.onUp()).isFalse()
    }

    @Test
    fun `a duplicate lift is reported once`() {
        val pull = handoff(atTop = true)
        pull.shouldHandOff(40f)
        pull.onHandedOff()

        assertThat(pull.onUp()).isTrue()
        assertThat(pull.onUp()).isFalse()
    }

    @Test
    fun `a new down reopens the gesture`() {
        val pull = handoff(atTop = true)
        pull.shouldHandOff(40f)
        pull.onHandedOff()
        pull.onUp()

        pull.onDown(atTop = true, atRevealPosition = false)

        assertThat(pull.isOpen).isTrue()
        assertThat(pull.didHandOff).isFalse()
        assertThat(pull.shouldHandOff(40f)).isTrue()
    }

    @Test
    fun `a cancelled gesture stops handing off`() {
        val pull = handoff(atTop = true)
        pull.shouldHandOff(40f)
        pull.onHandedOff()

        pull.onCancel()

        assertThat(pull.shouldHandOff(400f)).isFalse()
        assertThat(pull.onUp()).isFalse()
    }
}
