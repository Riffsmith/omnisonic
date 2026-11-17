package app.omnisonic.feature.search.search

/**
 * Autofocus decision for the search query field. [isActive] must be the
 * settled, actually-selected tab state — never the pager's transient
 * `currentPage`, which passes through Search while a tab tap animates
 * elsewhere.
 */
object SearchFocusRules {

    fun shouldRequestFocus(
        isActive: Boolean,
        phase: SearchPhase,
        autofocusOnEntry: Boolean,
    ): Boolean = isActive && autofocusOnEntry && phase == SearchPhase.IDLE
}
