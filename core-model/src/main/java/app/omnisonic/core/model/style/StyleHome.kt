package app.omnisonic.core.model.style

/**
 * Ordered, hideable home composition. [HomeSectionEntry.visible] is the only
 * per-section presentation v1 carries; row geometry stays out of the baseline
 * (`docs/omnisonic/style/style-schema.md` §Model).
 */
data class StyleHome(
    val sections: List<HomeSectionEntry> = DEFAULT_SECTIONS,
) {
    companion object {
        val DEFAULT_SECTIONS: List<HomeSectionEntry> = listOf(
            HomeSectionEntry(HomeSection.RECENTLY_PLAYED),
            HomeSectionEntry(HomeSection.RECENTLY_ADDED),
            HomeSectionEntry(HomeSection.MOST_PLAYED),
            HomeSectionEntry(HomeSection.FAVORITES),
        )
    }
}

data class HomeSectionEntry(
    val section: HomeSection = HomeSection.RECENTLY_PLAYED,
    val visible: Boolean = true,
)

enum class HomeSection { RECENTLY_PLAYED, RECENTLY_ADDED, MOST_PLAYED, FAVORITES }
