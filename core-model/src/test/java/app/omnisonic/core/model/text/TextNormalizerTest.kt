package app.omnisonic.core.model.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TextNormalizerTest {

    @Test
    fun `sort key lowercases and trims`() {
        assertThat(TextNormalizer.sortKey("  The Wall  ")).isEqualTo("the wall")
    }

    @Test
    fun `sort key strips combining marks`() {
        assertThat(TextNormalizer.sortKey("Björk")).isEqualTo("bjork")
        assertThat(TextNormalizer.sortKey("Sigur Rós")).isEqualTo("sigur ros")
    }

    @Test
    fun `romanize keeps non-latin scripts intact when no transliterator is available`() {
        // On the JVM there is no ICU Transliterator; the fallback must be stable.
        assertThat(TextNormalizer.romanize("東京事変")).isEqualTo("東京事変")
    }

    @Test
    fun `empty and blank titles produce empty sort keys`() {
        assertThat(TextNormalizer.sortKey("")).isEmpty()
        assertThat(TextNormalizer.sortKey("   ")).isEmpty()
    }
}
