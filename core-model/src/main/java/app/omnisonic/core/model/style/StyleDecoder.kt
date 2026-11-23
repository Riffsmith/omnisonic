package app.omnisonic.core.model.style

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import java.io.IOException

/**
 * Decodes style text through a Moshi JSON tree into the typed v1 document
 * (`docs/omnisonic/style/style-schema.md` §Decoding). Only the tree adapter is
 * used: no reflection adapter and no codegen DTO for the document. `:core-model`
 * cannot see `:core-data`'s shared Moshi, so the tree instance is local to this
 * module.
 */
object StyleDecoder {

    private val treeAdapter: JsonAdapter<Any> = Moshi.Builder().build().adapter(Any::class.java)

    private val documentKeys = setOf("schemaVersion", "id", "name", "app", "home", "pages", "nowPlaying")

    fun decode(text: String): StyleDecodeResult {
        val issues = StyleIssues()
        val root = readObjectRoot(text, issues) ?: return StyleDecodeResult.Rejected(issues.errors)
        if (!checkSchemaVersion(root, issues)) return StyleDecodeResult.Rejected(issues.errors)

        val document = decodeDocument(root, issues)
        return if (issues.hasErrors) {
            StyleDecodeResult.Rejected(issues.errors)
        } else {
            StyleDecodeResult.Decoded(document, issues.warnings)
        }
    }

    private fun readObjectRoot(text: String, issues: StyleIssues): Map<String, Any?>? {
        val tree = try {
            treeAdapter.fromJson(text)
        } catch (e: IOException) {
            issues.error(STYLE_ROOT_PATH, "malformed JSON: ${e.message ?: "unreadable document"}")
            return null
        } catch (e: JsonDataException) {
            issues.error(STYLE_ROOT_PATH, "malformed JSON: ${e.message ?: "unreadable document"}")
            return null
        }
        if (tree == null) {
            issues.error(STYLE_ROOT_PATH, "expected object, got null")
            return null
        }
        return issues.jsonObject(STYLE_ROOT_PATH, tree)
    }

    /**
     * Only an integer version that differs from [StyleDocument.CURRENT_SCHEMA_VERSION]
     * stops decoding early: a future document's fields may not exist in this
     * build, and the migrator chain is TODO 1d. A missing or wrongly typed
     * version is reported and decoding continues so all messages are retained.
     */
    private fun checkSchemaVersion(root: Map<String, Any?>, issues: StyleIssues): Boolean {
        if (!root.containsKey("schemaVersion")) {
            issues.error("schemaVersion", "required field is missing")
            return true
        }
        val version = issues.jsonInt("schemaVersion", root["schemaVersion"]) ?: return true
        val current = StyleDocument.CURRENT_SCHEMA_VERSION
        if (version != current) {
            issues.error(
                "schemaVersion",
                "unsupported schema version $version; this build reads version $current",
            )
            return false
        }
        return true
    }

    private fun decodeDocument(root: Map<String, Any?>, issues: StyleIssues): StyleDocument {
        issues.unknownKeys(STYLE_ROOT_PATH, root, documentKeys)

        val id = requiredText(root, "id", issues)
        val name = requiredText(root, "name", issues)
        val app = requiredSection(root, "app", issues)?.let { decodeStyleApp("app", it, issues) }
        val home = requiredSection(root, "home", issues)?.let { decodeStyleHome("home", it, issues) }
        val pages = requiredSection(root, "pages", issues)?.let { decodeStylePages("pages", it, issues) }
        val nowPlaying = requiredSection(root, "nowPlaying", issues)
            ?.let { decodeStyleNowPlaying("nowPlaying", it, issues) }

        return StyleDocument(
            id = id,
            name = name,
            app = app ?: StyleApp(),
            home = home ?: StyleHome(),
            pages = pages ?: StylePages(),
            nowPlaying = nowPlaying ?: StyleNowPlaying(),
        )
    }

    private fun requiredText(root: Map<String, Any?>, key: String, issues: StyleIssues): String {
        if (!root.containsKey(key)) {
            issues.error(key, "required field is missing")
            return ""
        }
        val text = issues.jsonString(key, root[key]) ?: return ""
        if (text.isBlank()) {
            issues.error(key, "must not be blank")
            return ""
        }
        return text
    }

    private fun requiredSection(
        root: Map<String, Any?>,
        key: String,
        issues: StyleIssues,
    ): Map<String, Any?>? {
        if (!root.containsKey(key)) {
            issues.error(key, "required field is missing")
            return null
        }
        return issues.jsonObject(key, root[key])
    }
}
