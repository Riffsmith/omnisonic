package app.omnisonic.feature.library.browse

import androidx.annotation.StringRes
import app.omnisonic.feature.library.R

/**
 * Library sections (UI_SPEC.md §3.4). [id] equals the `library/{section}`
 * route argument (ShellRoutes — extend only, never rename). The fixed
 * Phase 5 hub order; per-user ordering is a later-phase shell preference.
 */
enum class LibrarySection(val id: String, @StringRes val labelRes: Int) {
    ALBUMS("albums", R.string.library_section_albums),
    ARTISTS("artists", R.string.library_section_artists),
    GENRES("genres", R.string.library_section_genres),
    SONGS("songs", R.string.library_section_songs),
    PLAYLISTS("playlists", R.string.library_section_playlists),
    ;

    companion object {
        val DEFAULT_ORDER = entries.toList()

        fun fromId(id: String): LibrarySection? = entries.firstOrNull { it.id == id }
    }
}
