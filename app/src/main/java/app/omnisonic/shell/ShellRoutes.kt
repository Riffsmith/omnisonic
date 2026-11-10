package app.omnisonic.shell

/**
 * Navigation route table (reference/docs/UI_SCREENS.md §5 — the stability
 * contract: extend only, never rename; screens, shortcuts and future gesture
 * targets reference these strings).
 *
 * Reserved names, deliberately absent (do not squat): downloads, profiles,
 * styles, styleEditor, lyrics.
 */
object ShellRoutes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SECTION = "library/{section}"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val PROVIDER_SETUP = "providerSetup"
    const val LIBRARY_SYNC = "librarySync"
    const val ALBUM = "album/{albumId}"
    const val ARTIST = "artist/{artistId}"
    const val GENRE = "genre/{genreId}"
    const val PLAYLIST = "playlist/{playlistId}"
    const val NOW_PLAYING = "nowplaying"
    const val QUEUE = "queue"

    const val ARG_SECTION = "section"
    const val ARG_ALBUM_ID = "albumId"
    const val ARG_ARTIST_ID = "artistId"
    const val ARG_GENRE_ID = "genreId"
    const val ARG_PLAYLIST_ID = "playlistId"

    fun album(albumId: Long): String = "album/$albumId"
    fun artist(artistId: Long): String = "artist/$artistId"
    fun genre(genreId: Long): String = "genre/$genreId"
    fun playlist(playlistId: Long): String = "playlist/$playlistId"
    fun section(section: String): String = "library/$section"

    /** Every pattern the graph registers, in table order (§5). */
    val ALL: List<String> = listOf(
        HOME,
        LIBRARY,
        SECTION,
        SEARCH,
        SETTINGS,
        PROVIDER_SETUP,
        LIBRARY_SYNC,
        ALBUM,
        ARTIST,
        GENRE,
        PLAYLIST,
        NOW_PLAYING,
        QUEUE,
    )
}
