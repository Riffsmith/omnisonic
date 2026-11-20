package app.omnisonic.feature.downloads.service

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DownloadNotificationRulesTest {

    @Test
    fun `multi worker progress is averaged and clamped`() {
        val state = DownloadNotificationRules.state(
            progressPercents = listOf(40, 60),
            pendingCount = 3,
            paused = false,
            blocked = null,
        )

        assertThat(state.activeCount).isEqualTo(2)
        assertThat(state.progressPercent).isEqualTo(50)
        assertThat(state.indeterminate).isFalse()
    }

    @Test
    fun `an unknown length worker makes the aggregate indeterminate`() {
        val state = DownloadNotificationRules.state(
            progressPercents = listOf(80, -1),
            pendingCount = 0,
            paused = false,
            blocked = null,
        )

        assertThat(state.indeterminate).isTrue()
        assertThat(state.progressPercent).isEqualTo(80)
    }

    @Test
    fun `out of range progress is clamped and pending only work is indeterminate`() {
        val clamped = DownloadNotificationRules.state(
            progressPercents = listOf(240, 20),
            pendingCount = 0,
            paused = false,
            blocked = null,
        )
        assertThat(clamped.progressPercent).isEqualTo(60)
        assertThat(clamped.indeterminate).isFalse()

        val waiting = DownloadNotificationRules.state(
            progressPercents = emptyList(),
            pendingCount = 2,
            paused = false,
            blocked = null,
        )
        assertThat(waiting.indeterminate).isTrue()
        assertThat(waiting.pendingCount).isEqualTo(2)
    }

    @Test
    fun `blocked and paused states are carried through`() {
        val blocked = DownloadNotificationRules.state(
            progressPercents = listOf(25),
            pendingCount = 1,
            paused = false,
            blocked = DownloadRunBlock.WIFI_REQUIRED,
        )
        assertThat(blocked.blocked).isEqualTo(DownloadRunBlock.WIFI_REQUIRED)
        assertThat(blocked.indeterminate).isTrue()

        val paused = DownloadNotificationRules.state(
            progressPercents = listOf(25),
            pendingCount = 1,
            paused = true,
            blocked = null,
        )
        assertThat(paused.paused).isTrue()
        assertThat(paused.progressPercent).isEqualTo(25)
    }
}
