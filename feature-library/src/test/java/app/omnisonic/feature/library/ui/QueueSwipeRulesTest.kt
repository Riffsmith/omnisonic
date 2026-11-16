package app.omnisonic.feature.library.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Threshold, clamp and reserved-direction rules of the queue row swipe
 * (ui/queue-swipe.md §2): a release past half the row commits the delete and
 * springs off-screen, anything shorter springs back, positive travel is
 * refused while the start-to-end direction is reserved, and both rules are
 * total for every offset they can see.
 */
class QueueSwipeRulesTest {

    private val width = 800f
    private val threshold = QueueSwipeRules.DELETE_THRESHOLD_FRACTION

    @Test
    fun `a right-to-left drag follows the finger`() {
        assertThat(
            QueueSwipeRules.dragOffset(current = 0f, deltaXPx = -120f, rowWidthPx = width),
        ).isEqualTo(-120f)
    }

    @Test
    fun `the live offset clamps at the full row width`() {
        assertThat(
            QueueSwipeRules.dragOffset(current = -700f, deltaXPx = -900f, rowWidthPx = width),
        ).isEqualTo(-width)
    }

    @Test
    fun `positive travel stays at rest while the second direction is reserved`() {
        assertThat(
            QueueSwipeRules.dragOffset(current = 0f, deltaXPx = 300f, rowWidthPx = width),
        ).isEqualTo(0f)
        assertThat(
            QueueSwipeRules.dragOffset(current = -40f, deltaXPx = 300f, rowWidthPx = width),
        ).isEqualTo(0f)
    }

    @Test
    fun `positive travel is allowed once the second direction is enabled`() {
        assertThat(
            QueueSwipeRules.dragOffset(
                current = 0f,
                deltaXPx = 300f,
                rowWidthPx = width,
                startToEndEnabled = true,
            ),
        ).isEqualTo(300f)
    }

    @Test
    fun `a release under the threshold springs back`() {
        assertThat(QueueSwipeRules.commitsOnRelease(-399f, width, threshold)).isFalse()
        assertThat(QueueSwipeRules.settleTarget(-399f, width, threshold)).isEqualTo(0f)
    }

    @Test
    fun `a release at the threshold commits off-screen`() {
        assertThat(QueueSwipeRules.commitsOnRelease(-width * threshold, width, threshold)).isTrue()
        assertThat(QueueSwipeRules.settleTarget(-width * threshold, width, threshold))
            .isEqualTo(-width)
    }

    @Test
    fun `a release past the threshold commits off-screen`() {
        assertThat(QueueSwipeRules.settleTarget(-700f, width, threshold)).isEqualTo(-width)
    }

    @Test
    fun `a committed release settles in the dragged direction`() {
        assertThat(
            QueueSwipeRules.settleTarget(
                offsetPx = 600f,
                rowWidthPx = width,
                thresholdFraction = threshold,
            ),
        ).isEqualTo(width)
    }

    @Test
    fun `every release settles either at rest or off-screen`() {
        val offsets = generateSequence(-width) { it + 10f }.takeWhile { it <= 0f }
        for (offset in offsets) {
            assertThat(QueueSwipeRules.settleTarget(offset, width, threshold))
                .isIn(listOf(0f, -width))
        }
    }

    @Test
    fun `a positive row width is required`() {
        val drag = runCatching {
            QueueSwipeRules.dragOffset(current = 0f, deltaXPx = -10f, rowWidthPx = 0f)
        }.exceptionOrNull()
        val release = runCatching {
            QueueSwipeRules.commitsOnRelease(-10f, rowWidthPx = 0f, thresholdFraction = threshold)
        }.exceptionOrNull()

        assertThat(drag).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(release).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a positive threshold is required`() {
        val thrown = runCatching {
            QueueSwipeRules.commitsOnRelease(-10f, width, thresholdFraction = 0f)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }
}
