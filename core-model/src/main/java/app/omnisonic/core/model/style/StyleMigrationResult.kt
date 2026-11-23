package app.omnisonic.core.model.style

/**
 * Outcome of migrating style text. [Migrated.text] is the text at the target
 * version; [Failed] carries the path-qualified reason no text was produced, with
 * the first message being the editor-facing line and every message retained
 * (`docs/omnisonic/style/style-schema.md` §Versioning).
 */
sealed interface StyleMigrationResult {
    data class Migrated(val text: String) : StyleMigrationResult

    data class Failed(val errors: List<StyleIssue>) : StyleMigrationResult {
        val firstMessage: String get() = errors.first().message
    }
}
