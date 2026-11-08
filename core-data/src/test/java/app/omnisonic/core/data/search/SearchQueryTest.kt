package app.omnisonic.core.data.search

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SearchQueryTest {

    @Test
    fun `blank queries produce no terms`() {
        assertThat(SearchQuery.terms("")).isEmpty()
        assertThat(SearchQuery.terms("   \t\n  ")).isEmpty()
    }

    @Test
    fun `terms split on any whitespace run`() {
        val terms = SearchQuery.terms("  alpha\tbeta  gamma\ndelta ")

        assertThat(terms.map { it.text })
            .containsExactly("alpha", "beta", "gamma", "delta")
            .inOrder()
    }

    @Test
    fun `queries longer than the slot count are truncated`() {
        val terms = SearchQuery.terms("a b c d e f g h")

        assertThat(terms).hasSize(SearchQuery.MAX_TERMS)
        assertThat(terms.map { it.text })
            .containsExactly("a", "b", "c", "d", "e", "f")
            .inOrder()
    }

    @Test
    fun `patterns escape the sql wildcards`() {
        val term = SearchQuery.terms("50%_off\\").single()

        assertThat(term.likePattern).isEqualTo("%50\\%\\_off\\\\%")
    }

    @Test
    fun `normalized pattern is the sort key of the token`() {
        val term = SearchQuery.terms("Beyoncé").single()

        assertThat(term.likePattern).isEqualTo("%Beyoncé%")
        assertThat(term.normalizedLikePattern).isEqualTo("%beyonce%")
    }

    @Test
    fun `both patterns wrap the token in percent wildcards`() {
        val term = SearchQuery.terms("a_b").single()

        assertThat(term.likePattern).isEqualTo("%a\\_b%")
        assertThat(term.normalizedLikePattern).isEqualTo("%a\\_b%")
    }

    @Test
    fun `padding keeps term order and fills missing slots with null`() {
        val slots = listOf(SearchTerm("a", "%a%", "%a%")).padToSlots()

        assertThat(slots).hasSize(SearchQuery.MAX_TERMS)
        assertThat(slots.first()).isEqualTo(SearchTerm("a", "%a%", "%a%"))
        assertThat(slots.drop(1)).containsExactly(null, null, null, null, null).inOrder()
    }
}
