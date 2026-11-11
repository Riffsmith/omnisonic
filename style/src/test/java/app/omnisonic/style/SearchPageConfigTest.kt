package app.omnisonic.style

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SearchPageConfigTest {

    @Test
    fun `default json decodes to the shipped defaults`() {
        val config = SearchPageConfig.fromJson(SearchPageConfig.DEFAULT_JSON)

        assertThat(config).isEqualTo(SearchPageConfig.DEFAULT)
        assertThat(config.chipLayout).isEqualTo(FilterChipLayout.SINGLE_LINE)
        assertThat(config.autofocusOnEntry).isTrue()
        assertThat(config.showRecentSearches).isTrue()
    }

    @Test
    fun `overrides are read from json`() {
        val config = SearchPageConfig.fromJson(
            """{"chipLayout":"WRAPPED","autofocusOnEntry":false,"showRecentSearches":false}""",
        )

        assertThat(config.chipLayout).isEqualTo(FilterChipLayout.WRAPPED)
        assertThat(config.autofocusOnEntry).isFalse()
        assertThat(config.showRecentSearches).isFalse()
    }

    @Test
    fun `missing keys keep their defaults`() {
        val config = SearchPageConfig.fromJson("""{"chipLayout":"WRAPPED"}""")

        assertThat(config).isEqualTo(
            SearchPageConfig.DEFAULT.copy(chipLayout = FilterChipLayout.WRAPPED),
        )
    }

    @Test
    fun `malformed json falls back to defaults`() {
        assertThat(SearchPageConfig.fromJson("{not json")).isEqualTo(SearchPageConfig.DEFAULT)
        assertThat(SearchPageConfig.fromJson("")).isEqualTo(SearchPageConfig.DEFAULT)
        assertThat(SearchPageConfig.fromJson("null")).isEqualTo(SearchPageConfig.DEFAULT)
    }

    @Test
    fun `unknown enum values fall back to defaults`() {
        assertThat(SearchPageConfig.fromJson("""{"chipLayout":"SIDEWAYS"}"""))
            .isEqualTo(SearchPageConfig.DEFAULT)
    }

    @Test
    fun `round trip preserves a custom config`() {
        val config = SearchPageConfig(
            chipLayout = FilterChipLayout.WRAPPED,
            autofocusOnEntry = false,
            showRecentSearches = false,
        )

        assertThat(SearchPageConfig.fromJson(config.toJson())).isEqualTo(config)
    }
}
