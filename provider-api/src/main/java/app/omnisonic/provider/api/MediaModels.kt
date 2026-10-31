package app.omnisonic.provider.api

enum class ProviderMediaType { SONG, ALBUM, ARTIST }

enum class AlbumListType { NEWEST, ALPHABETICAL_BY_NAME, ALPHABETICAL_BY_ARTIST, BY_GENRE, RANDOM }

data class ProviderAlbumRequest(
    val type: AlbumListType,
    val size: Int = DEFAULT_PAGE_SIZE,
    val offset: Int = 0,
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 500
    }
}

data class ProviderArtist(
    val externalId: String,
    val title: String,
    val imagePath: String? = null,
    val albumCount: Int? = null,
    val songCount: Int? = null,
)

data class ProviderAlbum(
    val externalId: String,
    val title: String,
    val artistExternalId: String? = null,
    val artistName: String? = null,
    val year: Int? = null,
    val imagePath: String? = null,
    val songCount: Int? = null,
    val durationMs: Long? = null,
    val genres: List<String> = emptyList(),
    val createdAtMs: Long? = null,
)

/** One album payload together with its song list, from a single fetch. */
data class ProviderAlbumDetails(
    val album: ProviderAlbum,
    val songs: List<ProviderSong> = emptyList(),
)

data class ProviderSong(
    val externalId: String,
    val title: String,
    val artistExternalId: String? = null,
    val artistName: String? = null,
    val albumExternalId: String? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val imagePath: String? = null,
    val durationMs: Long? = null,
    val filePath: String? = null,
    val sizeBytes: Long? = null,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val starredAtMs: Long? = null,
)

data class ProviderGenre(
    val externalId: String,
    val songCount: Int? = null,
    val albumCount: Int? = null,
)

data class ProviderRadio(
    val externalId: String,
    val name: String,
    val streamUrl: String,
    val homePageUrl: String? = null,
)

data class ProviderPlaylistSummary(
    val externalId: String,
    val name: String,
    val songCount: Int? = null,
    val durationMs: Long? = null,
    val owner: String? = null,
    val changedAtMs: Long? = null,
)

data class ProviderPlaylistEntry(
    val externalId: String,
    val title: String,
    val artistName: String? = null,
    val durationMs: Long? = null,
    val imagePath: String? = null,
)

data class ProviderPlaylist(
    val summary: ProviderPlaylistSummary,
    val entries: List<ProviderPlaylistEntry> = emptyList(),
)

data class ProviderBookmark(
    val entryExternalId: String,
    val positionMs: Long,
    val updatedAtMs: Long? = null,
)

data class ProviderDirectory(
    val externalId: String,
    val childAlbums: List<ProviderAlbum> = emptyList(),
    val childSongs: List<ProviderSong> = emptyList(),
)

data class ProviderCapabilities(
    val extensionNames: Set<String> = emptySet(),
    val serverSoftwareHint: String? = null,
    val backdateScrobbles: BackdateScrobbles = BackdateScrobbles.AUTO,
) {
    /**
     * Backdated scrobble support follows the D-8 policy: the per-provider
     * override forces on/off, and `auto` enables it only when the configured
     * server software is known to honor the scrobble `time` parameter.
     */
    val supportsBackdatedScrobbles: Boolean
        get() = when (backdateScrobbles) {
            BackdateScrobbles.ON -> true
            BackdateScrobbles.OFF -> false
            BackdateScrobbles.AUTO -> serverSoftwareHint?.trim()?.lowercase() in KNOWN_BACKDATING_SERVERS
        }

    companion object {
        val KNOWN_BACKDATING_SERVERS = setOf("navidrome")
    }
}

enum class BackdateScrobbles { AUTO, ON, OFF }
