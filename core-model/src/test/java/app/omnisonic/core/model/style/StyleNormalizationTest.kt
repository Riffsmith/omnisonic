package app.omnisonic.core.model.style

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StyleNormalizationTest {

    private val looseStyle = """
        {
          "name": "Loose style",
          "schemaVersion": 1,
          "id": "style-loose",
          "nowPlaying": {
            "compact": {
              "progress": {"heightDp": 1.5, "showRemainingTime": true, "appearance": "HAIRLINE"},
              "buttons": [{"action": "NEXT"}],
              "blocks": [{"kind": "ARTWORK"}, {"kind": "TITLE", "visible": false}]
            }
          },
          "pages": {
            "songs": {
              "spacingDp": {"landscape": 10, "portrait": 4},
              "columns": {"landscape": 6, "portrait": 2}
            }
          },
          "home": {"sections": [{"visible": false, "section": "FAVORITES"}]},
          "app": {"effects": {"overlayAlpha": 0.25}, "darkMode": "DARK", "seedColor": "#010203"}
        }
    """.trimIndent()

    private val reorderedStyle = """
        {
          "schemaVersion": 1,
          "id": "style-loose",
          "name": "Loose style",
          "app": {
            "seedColor": "#010203",
            "darkMode": "DARK",
            "mystery": 1,
            "effects": {"overlayAlpha": 0.25, "mystery": true}
          },
          "home": {"sections": [{"section": "FAVORITES", "visible": false}]},
          "pages": {
            "songs": {
              "columns": {"portrait": 2, "landscape": 6},
              "spacingDp": {"portrait": 4, "landscape": 10}
            }
          },
          "nowPlaying": {
            "compact": {
              "blocks": [{"kind": "ARTWORK"}, {"visible": false, "kind": "TITLE"}],
              "buttons": [{"action": "NEXT"}],
              "progress": {"appearance": "HAIRLINE", "showRemainingTime": true, "heightDp": 1.5}
            }
          },
          "trailingMystery": {}
        }
    """.trimIndent()

    private fun normalized(text: String): String =
        when (val result = StyleNormalizer.normalize(text)) {
            is StyleWriteResult.Written -> result.text
            is StyleWriteResult.Failed ->
                throw AssertionError("expected normalized text, got ${result.errors.map { it.message }}")
        }

    private fun rejected(text: String): StyleWriteResult.Failed =
        when (val result = StyleNormalizer.normalize(text)) {
            is StyleWriteResult.Failed -> result
            is StyleWriteResult.Written -> throw AssertionError("expected a rejection, got:\n${result.text}")
        }

    @Test
    fun `key order is stable under input key reordering`() {
        assertThat(normalized(looseStyle)).isEqualTo(normalized(reorderedStyle))
    }

    @Test
    fun `unknown keys never reach the normalized text`() {
        val text = normalized(reorderedStyle)

        assertThat(text).doesNotContain("mystery")
        assertThat(text).doesNotContain("trailingMystery")
        val decoded = StyleDecoder.decode(text) as StyleDecodeResult.Decoded
        assertThat(decoded.warnings).isEmpty()
    }

    @Test
    fun `normalization is idempotent byte-for-byte`() {
        val once = normalized(looseStyle)

        assertThat(normalized(once)).isEqualTo(once)
    }

    @Test
    fun `export import export is byte-equal`() {
        val document = fullStyleDocument()
        val exported = StyleWriter.write(document) as StyleWriteResult.Written

        val imported = StyleNormalizer.normalize(exported.text) as StyleWriteResult.Written
        val reExported = StyleWriter.write(
            (StyleDecoder.decode(imported.text) as StyleDecodeResult.Decoded).document,
        ) as StyleWriteResult.Written

        assertThat(imported.text).isEqualTo(exported.text)
        assertThat(reExported.text).isEqualTo(exported.text)
    }

    @Test
    fun `a rejected document produces no normalized text`() {
        assertThat(rejected("""{"schemaVersion":1,"id":}""").firstMessage)
            .startsWith("\$: malformed JSON")

        val future = rejected(
            """{"schemaVersion":2,"id":"id","name":"name","app":{},"home":{},"pages":{},"nowPlaying":{}}""",
        )
        assertThat(future.firstMessage)
            .isEqualTo("schemaVersion: unsupported schema version 2; this build reads version 1")
    }
}
