package app.omnisonic.core.model.style

/**
 * Outcome of writing style text. [Written.text] is the complete normalized
 * document; [Failed] carries the path-qualified reason no text was produced, so
 * a document this build cannot represent never yields a partial file
 * (`docs/omnisonic/style/style-schema.md` §Normalized form).
 */
sealed interface StyleWriteResult {
    data class Written(val text: String) : StyleWriteResult

    data class Failed(val errors: List<StyleIssue>) : StyleWriteResult {
        val firstMessage: String get() = errors.first().message
    }
}
