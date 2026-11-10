package app.omnisonic.feature.library.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoverSizingTest {

    @Test
    fun `unmeasured cells request no explicit size`() {
        assertThat(CoverSizing.sizePx(0, 0)).isNull()
        assertThat(CoverSizing.sizePx(-4, 0)).isNull()
    }

    @Test
    fun `size follows the longest measured dimension`() {
        assertThat(CoverSizing.sizePx(120, 0)).isEqualTo(128)
        assertThat(CoverSizing.sizePx(0, 300)).isEqualTo(320)
    }

    @Test
    fun `sizes bucket up so neighbouring cells share a cache entry`() {
        assertThat(CoverSizing.sizePx(300, 300)).isEqualTo(320)
        assertThat(CoverSizing.sizePx(301, 300)).isEqualTo(320)
        assertThat(CoverSizing.sizePx(321, 300)).isEqualTo(384)
    }

    @Test
    fun `request size is capped at the detail-art ceiling`() {
        assertThat(CoverSizing.sizePx(2_000, 2_000)).isEqualTo(CoverSizing.MAX_SIZE_PX)
    }
}
