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
) {
    /**
     * Backdated scrobbles are accepted when the instance advertises an
     * OpenSubsonic extension whose spec documents server-side acceptance of
     * explicit replay timestamps (formPost, transcodeOffset).
     */
    val supportsBackdatedScrobbles: Boolean
        get() = extensionNames.contains("formPost") || extensionNames.contains("transcodeOffset")
}
