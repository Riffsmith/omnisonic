package app.omnisonic.feature.settings.shell

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MainTabTest {

    @Test
    fun `null and blank raw values parse to the default tab set`() {
        assertThat(MainTabSet.parse(null)).isEqualTo(MainTab.DEFAULT)
        assertThat(MainTabSet.parse("")).isEqualTo(MainTab.DEFAULT)
        assertThat(MainTabSet.parse("   ")).isEqualTo(MainTab.DEFAULT)
    }

    @Test
    fun `unknown ids and blanks are dropped`() {
        val parsed = MainTabSet.parse("home,widget,search,")
        assertThat(parsed).containsExactly(MainTab.HOME, MainTab.SEARCH).inOrder()
    }

    @Test
    fun `duplicates collapse to the first occurrence`() {
        val parsed = MainTabSet.parse("search,home,search")
        assertThat(parsed).containsExactly(MainTab.SEARCH, MainTab.HOME).inOrder()
    }

    @Test
    fun `order is preserved from the raw value`() {
        val parsed = MainTabSet.parse("settings,library,home,search")
        assertThat(parsed).containsExactly(
            MainTab.SETTINGS,
            MainTab.LIBRARY,
            MainTab.HOME,
            MainTab.SEARCH,
        ).inOrder()
    }

    @Test
    fun `only-unknown values fall back to the default set`() {
        assertThat(MainTabSet.parse("widget,gadget")).isEqualTo(MainTab.DEFAULT)
    }

    @Test
    fun `serialize-parse round trip preserves order`() {
        val tabs = listOf(MainTab.SEARCH, MainTab.HOME)
        assertThat(MainTabSet.parse(MainTabSet.serialize(tabs))).isEqualTo(tabs)
    }
}
