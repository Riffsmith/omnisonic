package app.omnisonic.style

import app.omnisonic.core.model.style.StyleDecodeResult
import app.omnisonic.core.model.style.StyleDecoder
import app.omnisonic.core.model.style.StyleDocument
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Pins the profile-backed observable source (`docs/omnisonic/style/style-profiles.md`
 * §Wiring, D-79): resolution comes only from the active document, an unusable
 * profile is skipped, and one switch yields exactly one new emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileStyleTokenSourceTest {

    private val assetDir = File("src/main/assets/styles")

    private fun document(id: String): StyleDocument {
        val result = StyleDecoder.decode(File(assetDir, "$id.json").readText())
        assertThat(result).isInstanceOf(StyleDecodeResult.Decoded::class.java)
        return (result as StyleDecodeResult.Decoded).document
    }

    @Test
    fun synchronousReadServesTheLegacyDefault() {
        val source = ProfileStyleTokenSource(MutableStateFlow<StyleDocument?>(null))

        assertThat(source.tokens()).isSameInstanceAs(DefaultStyleTokens)
    }

    @Test
    fun tokenFlowEmitsTheResolvedTokensForTheActiveDocument() = runTest {
        val compact = document("builtin-compact-list")
        val source = ProfileStyleTokenSource(MutableStateFlow<StyleDocument?>(compact))

        val tokens = source.tokenFlow.first()

        assertThat(tokens).isEqualTo(StyleTokenResolver.resolve(compact))
        assertThat(tokens.layout.gridColumnsPortrait).isEqualTo(4)
        assertThat(tokens.layout.gridColumnsLandscape).isEqualTo(6)
    }

    @Test
    fun aProfileSwitchProducesExactlyOneNewEmission() = runTest {
        val documents = MutableStateFlow<StyleDocument?>(document("builtin-compact-list"))
        val source = ProfileStyleTokenSource(documents)
        val emissions = mutableListOf<StyleTokens>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.tokenFlow.collect { emissions += it }
        }
        runCurrent()
        assertThat(emissions).hasSize(1)

        documents.value = document("builtin-large-cover")
        runCurrent()

        assertThat(emissions).hasSize(2)
        assertThat(emissions[0].layout.gridColumnsPortrait).isEqualTo(4)
        assertThat(emissions[1].layout.gridColumnsPortrait).isEqualTo(2)
        assertThat(emissions[1].layout.gridSpacing.value).isWithin(0.001f).of(12f)
    }

    @Test
    fun anUnusableProfileNeverReplacesTheLastGoodTokens() = runTest {
        val compact = document("builtin-compact-list")
        val large = document("builtin-large-cover")
        val documents = MutableStateFlow<StyleDocument?>(compact)
        val source = ProfileStyleTokenSource(documents)
        val emissions = mutableListOf<StyleTokens>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.tokenFlow.collect { emissions += it }
        }
        runCurrent()

        documents.value = null
        runCurrent()
        assertThat(emissions).hasSize(1)
        assertThat(emissions.single()).isEqualTo(StyleTokenResolver.resolve(compact))

        documents.value = compact
        runCurrent()
        assertThat(emissions).hasSize(1)

        documents.value = large
        runCurrent()
        assertThat(emissions).hasSize(2)
        assertThat(emissions.last().layout.gridColumnsPortrait).isEqualTo(2)
    }

    @Test
    fun aUsableDocumentAfterAnUnusableStartBeginsEmission() = runTest {
        val documents = MutableStateFlow<StyleDocument?>(null)
        val source = ProfileStyleTokenSource(documents)
        val emissions = mutableListOf<StyleTokens>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.tokenFlow.collect { emissions += it }
        }
        runCurrent()
        assertThat(emissions).isEmpty()

        documents.value = document("builtin-tablet-wide")
        runCurrent()

        assertThat(emissions).hasSize(1)
        assertThat(emissions.single().layout.gridColumnsPortrait).isEqualTo(5)
        assertThat(emissions.single().shapes.cornerScale).isEqualTo(CornerScale.LARGE)
    }
}
