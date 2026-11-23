package app.omnisonic.core.data.style

import app.omnisonic.core.model.style.StyleDecodeResult
import app.omnisonic.core.model.style.StyleDecoder
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleIssue
import app.omnisonic.core.model.style.StyleWriteResult
import app.omnisonic.core.model.style.StyleWriter
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import java.io.IOException

sealed interface StyleMergeResult {

    data class Merged(
        val document: StyleDocument,
        val warnings: List<StyleIssue>,
    ) : StyleMergeResult

    data class Rejected(val errors: List<StyleIssue>) : StyleMergeResult {
        val firstMessage: String get() = errors.first().message
    }
}

sealed interface StyleResetResult {

    data class Reset(val document: StyleDocument) : StyleResetResult

    data class UnknownPath(val path: String) : StyleResetResult

    data class Rejected(val errors: List<StyleIssue>) : StyleResetResult {
        val firstMessage: String get() = errors.first().message
    }
}

enum class StyleSection { APP, HOME, PAGES, NOW_PLAYING }

/**
 * Merges a profile over the built-in document it derives from
 * (`docs/omnisonic/style/style-loader.md` §Merge).
 *
 * The merge runs on the JSON tree before decoding, because the decoder resolves
 * an absent nested field to the model default, which is not the underlying
 * style's value. Merging on the tree lets every key present in the profile win
 * at every depth while an absent key keeps the base value — including one
 * orientation of a pair and a whole list section, which are replaced as units
 * rather than element-merged.
 */
object StyleMerger {

    private const val ROOT_PATH = "\$"

    private val envelopeKeys = setOf("schemaVersion", "id", "name")

    private val treeAdapter: JsonAdapter<Any> = Moshi.Builder().build().adapter(Any::class.java)

    fun merge(base: StyleDocument, overlayText: String): StyleMergeResult {
        val baseTree = documentTree(base)
            ?: return StyleMergeResult.Rejected(listOf(StyleIssue(ROOT_PATH, "base document is unrepresentable")))
        val overlayTree = parseObject(overlayText)
            ?: return StyleMergeResult.Rejected(
                listOf(StyleIssue(ROOT_PATH, "malformed JSON: profile is not an object")),
            )
        return decode(mergeTrees(baseTree, overlayTree))
    }

    /**
     * Restores one field of [base]'s value into [profile]. [path] uses the
     * decoder's dotted key form (`app.effects.overlayAlpha`,
     * `pages.albums.columns.portrait`) and must name a key the base document
     * carries; the envelope (`schemaVersion`, `id`, `name`) is not resettable.
     */
    fun resetField(profile: StyleDocument, base: StyleDocument, path: String): StyleResetResult {
        val segments = path.split('.')
        if (path.isBlank() || segments.any { it.isBlank() }) return StyleResetResult.UnknownPath(path)
        if (segments.first() in envelopeKeys) return StyleResetResult.UnknownPath(path)
        val baseTree = documentTree(base)
            ?: return StyleResetResult.Rejected(listOf(StyleIssue(ROOT_PATH, "base document is unrepresentable")))
        val profileTree = documentTree(profile)
            ?: return StyleResetResult.Rejected(listOf(StyleIssue(ROOT_PATH, "profile document is unrepresentable")))
        val baseValue = lookup(baseTree, segments)?.value ?: return StyleResetResult.UnknownPath(path)
        val spliced = replace(profileTree, segments, baseValue) ?: return StyleResetResult.UnknownPath(path)
        return when (val merged = decode(spliced)) {
            is StyleMergeResult.Merged -> StyleResetResult.Reset(merged.document)
            is StyleMergeResult.Rejected -> StyleResetResult.Rejected(merged.errors)
        }
    }

    fun resetSection(profile: StyleDocument, base: StyleDocument, section: StyleSection): StyleDocument =
        when (section) {
            StyleSection.APP -> profile.copy(app = base.app)
            StyleSection.HOME -> profile.copy(home = base.home)
            StyleSection.PAGES -> profile.copy(pages = base.pages)
            StyleSection.NOW_PLAYING -> profile.copy(nowPlaying = base.nowPlaying)
        }

    /**
     * Restores the content of the style [base] while keeping the profile's
     * identity: the profile stays itself, derived from that built-in.
     */
    fun resetAll(profile: StyleDocument, base: StyleDocument): StyleDocument = profile.copy(
        app = base.app,
        home = base.home,
        pages = base.pages,
        nowPlaying = base.nowPlaying,
    )

    private fun decode(tree: Map<String, Any?>): StyleMergeResult =
        when (val decoded = StyleDecoder.decode(treeAdapter.toJson(tree))) {
            is StyleDecodeResult.Decoded -> StyleMergeResult.Merged(decoded.document, decoded.warnings)
            is StyleDecodeResult.Rejected -> StyleMergeResult.Rejected(decoded.errors)
        }

    private fun mergeTrees(
        base: Map<String, Any?>,
        overlay: Map<String, Any?>,
    ): Map<String, Any?> {
        val merged = LinkedHashMap<String, Any?>(base.size + overlay.size)
        merged.putAll(base)
        overlay.forEach { (key, overlayValue) ->
            val baseValue = merged[key]
            merged[key] = if (baseValue is Map<*, *> && overlayValue is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                mergeTrees(
                    baseValue as Map<String, Any?>,
                    overlayValue as Map<String, Any?>,
                )
            } else {
                overlayValue
            }
        }
        return merged
    }

    private class Found(val value: Any?)

    private fun lookup(root: Map<String, Any?>, segments: List<String>): Found? {
        var current: Any? = root
        for (segment in segments) {
            val node = current as? Map<*, *> ?: return null
            if (!node.containsKey(segment)) return null
            current = node[segment]
        }
        return Found(current)
    }

    @Suppress("UNCHECKED_CAST")
    private fun replace(
        node: Map<String, Any?>,
        segments: List<String>,
        value: Any?,
    ): Map<String, Any?>? {
        val key = segments.first()
        if (!node.containsKey(key)) return null
        val copy = LinkedHashMap<String, Any?>(node)
        if (segments.size == 1) {
            copy[key] = value
            return copy
        }
        val child = node[key] as? Map<*, *> ?: return null
        copy[key] = replace(child as Map<String, Any?>, segments.drop(1), value) ?: return null
        return copy
    }

    private fun documentTree(document: StyleDocument): Map<String, Any?>? =
        when (val written = StyleWriter.write(document)) {
            is StyleWriteResult.Failed -> null
            is StyleWriteResult.Written -> parseObject(written.text)
        }

    private fun parseObject(text: String): Map<String, Any?>? {
        val tree = try {
            treeAdapter.fromJson(text)
        } catch (failure: IOException) {
            return null
        } catch (failure: JsonDataException) {
            return null
        }
        val node = tree as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST")
        return node as Map<String, Any?>
    }
}
