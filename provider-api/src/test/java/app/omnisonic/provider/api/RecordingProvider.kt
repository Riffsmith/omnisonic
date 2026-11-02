package app.omnisonic.provider.api

class RecordingProvider : MediaProvider {
    val submittedPlays = mutableListOf<SubmittedPlay>()
    val favorites = mutableListOf<Triple<ProviderMediaType, String, Boolean>>()
    val userRatings = mutableListOf<Triple<ProviderMediaType, String, Int>>()
    val savedBookmarks = mutableListOf<Pair<String, Long>>()
    val deletedBookmarks = mutableListOf<String>()
    val probed = mutableListOf<Boolean>()

    /** Cross-call ordered log of every provider interaction. */
    val events = mutableListOf<String>()

    var submitPlaysResult: Int = 0
    var favoriteResult: Boolean = true
    var ratingResult: Boolean = true
    var bookmarkResult: Boolean = true
    var probeResult: ProviderCapabilities = ProviderCapabilities()
    var failNext: Boolean = false

    override suspend fun probe(): ProviderCapabilities = probeResult.also { probed.add(true) }

    override suspend fun listArtists(): List<ProviderArtist> = emptyList()

    override suspend fun browseDirectory(externalId: String): ProviderDirectory = ProviderDirectory(externalId)

    override suspend fun getAlbum(albumExternalId: String): ProviderAlbumDetails? = null

    override suspend fun songDetails(externalId: String): ProviderSong? = null

    override suspend fun listAlbums(request: ProviderAlbumRequest): List<ProviderAlbum> = emptyList()

    override suspend fun listGenres(): List<ProviderGenre> = emptyList()

    override suspend fun listRadios(): List<ProviderRadio> = emptyList()

    override suspend fun listPlaylists(): List<ProviderPlaylistSummary> = emptyList()

    override suspend fun getPlaylist(playlistExternalId: String): ProviderPlaylist? = null

    override suspend fun listBookmarks(): List<ProviderBookmark> = emptyList()

    override suspend fun searchArtists(query: String, offset: Int, count: Int): List<ProviderArtist> = emptyList()

    override suspend fun searchAlbums(query: String, offset: Int, count: Int): List<ProviderAlbum> = emptyList()

    override suspend fun searchSongs(query: String, offset: Int, count: Int): List<ProviderSong> = emptyList()

    override fun streamUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? = null

    override fun coverArtUrl(imageId: String?, sizePx: Int?): String? = null

    override fun downloadUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? = null

    override suspend fun submitPlays(externalId: String, timestamps: List<Long>): Int {
        submittedPlays += SubmittedPlay(externalId, timestamps)
        events += "submitPlays"
        return submitPlaysResult
    }

    override suspend fun sendNowPlaying(externalId: String, playedMs: Long): Boolean = true

    override suspend fun setFavorite(mediaType: ProviderMediaType, externalId: String, favorite: Boolean): Boolean {
        if (failNext) {
            failNext = false
            throw IllegalStateException("injected")
        }
        favorites += Triple(mediaType, externalId, favorite)
        events += "setFavorite"
        return favoriteResult
    }

    override suspend fun setUserRating(mediaType: ProviderMediaType, externalId: String, rating: Int): Boolean {
        userRatings += Triple(mediaType, externalId, rating)
        events += "setUserRating"
        return ratingResult
    }

    override suspend fun saveBookmark(externalId: String, positionMs: Long): Boolean {
        savedBookmarks += externalId to positionMs
        events += "saveBookmark"
        return bookmarkResult
    }

    override suspend fun deleteBookmark(externalId: String): Boolean {
        deletedBookmarks += externalId
        events += "deleteBookmark"
        return bookmarkResult
    }

    override suspend fun similarSongs(externalId: String, count: Int): List<ProviderSong> = emptyList()

    data class SubmittedPlay(val externalId: String, val timestamps: List<Long>)
}
