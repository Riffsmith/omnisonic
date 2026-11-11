package app.omnisonic.feature.library.miniplayer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Expand/collapse state machine of the mini-player drag surface
 * (GESTURES.md §4.4; velocity-settle delegates to the SwipeZone contract).
 */
class MiniPlayerExpandRulesTest {

    private val travel = 64f

    @Test
    fun `upward drag expands and downward drag contracts`() {
        assertThat(
            MiniPlayerExpandRules.dragProgress(current = 0f, dyPx = -32f, travelPx = travel),
        ).isWithin(0.001f).of(0.5f)
        assertThat(
            MiniPlayerExpandRules.dragProgress(current = 0.5f, dyPx = 32f, travelPx = travel),
        ).isWithin(0.001f).of(0f)
        assertThat(
            MiniPlayerExpandRules.dragProgress(current = 0.5f, dyPx = -16f, travelPx = travel),
        ).isWithin(0.001f).of(0.75f)
    }

    @Test
    fun `drag progress clamps to the collapsed and expanded ends`() {
        assertThat(
            MiniPlayerExpandRules.dragProgress(current = 0.9f, dyPx = -500f, travelPx = travel),
        ).isEqualTo(MiniPlayerExpandRules.EXPANDED)
        assertThat(
            MiniPlayerExpandRules.dragProgress(current = 0.1f, dyPx = 500f, travelPx = travel),
        ).isEqualTo(MiniPlayerExpandRules.COLLAPSED)
    }

    @Test
    fun `drag progress requires a positive travel distance`() {
        val thrown = runCatching {
            MiniPlayerExpandRules.dragProgress(current = 0f, dyPx = -10f, travelPx = 0f)
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an upward fling expands regardless of distance travelled`() {
        assertThat(
            MiniPlayerExpandRules.settleTarget(
                velocityYPx = -1_500f,
                fraction = 0.05f,
                velocityThresholdPx = 300f,
            ),
        ).isEqualTo(MiniPlayerSettle.EXPANDED)
    }

    @Test
    fun `a downward fling collapses regardless of distance travelled`() {
        assertThat(
            MiniPlayerExpandRules.settleTarget(
                velocityYPx = 1_500f,
                fraction = 0.95f,
                velocityThresholdPx = 300f,
            ),
        ).isEqualTo(MiniPlayerSettle.COLLAPSED)
    }

    @Test
    fun `a slow release settles by the side of the halfway point`() {
        assertThat(
            MiniPlayerExpandRules.settleTarget(
                velocityYPx = 0f,
                fraction = 0.6f,
                velocityThresholdPx = 300f,
            ),
        ).isEqualTo(MiniPlayerSettle.EXPANDED)
        assertThat(
            MiniPlayerExpandRules.settleTarget(
                velocityYPx = 0f,
                fraction = 0.4f,
                velocityThresholdPx = 300f,
            ),
        ).isEqualTo(MiniPlayerSettle.COLLAPSED)
    }
}
