package app.omnisonic.core.model.style

/**
 * Per-library presentation. Every list carries its own portrait/landscape
 * column and spacing pair so one orientation can change without touching the
 * other (D-64).
 */
data class StylePages(
    val albums: StyleListLayout = StyleListLayout(),
    val artists: StyleListLayout = StyleListLayout(),
    val genres: StyleListLayout = StyleListLayout(),
    val songs: StyleListLayout = StyleListLayout(),
    val playlists: StyleListLayout = StyleListLayout(),
)

data class StyleListLayout(
    val columns: OrientationPair<Int> = DEFAULT_COLUMNS,
    val spacingDp: OrientationPair<Float> = DEFAULT_SPACING,
) {
    companion object {
        val DEFAULT_COLUMNS: OrientationPair<Int> = OrientationPair(portrait = 3, landscape = 5)
        val DEFAULT_SPACING: OrientationPair<Float> = OrientationPair(portrait = 8f, landscape = 8f)
    }
}
