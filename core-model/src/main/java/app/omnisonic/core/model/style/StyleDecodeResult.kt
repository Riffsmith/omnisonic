package app.omnisonic.core.model.style

/**
 * One path-qualified decoder message. [path] uses dotted keys and bracketed
 * array indexes (`nowPlaying.compact.buttons[2].action`); [message] is the
 * editor-facing line (`path: detail`).
 */
data class StyleIssue(
    val path: String,
    val detail: String,
) {
    val message: String get() = "$path: $detail"
}

/**
 * Outcome of decoding style text. A [Decoded] document carries the unknown-key
 * warnings in document order; a [Rejected] result carries every path-qualified
 * error, with the first one being the primary message for the editor
 * (`docs/omnisonic/style/style-schema.md` §Errors, D-64).
 */
sealed interface StyleDecodeResult {
    data class Decoded(
        val document: StyleDocument,
        val warnings: List<StyleIssue>,
    ) : StyleDecodeResult

    data class Rejected(
        val errors: List<StyleIssue>,
    ) : StyleDecodeResult {
        val firstMessage: String get() = errors.first().message
    }
}
