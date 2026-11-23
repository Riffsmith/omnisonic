package app.omnisonic.core.model.style

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import java.io.IOException

/**
 * Ordered forward migration of style text to [targetVersion]
 * (`docs/omnisonic/style/style-schema.md` §Versioning, D-64/D-70).
 *
 * The version is read and the steps run on the JSON tree before
 * [StyleDecoder], which is what lets the D-68 gate stay unchanged: the decoder
 * still reads exactly version 1, and a migrated document reaches it only after
 * the chain has brought the version up to the target. [targetVersion] is the
 * version this build reads; a test raises it to exercise a step before a real
 * v2 exists.
 */
class StyleMigrator(
    private val migrations: List<StyleMigration> = emptyList(),
    private val targetVersion: Int = StyleDocument.CURRENT_SCHEMA_VERSION,
) {

    private val treeAdapter: JsonAdapter<Any> = Moshi.Builder().build().adapter(Any::class.java)

    fun migrate(text: String): StyleMigrationResult {
        val issues = StyleIssues()
        val root = readRoot(text, issues) ?: return StyleMigrationResult.Failed(issues.errors)
        val version = readVersion(root, issues) ?: return StyleMigrationResult.Failed(issues.errors)

        if (version > targetVersion) {
            issues.error(
                "schemaVersion",
                "unsupported schema version $version; this build reads version $targetVersion",
            )
            return StyleMigrationResult.Failed(issues.errors)
        }
        if (version == targetVersion) return StyleMigrationResult.Migrated(text)

        var current = version
        var tree = root
        while (current < targetVersion) {
            val candidates = migrations.filter { it.from == current }
            if (candidates.isEmpty()) {
                issues.error(
                    STYLE_ROOT_PATH,
                    "no migration step from version $current to version ${current + 1}",
                )
                return StyleMigrationResult.Failed(issues.errors)
            }
            if (candidates.size > 1) {
                issues.error(STYLE_ROOT_PATH, "more than one migration step from version $current")
                return StyleMigrationResult.Failed(issues.errors)
            }
            val step = candidates.single()
            if (step.to != current + 1) {
                issues.error(
                    STYLE_ROOT_PATH,
                    "migration $current -> ${step.to} targets version ${step.to}; " +
                        "the next step must target version ${current + 1}",
                )
                return StyleMigrationResult.Failed(issues.errors)
            }
            tree = when (val outcome = step.migrate(tree)) {
                is StyleMigrationOutcome.Failed -> {
                    issues.error(
                        STYLE_ROOT_PATH,
                        "migration $current -> ${step.to} failed: ${outcome.detail}",
                    )
                    return StyleMigrationResult.Failed(issues.errors)
                }

                is StyleMigrationOutcome.Migrated -> outcome.tree + ("schemaVersion" to step.to)
            }
            current = step.to
        }

        val migrated = treeAdapter.toJson(tree)
        if (targetVersion != StyleDocument.CURRENT_SCHEMA_VERSION) {
            return StyleMigrationResult.Migrated(migrated)
        }
        return when (val decoded = StyleDecoder.decode(migrated)) {
            is StyleDecodeResult.Rejected -> StyleMigrationResult.Failed(decoded.errors)
            is StyleDecodeResult.Decoded -> StyleMigrationResult.Migrated(migrated)
        }
    }

    private fun readVersion(root: Map<String, Any?>, issues: StyleIssues): Int? {
        if (!root.containsKey("schemaVersion")) {
            issues.error("schemaVersion", "required field is missing")
            return null
        }
        return issues.jsonInt("schemaVersion", root["schemaVersion"])
    }

    private fun readRoot(text: String, issues: StyleIssues): Map<String, Any?>? {
        val tree = try {
            treeAdapter.fromJson(text)
        } catch (failure: IOException) {
            issues.error(STYLE_ROOT_PATH, "malformed JSON: ${failure.message ?: "unreadable document"}")
            return null
        } catch (failure: JsonDataException) {
            issues.error(STYLE_ROOT_PATH, "malformed JSON: ${failure.message ?: "unreadable document"}")
            return null
        }
        if (tree == null) {
            issues.error(STYLE_ROOT_PATH, "expected object, got null")
            return null
        }
        return issues.jsonObject(STYLE_ROOT_PATH, tree)
    }
}
