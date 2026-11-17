package app.omnisonic.feature.search.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchFocusRulesTest {

    @Test
    fun `inactive search never takes focus`() {
        assertThat(
            SearchFocusRules.shouldRequestFocus(
                isActive = false,
                phase = SearchPhase.IDLE,
                autofocusOnEntry = true,
            ),
        ).isFalse()
    }

    @Test
    fun `active idle search takes focus when autofocus is configured`() {
        assertThat(
            SearchFocusRules.shouldRequestFocus(
                isActive = true,
                phase = SearchPhase.IDLE,
                autofocusOnEntry = true,
            ),
        ).isTrue()
    }

    @Test
    fun `active search does not take focus once a query is running or has results`() {
        listOf(SearchPhase.SEARCHING, SearchPhase.RESULTS, SearchPhase.NO_RESULTS, SearchPhase.ERROR)
            .forEach { phase ->
                assertThat(
                    SearchFocusRules.shouldRequestFocus(
                        isActive = true,
                        phase = phase,
                        autofocusOnEntry = true,
                    ),
                ).isFalse()
            }
    }

    @Test
    fun `the style config can disable autofocus`() {
        assertThat(
            SearchFocusRules.shouldRequestFocus(
                isActive = true,
                phase = SearchPhase.IDLE,
                autofocusOnEntry = false,
            ),
        ).isFalse()
    }
}
