package app.omnisonic.core.data.style

import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.style.StyleDecodeResult
import app.omnisonic.core.model.style.StyleDecoder
import app.omnisonic.core.model.style.StyleDocument
import app.omnisonic.core.model.style.StyleIssue

sealed interface StyleProfileState {

    data class Resolved(
        val document: StyleDocument,
        val baseId: String,
        val warnings: List<StyleIssue>,
        val fellBack: Boolean,
    ) : StyleProfileState

    data class Unavailable(val errors: List<StyleIssue>) : StyleProfileState {
        val firstMessage: String get() = errors.first().message
    }
}

/**
 * Resolves the active style profile over the built-in it derives from
 * (`docs/omnisonic/style/style-loader.md` §Fallback).
 *
 * A null [resolve] argument means no style has been chosen yet and yields the
 * default built-in quietly. A named profile that is missing, unreadable or
 * invalid yields the same default with exactly one warning; the file is never
 * touched, so a corrupt profile stays on disk for inspection.
 */
class StyleProfileResolver(
    private val repository: StyleRepository,
    private val defaultStyleId: String,
    private val logger: OmnisonicLogger,
) {

    fun resolve(activeId: String?): StyleProfileState {
        val base = when (val read = readBase()) {
            is BaseRead.Read -> read
            is BaseRead.Failed -> {
                logger.warn(
                    TAG,
                    "default style '$defaultStyleId' is unavailable: ${read.errors.first().message}",
                )
                return StyleProfileState.Unavailable(read.errors)
            }
        }
        if (activeId == null) {
            return StyleProfileState.Resolved(base.document, base.document.id, base.warnings, fellBack = false)
        }
        return resolveOver(base, activeId, logFallback = true)
    }

    /**
     * The same merge/identity contract as [resolve], but a candidate that cannot
     * be applied returns its path-qualified errors instead of falling back, so
     * a switch never mistakes the default for a successfully selected id.
     */
    fun resolveCandidate(activeId: String): StyleProfileState {
        val base = when (val read = readBase()) {
            is BaseRead.Read -> read
            is BaseRead.Failed -> {
                logger.warn(
                    TAG,
                    "default style '$defaultStyleId' is unavailable: ${read.errors.first().message}",
                )
                return StyleProfileState.Unavailable(read.errors)
            }
        }
        return resolveOver(base, activeId, logFallback = false)
    }

    private fun resolveOver(
        base: BaseRead.Read,
        activeId: String,
        logFallback: Boolean,
    ): StyleProfileState {
        val overlayText = when (val text = repository.readText(activeId)) {
            is StyleTextResult.Loaded -> text.text
            is StyleTextResult.Rejected ->
                return failure(base, activeId, text.errors, logFallback)

            is StyleTextResult.Unreadable ->
                return failure(base, activeId, listOf(StyleIssue(activeId, text.detail)), logFallback)

            is StyleTextResult.NotFound ->
                return failure(base, activeId, listOf(StyleIssue(activeId, "the file does not exist")), logFallback)

            is StyleTextResult.InvalidId ->
                return failure(base, activeId, listOf(StyleIssue(activeId, "invalid style id")), logFallback)
        }
        return when (val merged = StyleMerger.merge(base.document, overlayText)) {
            is StyleMergeResult.Merged ->
                if (merged.document.id == activeId) {
                    StyleProfileState.Resolved(merged.document, base.document.id, merged.warnings, fellBack = false)
                } else {
                    failure(
                        base,
                        activeId,
                        listOf(StyleIssue("id", "does not match the file name '$activeId'")),
                        logFallback,
                    )
                }

            is StyleMergeResult.Rejected -> failure(base, activeId, merged.errors, logFallback)
        }
    }

    private fun failure(
        base: BaseRead.Read,
        activeId: String,
        errors: List<StyleIssue>,
        logFallback: Boolean,
    ): StyleProfileState =
        if (logFallback) {
            fallback(base, activeId, errors.first().message)
        } else {
            StyleProfileState.Unavailable(errors)
        }

    private fun fallback(base: BaseRead.Read, activeId: String, reason: String): StyleProfileState.Resolved {
        logger.warn(
            TAG,
            "active style '$activeId' is unavailable ($reason); using default style '$defaultStyleId'",
        )
        return StyleProfileState.Resolved(base.document, base.document.id, base.warnings, fellBack = true)
    }

    private sealed interface BaseRead {

        data class Read(
            val document: StyleDocument,
            val warnings: List<StyleIssue>,
        ) : BaseRead

        data class Failed(val errors: List<StyleIssue>) : BaseRead
    }

    private fun readBase(): BaseRead {
        val text = when (val result = repository.readText(defaultStyleId)) {
            is StyleTextResult.Loaded -> result.text
            is StyleTextResult.Rejected -> return BaseRead.Failed(result.errors)
            is StyleTextResult.Unreadable -> return BaseRead.Failed(listOf(StyleIssue(defaultStyleId, result.detail)))
            is StyleTextResult.NotFound -> return BaseRead.Failed(listOf(StyleIssue(defaultStyleId, "not found")))
            is StyleTextResult.InvalidId -> return BaseRead.Failed(listOf(StyleIssue(defaultStyleId, "invalid id")))
        }
        return when (val decoded = StyleDecoder.decode(text)) {
            is StyleDecodeResult.Decoded ->
                if (decoded.document.id == defaultStyleId) {
                    BaseRead.Read(decoded.document, decoded.warnings)
                } else {
                    BaseRead.Failed(
                        listOf(StyleIssue("id", "does not match the file name '$defaultStyleId'")),
                    )
                }

            is StyleDecodeResult.Rejected -> BaseRead.Failed(decoded.errors)
        }
    }

    private companion object {
        private const val TAG = "styleProfile"
    }
}
