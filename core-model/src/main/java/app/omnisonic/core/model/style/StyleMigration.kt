package app.omnisonic.core.model.style

/**
 * One forward step of the style document tree, mirroring the database's
 * `Migration(from, to)` steps (`docs/omnisonic/style/style-schema.md` §Versioning,
 * D-70). A step reads a tree at [from] and returns a tree at [to]; the migrator
 * stamps the envelope version and validates the result, so a step never owns
 * the version marker.
 */
class StyleMigration(
    val from: Int,
    val to: Int,
    private val step: (Map<String, Any?>) -> StyleMigrationOutcome,
) {
    fun migrate(tree: Map<String, Any?>): StyleMigrationOutcome = step(tree)
}

/**
 * A step either advances the tree or reports why it could not. A failure carries
 * no tree, so a half-migrated document cannot leave the chain
 * (`docs/omnisonic/style/style-schema.md` §Versioning).
 */
sealed interface StyleMigrationOutcome {
    data class Migrated(val tree: Map<String, Any?>) : StyleMigrationOutcome

    data class Failed(val detail: String) : StyleMigrationOutcome
}
