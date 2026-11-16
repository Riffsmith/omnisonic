package app.omnisonic.feature.library.player

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.HistoricalChange
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Release-velocity sampling of the player sheet (ui/player-sheet.md §2). The
 * regression that motivated the two-sample fallback: Compose's default Lsq2
 * estimator returns 0 for a down plus one move, so a quick flick that produced
 * a single coalesced move was released as stationary speed and snapped back.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PlayerSheetVelocityTest {

    @Test
    fun `two samples keep the exact average velocity of a fast flick`() {
        val velocity = PlayerSheetVelocity().apply {
            add(down(timeMillis = 0L, y = 1_000f))
            add(move(timeMillis = 16L, y = 720f, previousY = 1_000f))
        }.velocityY()

        assertThat(velocity).isWithin(0.5f).of(-17_500f)
    }

    @Test
    fun `many samples report the direction of travel`() {
        val up = PlayerSheetVelocity().apply {
            add(down(timeMillis = 0L, y = 1_000f))
            add(move(timeMillis = 16L, y = 900f, previousY = 1_000f))
            add(move(timeMillis = 32L, y = 800f, previousY = 900f))
            add(move(timeMillis = 48L, y = 700f, previousY = 800f))
        }.velocityY()
        val down = PlayerSheetVelocity().apply {
            add(down(timeMillis = 0L, y = 700f))
            add(move(timeMillis = 16L, y = 800f, previousY = 700f))
            add(move(timeMillis = 32L, y = 900f, previousY = 800f))
            add(move(timeMillis = 48L, y = 1_000f, previousY = 900f))
        }.velocityY()

        assertThat(up).isLessThan(-5_000f)
        assertThat(down).isGreaterThan(5_000f)
    }

    @Test
    fun `a stationary two-sample release has no velocity`() {
        val velocity = PlayerSheetVelocity().apply {
            add(down(timeMillis = 0L, y = 1_000f))
            add(move(timeMillis = 16L, y = 1_000f, previousY = 1_000f))
        }.velocityY()

        assertThat(velocity).isWithin(0.5f).of(0f)
    }

    @Test
    fun `samples sharing a timestamp do not divide by zero`() {
        val velocity = PlayerSheetVelocity().apply {
            add(down(timeMillis = 0L, y = 1_000f))
            add(move(timeMillis = 0L, y = 700f, previousY = 1_000f))
        }.velocityY()

        assertThat(velocity).isWithin(0.5f).of(0f)
    }

    @Test
    fun `historical samples carry the late movement of a coalesced flick`() {
        val velocity = PlayerSheetVelocity().apply {
            add(down(timeMillis = 0L, y = 1_000f))
            add(
                move(
                    timeMillis = 220L,
                    y = 780f,
                    previousY = 800f,
                    historical = listOf(
                        180L to 1_000f,
                        195L to 900f,
                        210L to 800f,
                    ),
                ),
            )
        }.velocityY()

        // The two-point average over the whole 220 ms is -1_000 px/s; the
        // historical samples show the real burst in the last 40 ms.
        assertThat(velocity).isLessThan(-2_000f)
    }

    @Test
    fun `a new down resets the previous gesture`() {
        val velocity = PlayerSheetVelocity().apply {
            add(down(timeMillis = 0L, y = 1_000f))
            add(move(timeMillis = 16L, y = 600f, previousY = 1_000f))
            add(down(timeMillis = 100L, y = 500f))
            add(move(timeMillis = 116L, y = 500f, previousY = 500f))
        }.velocityY()

        assertThat(velocity).isWithin(0.5f).of(0f)
    }

    @Test
    fun `a release without movement has no velocity`() {
        val velocity = PlayerSheetVelocity().apply {
            add(down(timeMillis = 0L, y = 1_000f))
            add(up(timeMillis = 16L, y = 1_000f))
        }.velocityY()

        assertThat(velocity).isWithin(0.5f).of(0f)
    }

    private fun down(timeMillis: Long, y: Float) = PointerInputChange(
        id = POINTER,
        uptimeMillis = timeMillis,
        position = Offset(0f, y),
        pressed = true,
        previousUptimeMillis = timeMillis - 16L,
        previousPosition = Offset(0f, y),
        isInitiallyConsumed = false,
        previousPressed = false,
    )

    private fun move(
        timeMillis: Long,
        y: Float,
        previousY: Float,
        historical: List<Pair<Long, Float>> = emptyList(),
    ) = PointerInputChange(
        id = POINTER,
        uptimeMillis = timeMillis,
        position = Offset(0f, y),
        pressed = true,
        previousUptimeMillis = timeMillis - 16L,
        previousPosition = Offset(0f, previousY),
        isInitiallyConsumed = false,
        previousPressed = true,
    ).copy(
        historical = historical.map { (time, historicalY) ->
            HistoricalChange(time, Offset(0f, historicalY))
        },
    )

    private fun up(timeMillis: Long, y: Float) = PointerInputChange(
        id = POINTER,
        uptimeMillis = timeMillis,
        position = Offset(0f, y),
        pressed = false,
        previousUptimeMillis = timeMillis - 16L,
        previousPosition = Offset(0f, y),
        isInitiallyConsumed = false,
        previousPressed = true,
    )

    private companion object {
        val POINTER = PointerId(1)
    }
}
