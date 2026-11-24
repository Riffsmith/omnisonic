package app.omnisonic.style

import app.omnisonic.core.model.style.StyleDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Observable token seam (`docs/omnisonic/style/style-profiles.md` §Wiring, D-79).
 * [tokenFlow] is the authority for a repaint; [tokens] stays available for the
 * existing synchronous readers (`DefaultStyleTokenSource` still serves a
 * no-store context).
 */
interface ObservableStyleTokenSource : StyleTokenSource {

    val tokenFlow: Flow<StyleTokens>
}

/**
 * Profile-backed implementation of [ObservableStyleTokenSource].
 *
 * [activeDocument] is the loader's already merged and validated active document
 * (D-72/D-73), or `null` while no usable profile exists. A `null` input is
 * skipped rather than resolved, so an unavailable or malformed profile keeps the
 * last good tokens in place instead of replacing them with a second fallback
 * rule here. Repeated equal resolutions collapse to one emission.
 *
 * [fallback] backs the synchronous [tokens] read for callers that cannot await
 * the flow, and for the window before the first emission.
 */
class ProfileStyleTokenSource(
    activeDocument: Flow<StyleDocument?>,
    private val fallback: StyleTokens = DefaultStyleTokens,
) : ObservableStyleTokenSource {

    override val tokenFlow: Flow<StyleTokens> =
        activeDocument
            .map { document -> document?.let(StyleTokenResolver::resolve) }
            .filterNotNull()
            .distinctUntilChanged()

    override fun tokens(): StyleTokens = fallback
}
