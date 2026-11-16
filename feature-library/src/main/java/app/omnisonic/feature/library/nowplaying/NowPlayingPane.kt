package app.omnisonic.feature.library.nowplaying

/**
 * Which content the now-playing art block shows (D-23). It is a pane state on
 * the player view model, not a route: collapsing the sheet keeps it, so the
 * player returns to the pane the user left it on while the process lives.
 */
enum class NowPlayingPane { COVER, LYRICS }

/**
 * Seam for the lyrics pane. The data layer (provider fetch, parsing, synced
 * line highlighting) is a separate feature; until it lands the pane renders
 * its empty state and this type carries the shape the screen already consumes.
 */
data class LyricsUiState(
    val lines: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = lines.isEmpty()
}
