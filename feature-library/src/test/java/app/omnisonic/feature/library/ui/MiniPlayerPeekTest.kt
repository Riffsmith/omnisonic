package app.omnisonic.feature.library.ui

import app.omnisonic.feature.library.player.PlayerSheetStops
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The mini player as the sheet's peek detent (ui/player-sheet.md §2): its exit
 * is a pure function of sheet position, so no second animation system can fall
 * out of sync with the sheet's spring.
 */
class MiniPlayerPeekTest {

    @Test
    fun `the bar rests in place while the sheet peeks`() {
        assertThat(barExitProgress(PlayerSheetStops.PEEK)).isEqualTo(0f)
    }

    @Test
    fun `the bar has left once the sheet reaches now-playing`() {
        assertThat(barExitProgress(PlayerSheetStops.NOW_PLAYING)).isEqualTo(1f)
    }

    @Test
    fun `the exit tracks the sheet between the two detents`() {
        assertThat(barExitProgress(0.5f)).isWithin(0.001f).of(0.5f)
        assertThat(barExitProgress(0.25f)).isWithin(0.001f).of(0.75f)
    }

    @Test
    fun `the exit never leaves the 0 to 1 range`() {
        assertThat(barExitProgress(PlayerSheetStops.SWIPE_UP_TARGET)).isEqualTo(1f)
        assertThat(barExitProgress(1.4f)).isEqualTo(0f)
    }
}
