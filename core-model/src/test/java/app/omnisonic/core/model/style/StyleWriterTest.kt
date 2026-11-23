package app.omnisonic.core.model.style

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StyleWriterTest {

    private fun written(document: StyleDocument): String =
        when (val result = StyleWriter.write(document)) {
            is StyleWriteResult.Written -> result.text
            is StyleWriteResult.Failed ->
                throw AssertionError("expected text, got ${result.errors.map { it.message }}")
        }

    private fun failed(result: StyleWriteResult): StyleWriteResult.Failed =
        when (result) {
            is StyleWriteResult.Failed -> result
            is StyleWriteResult.Written -> throw AssertionError("expected a failure, got:\n${result.text}")
        }

    private fun keyOf(line: String): String = line.trim().removePrefix("\"").substringBefore("\":") 

    @Test
    fun `a full document round-trips byte-equal`() {
        val document = fullStyleDocument()

        val first = written(document)
        val decoded = StyleDecoder.decode(first)

        assertThat(decoded).isInstanceOf(StyleDecodeResult.Decoded::class.java)
        decoded as StyleDecodeResult.Decoded
        assertThat(decoded.warnings).isEmpty()
        assertThat(decoded.document).isEqualTo(document)
        assertThat(written(decoded.document)).isEqualTo(first)
    }

    @Test
    fun `a minimal document writes every resolved section`() {
        val document = StyleDocument(id = "style-id", name = "Style name")

        val text = written(document)
        val decoded = StyleDecoder.decode(text) as StyleDecodeResult.Decoded

        assertThat(decoded.document).isEqualTo(document)
        assertThat(decoded.warnings).isEmpty()
        assertThat(text).contains("\"schemaVersion\": 1")
        assertThat(text).contains("\"fontFamilies\": {")
        assertThat(text).contains("\"title\": null")
        assertThat(text).contains("\"background\": null")
        assertThat(text).contains("\"sections\": [")
    }

    @Test
    fun `the writer fixes key order in the envelope and app sections`() {
        val text = written(StyleDocument(id = "id", name = "name"))

        val rootKeys = text.lines().filter { it.startsWith("  \"") }.map(::keyOf)
        assertThat(rootKeys).containsExactly(
            "schemaVersion",
            "id",
            "name",
            "app",
            "home",
            "pages",
            "nowPlaying",
        ).inOrder()

        val appBlock = text.substring(text.indexOf("\"app\": {"), text.indexOf("\"home\": {"))
        val appKeys = appBlock.lines().filter { it.startsWith("    \"") }.map(::keyOf)
        assertThat(appKeys).containsExactly(
            "colorSource",
            "seedColor",
            "darkMode",
            "colorPalette",
            "colorContrast",
            "cornerScale",
            "fontFamilies",
            "effects",
        ).inOrder()
    }

    @Test
    fun `the writer indents two spaces per depth and ends with one newline`() {
        val text = written(StyleDocument(id = "id", name = "name"))

        assertThat(text.first().toString()).isEqualTo("{")
        assertThat(text.lines()[1]).startsWith("  \"")
        assertThat(text).contains("\n    \"fontFamilies\": {")
        assertThat(text).contains("\n      \"title\": null")
        assertThat(text).contains("\n    \"albums\": {")
        assertThat(text).contains("\n      \"columns\": {")
        assertThat(text).contains("\n        \"portrait\": 3")
        assertThat(text.endsWith("\n")).isTrue()
        assertThat(text.endsWith("\n\n")).isFalse()
        assertThat(text.dropLast(1).endsWith("\n")).isFalse()
    }

    @Test
    fun `numbers keep a stable shortest form for fractional and integral values`() {
        val document = StyleDocument(id = "id", name = "name").copy(
            app = StyleApp(effects = StyleEffects(overlayAlpha = 0.0001f, tiltParallaxMaxOffsetDp = 20f)),
            pages = StylePages(
                albums = StyleListLayout(
                    columns = OrientationPair(portrait = 2, landscape = 4),
                    spacingDp = OrientationPair(portrait = 7.5f, landscape = 12f),
                ),
            ),
            nowPlaying = StyleNowPlaying(
                compact = StyleCompactPlayer(progress = StyleProgress(heightDp = 1.5f)),
            ),
        )

        val text = written(document)

        assertThat(text).contains("\"overlayAlpha\": 0.0001")
        assertThat(text).doesNotContain("0.00010")
        assertThat(text).contains("\"tiltParallaxMaxOffsetDp\": 20")
        assertThat(text).contains("\"portrait\": 7.5")
        assertThat(text).contains("\"landscape\": 12")
        assertThat(text).contains("\"heightDp\": 1.5")
        assertThat(text).doesNotContain("E+")
        assertThat(text).doesNotContain("E-")
        assertThat(Regex("\\.0(?=[,\\n])").containsMatchIn(text)).isFalse()
        val decoded = StyleDecoder.decode(text) as StyleDecodeResult.Decoded
        assertThat(written(decoded.document)).isEqualTo(text)
    }

    @Test
    fun `a scientific-notation float is written as a plain decimal`() {
        val document = StyleDocument(id = "id", name = "name").copy(
            app = StyleApp(effects = StyleEffects(overlayAlpha = 1.0E-6f)),
        )

        val text = written(document)

        assertThat(text).contains("\"overlayAlpha\": 0.000001")
        assertThat(text).doesNotContain("E-")
        assertThat(text).doesNotContain("E+")
        val decoded = StyleDecoder.decode(text) as StyleDecodeResult.Decoded
        assertThat(decoded.document.app.effects.overlayAlpha).isEqualTo(1.0E-6f)
    }

    @Test
    fun `empty lists keep a resolved empty shape`() {
        val emptyPlayer = { StyleExpandedPlayer(blocks = emptyList(), buttons = emptyList()) }
        val document = StyleDocument(id = "id", name = "name").copy(
            home = StyleHome(sections = emptyList()),
            nowPlaying = StyleNowPlaying(
                compact = StyleCompactPlayer(blocks = emptyList(), buttons = emptyList()),
                expanded = OrientationPair(portrait = emptyPlayer(), landscape = emptyPlayer()),
                background = null,
            ),
        )

        val text = written(document)

        assertThat(text).contains("\"sections\": []")
        assertThat(text).contains("\"blocks\": []")
        assertThat(text).contains("\"buttons\": []")
        assertThat(text).contains("\"background\": null")
        assertThat((StyleDecoder.decode(text) as StyleDecodeResult.Decoded).document).isEqualTo(document)
    }

    @Test
    fun `text templates keep quotes and newlines after escaping`() {
        val template = "Say \"hi\"\nbye"
        val document = StyleDocument(id = "id", name = "name").copy(
            nowPlaying = StyleNowPlaying(
                compact = StyleCompactPlayer(
                    text = StyleTextBlocks(
                        title = StyleTextBlock(template = template),
                        subtitle = StyleTextBlock(template = template),
                    ),
                ),
            ),
        )

        val text = written(document)

        assertThat(text).contains("Say \\\"hi\\\"\\nbye")
        val decoded = StyleDecoder.decode(text) as StyleDecodeResult.Decoded
        assertThat(decoded.document.nowPlaying.compact.text.title.template).isEqualTo(template)
    }

    @Test
    fun `a document this build cannot represent fails as a whole`() {
        val outOfRange = StyleDocument(id = "id", name = "name").copy(
            pages = StylePages(albums = StyleListLayout(columns = OrientationPair(portrait = 0, landscape = 4))),
        )
        assertThat(failed(StyleWriter.write(outOfRange)).firstMessage)
            .isEqualTo("pages.albums.columns.portrait: value 0 is out of range 1..12")

        val blankName = StyleDocument(id = "id", name = "  ")
        assertThat(failed(StyleWriter.write(blankName)).firstMessage).isEqualTo("name: must not be blank")

        val nonFinite = StyleDocument(id = "id", name = "name").copy(
            app = StyleApp(effects = StyleEffects(overlayAlpha = Float.NaN)),
        )
        val failure = failed(StyleWriter.write(nonFinite))
        assertThat(failure.errors.single().path).isEqualTo("\$")
        assertThat(failure.firstMessage).contains("non-finite")
    }
}
