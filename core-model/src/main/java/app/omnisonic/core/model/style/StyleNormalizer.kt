package app.omnisonic.core.model.style

/**
 * Normalization entry point for import, export and the loader's write path:
 * text → decode → canonical document → canonical text
 * (`docs/omnisonic/style/style-schema.md` §Normalized form).
 *
 * A rejection returns the decoder's path-qualified errors and no text, so a
 * malformed import can never replace a working style with a partial document.
 */
object StyleNormalizer {

    fun normalize(text: String): StyleWriteResult =
        when (val decoded = StyleDecoder.decode(text)) {
            is StyleDecodeResult.Rejected -> StyleWriteResult.Failed(decoded.errors)
            is StyleDecodeResult.Decoded -> StyleWriter.write(decoded.document)
        }
}
