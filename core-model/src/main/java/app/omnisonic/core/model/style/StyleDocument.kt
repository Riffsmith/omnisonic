package app.omnisonic.core.model.style

/**
 * Orientation-dependent values are structural pairs, never sibling keys
 * (`docs/omnisonic/style/style-format.md` §3.2, D-64).
 */
data class OrientationPair<T>(
    val portrait: T,
    val landscape: T,
)

/**
 * Typed envelope for an Omnisonic-native style document. Applying a document,
 * decoding text and normalizing it belong to TODO 1b-1d; this model only owns
 * the field inventory and its defaults (`docs/omnisonic/style/style-schema.md`).
 *
 * [id] and [name] have no defaults: identity is supplied by the creating or
 * importing flow, and a decoder must reject a document that lacks either.
 */
data class StyleDocument(
    val id: String,
    val name: String,
    val schemaVersion: Int = StyleDocument.CURRENT_SCHEMA_VERSION,
    val app: StyleApp = StyleApp(),
    val home: StyleHome = StyleHome(),
    val pages: StylePages = StylePages(),
    val nowPlaying: StyleNowPlaying = StyleNowPlaying(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
