package app.omnisonic.provider.api

/**
 * Read/writer surface per provider connection. Implementations map these calls
 * onto their wire protocol; all methods are safe to call from coroutines.
 */
interface MediaProvider {
    suspend fun probe(): ProviderCapabilities

    suspend fun listArtists(): List<ProviderArtist>
    suspend fun browseDirectory(externalId: String): ProviderDirectory
    suspend fun albumDetails(albumExternalId: String): ProviderAlbum?
    suspend fun songDetails(externalId: String): ProviderSong?
    suspend fun listAlbums(request: ProviderAlbumRequest): List<ProviderAlbum>
    suspend fun listGenres(): List<ProviderGenre>
    suspend fun listRadios(): List<ProviderRadio>
    suspend fun listPlaylists(): List<ProviderPlaylistSummary>
    suspend fun getPlaylist(playlistExternalId: String): ProviderPlaylist?
    suspend fun listBookmarks(): List<ProviderBookmark>

    suspend fun searchArtists(query: String, offset: Int = 0, count: Int = SEARCH_COUNT): List<ProviderArtist>
    suspend fun searchAlbums(query: String, offset: Int = 0, count: Int = SEARCH_COUNT): List<ProviderAlbum>
    suspend fun searchSongs(query: String, offset: Int = 0, count: Int = SEARCH_COUNT): List<ProviderSong>

    fun streamUrl(externalId: String, maxBitRateKbps: Int? = null, format: String? = null): String?
    fun coverArtUrl(imageId: String?, sizePx: Int? = null): String?
    fun downloadUrl(externalId: String, maxBitRateKbps: Int? = null, format: String? = null): String?

    /**
     * Reports [playCount] listens for [externalId]; returns how many submissions
     * the server accepted (implementations may batch individual increments).
     */
    suspend fun submitPlays(externalId: String, playCount: Int, lastPlayedAtMs: Long?): Int

    suspend fun sendNowPlaying(externalId: String, playedMs: Long = 0L): Boolean
    suspend fun setFavorite(mediaType: ProviderMediaType, externalId: String, favorite: Boolean): Boolean
    suspend fun setUserRating(mediaType: ProviderMediaType, externalId: String, rating: Int): Boolean
    suspend fun saveBookmark(externalId: String, positionMs: Long): Boolean
    suspend fun deleteBookmark(externalId: String): Boolean

    suspend fun similarSongs(externalId: String, count: Int = SIMILAR_COUNT): List<ProviderSong>

    companion object {
        const val SEARCH_COUNT = 20
        const val SIMILAR_COUNT = 20
    }
}

interface MediaProviderFactory {
    /** Registry key, e.g. "subsonic" or "local". */
    fun apiType(): String

    fun create(connection: ProviderConnection): MediaProvider
}

class ProviderRegistry(factories: List<MediaProviderFactory>) {
    private val factoriesByType = factories.associateBy { it.apiType() }

    fun factoryFor(apiType: String): MediaProviderFactory? = factoriesByType[apiType]

    fun create(apiType: String, connection: ProviderConnection): MediaProvider? =
        factoryFor(apiType)?.create(connection)
}
