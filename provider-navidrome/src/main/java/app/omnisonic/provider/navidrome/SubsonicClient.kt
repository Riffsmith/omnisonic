package app.omnisonic.provider.navidrome

import app.omnisonic.provider.api.AlbumListType
import app.omnisonic.provider.api.ProviderAuthMethod
import app.omnisonic.provider.api.BlockAttempt
import app.omnisonic.provider.api.BlockFailover
import app.omnisonic.provider.api.ConnectionBlock
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.ProviderAlbum
import app.omnisonic.provider.api.ProviderAlbumRequest
import app.omnisonic.provider.api.ProviderArtist
import app.omnisonic.provider.api.ProviderBookmark
import app.omnisonic.provider.api.ProviderCapabilities
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderDirectory
import app.omnisonic.provider.api.ProviderFatalException
import app.omnisonic.provider.api.ProviderGenre
import app.omnisonic.provider.api.ProviderHttpError
import app.omnisonic.provider.api.ProviderMediaType
import app.omnisonic.provider.api.ProviderPlaylist
import app.omnisonic.provider.api.ProviderPlaylistEntry
import app.omnisonic.provider.api.ProviderPlaylistSummary
import app.omnisonic.provider.api.ProviderRadio
import app.omnisonic.provider.api.ProviderSong
import app.omnisonic.provider.api.ProviderTransportException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZonedDateTime
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Subsonic/Navidrome REST client (protocol line `u/t/s/v/c/f`, JSON replies).
 * Subsonic error payloads arrive as HTTP 200 with `status:"failed"`, mapped to
 * fatal failures; HTTP >=500 surfaces through the block failover preference.
 */
class SubsonicClient(
    private val connection: ProviderConnection,
    private val http: OkHttpClient,
    private val onPreferredBlockChange: (Int) -> Unit = {},
) : MediaProvider {

    private val failover = BlockFailover(connection, onPreferredBlockChange)

    @Volatile
    private var capabilities: ProviderCapabilities = ProviderCapabilities()

    override suspend fun probe(): ProviderCapabilities {
        call(Endpoint.PING) { it }
        // getOpenSubsonicExtensions is OpenSubsonic-only; older servers reject
        // the endpoint, which is terminal for the call but not for the protocol.
        val extensions = runCatching {
            call(Endpoint.OPEN_SUBSONIC_EXTENSIONS) { it }.openSubsonicExtensions
        }.getOrDefault(emptyList())
        val caps = ProviderCapabilities(
            extensionNames = extensions.mapNotNull { it.name }.toSet(),
            serverSoftwareHint = connection.serverSoftwareHint,
        )
        capabilities = caps
        return caps
    }

    override suspend fun listArtists(): List<ProviderArtist> = call(Endpoint.GET_ARTISTS) { envelope ->
        envelope.artists?.index.orEmpty()
            .flatMap { it.artist }
            .mapNotNull { artist -> artist.toArtist() }
    }

    override suspend fun browseDirectory(externalId: String): ProviderDirectory = call(
        Endpoint.GET_MUSIC_DIRECTORY,
        listOf("id" to externalId),
    ) { envelope ->
        val directory = envelope.directory.firstOrNull()
            ?: SubsonicDirectoryDto(id = externalId)
        val children = directory.child
        ProviderDirectory(
            externalId = directory.id ?: externalId,
            childAlbums = children.filter { it.isDir == true }.mapNotNull { it.toDirectoryAlbum() },
            childSongs = children.filter { it.isDir != true }.mapNotNull { it.toSong() },
        )
    }

    override suspend fun albumDetails(albumExternalId: String): ProviderAlbum? = call(
        Endpoint.GET_ALBUM,
        listOf("id" to albumExternalId),
    ) { envelope -> envelope.album.firstOrNull()?.toAlbum() }

    override suspend fun songDetails(externalId: String): ProviderSong? = call(
        Endpoint.GET_SONG,
        listOf("id" to externalId),
    ) { envelope -> envelope.song.firstOrNull()?.toSong() }

    override suspend fun listAlbums(request: ProviderAlbumRequest): List<ProviderAlbum> = call(
        Endpoint.GET_ALBUM_LIST2,
        listOf(
            "type" to request.type.toParam(),
            "size" to request.size.toString(),
            "offset" to request.offset.toString(),
        ),
    ) { envelope -> envelope.albumList2?.album.orEmpty().mapNotNull { it.toAlbum() } }

    override suspend fun listGenres(): List<ProviderGenre> = call(Endpoint.GET_GENRES) { envelope ->
        envelope.genres?.genre.orEmpty().mapNotNull { genre ->
            val name = genre.value ?: return@mapNotNull null
            ProviderGenre(externalId = name, songCount = genre.songCount, albumCount = genre.albumCount)
        }
    }

    override suspend fun listRadios(): List<ProviderRadio> =
        call(Endpoint.GET_INTERNET_RADIO_STATIONS) { envelope ->
            envelope.internetRadioStations?.station.orEmpty().mapNotNull { station ->
                val id = station.id ?: return@mapNotNull null
                val stream = station.streamUrl ?: return@mapNotNull null
                ProviderRadio(
                    externalId = id,
                    name = station.name.orEmpty(),
                    streamUrl = stream,
                    homePageUrl = station.homePageUrl,
                )
            }
        }

    override suspend fun listPlaylists(): List<ProviderPlaylistSummary> = call(Endpoint.GET_PLAYLISTS) { envelope ->
        envelope.playlists?.playlist.orEmpty().mapNotNull { playlist ->
            val id = playlist.id ?: return@mapNotNull null
            ProviderPlaylistSummary(
                externalId = id,
                name = playlist.name.orEmpty(),
                songCount = playlist.songCount,
                durationMs = playlist.duration.multipledBy1000(),
                owner = playlist.owner,
                changedAtMs = playlist.changed.epochMs(),
            )
        }
    }

    override suspend fun getPlaylist(playlistExternalId: String): ProviderPlaylist? = call(
        Endpoint.GET_PLAYLIST,
        listOf("id" to playlistExternalId),
    ) { envelope ->
        val playlist = envelope.playlist.firstOrNull() ?: return@call null
        val id = playlist.id ?: playlistExternalId
        ProviderPlaylist(
            summary = ProviderPlaylistSummary(
                externalId = id,
                name = playlist.name.orEmpty(),
                songCount = playlist.songCount,
                durationMs = playlist.duration.multipledBy1000(),
                owner = playlist.owner,
                changedAtMs = playlist.changed.epochMs(),
            ),
            entries = playlist.entry.mapNotNull { entry ->
                val entryId = entry.id ?: return@mapNotNull null
                ProviderPlaylistEntry(
                    externalId = entryId,
                    title = entry.title.orEmpty(),
                    artistName = entry.artist,
                    durationMs = entry.duration.multipledBy1000(),
                    imagePath = entry.coverArt,
                )
            },
        )
    }

    override suspend fun listBookmarks(): List<ProviderBookmark> = call(Endpoint.GET_BOOKMARKS) { envelope ->
        envelope.bookmarks?.bookmark.orEmpty().mapNotNull { bookmark ->
            val entryId = bookmark.entry.firstOrNull()?.id ?: return@mapNotNull null
            ProviderBookmark(
                entryExternalId = entryId,
                positionMs = bookmark.position ?: 0L,
                updatedAtMs = bookmark.changed.epochMs(),
            )
        }
    }

    override suspend fun searchArtists(query: String, offset: Int, count: Int): List<ProviderArtist> =
        search(query, offset, count, "artist") { dto -> dto.artist.mapNotNull { it.toArtist() } }

    override suspend fun searchAlbums(query: String, offset: Int, count: Int): List<ProviderAlbum> =
        search(query, offset, count, "album") { dto -> dto.album.mapNotNull { it.toAlbum() } }

    override suspend fun searchSongs(query: String, offset: Int, count: Int): List<ProviderSong> =
        search(query, offset, count, "song") { dto -> dto.song.mapNotNull { it.toSong() } }

    private suspend fun <T> search(
        query: String,
        offset: Int,
        count: Int,
        kind: String,
        decode: (SubsonicSearchDto) -> List<T>,
    ): List<T> = call(
        Endpoint.SEARCH3,
        listOf(
            "query" to query,
            "${kind}Offset" to offset.toString(),
            "${kind}Count" to count.toString(),
        ),
    ) { envelope -> decode(envelope.searchResult3 ?: SubsonicSearchDto()) }

    private fun SubsonicArtistDto.toArtist(): ProviderArtist? {
        val id = id ?: return null
        return ProviderArtist(
            externalId = id,
            title = name.orEmpty(),
            imagePath = coverArt,
            albumCount = albumCount,
            songCount = songCount,
        )
    }

    private fun SubsonicChildDto.toSong(): ProviderSong? {
        val id = id ?: return null
        return ProviderSong(
            externalId = id,
            title = title.orEmpty(),
            artistExternalId = artistId,
            artistName = artist,
            albumExternalId = albumId,
            trackNo = track,
            discNo = discNumber,
            year = year,
            genre = genre,
            imagePath = coverArt,
            durationMs = duration.multipledBy1000(),
            filePath = path,
            sizeBytes = size,
            codec = suffix ?: contentType?.substringAfterLast('/'),
            bitrateKbps = bitRate,
            starredAtMs = starred.epochMs(),
        )
    }

    private fun SubsonicChildDto.toDirectoryAlbum(): ProviderAlbum? {
        val id = id ?: return null
        return ProviderAlbum(
            externalId = id,
            title = album ?: title.orEmpty(),
            artistExternalId = artistId,
            artistName = artist,
            year = year,
            imagePath = coverArt,
            songCount = null,
            durationMs = null,
            genres = listOfNotNull(genre),
        )
    }

    private fun SubsonicAlbumDto.toAlbum(): ProviderAlbum? {
        val id = id ?: return null
        return ProviderAlbum(
            externalId = id,
            title = title ?: name ?: album.orEmpty(),
            artistExternalId = artistId,
            artistName = artist,
            year = year,
            imagePath = coverArt,
            songCount = songCount,
            durationMs = duration.multipledBy1000(),
            genres = listOfNotNull(genre),
            createdAtMs = created.epochMs(),
        )
    }

    private fun Int?.multipledBy1000(): Long? = this?.let { it * 1_000L }

    /** Accepts "2024-05-20T15:51:01.099Z" and "2024-05-20 15:51:01.099" styles. */
    private fun String?.epochMs(): Long? {
        val raw = this ?: return null
        val candidates = buildList {
            add(raw)
            val dashed = raw.replace(' ', 'T')
            add("$dashed" + ("+00:00"))
            add(dashed.substringBefore('.') + ".000Z")
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching { ZonedDateTime.parse(candidate).toInstant().toEpochMilli() }.getOrNull()
        }
    }

    override fun streamUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String =
        mediaUrl("stream", externalId, maxBitRateKbps, format)

    override fun coverArtUrl(imageId: String?, sizePx: Int?): String? {
        if (imageId.isNullOrBlank()) return null
        val params = buildList {
            add("id" to imageId)
            sizePx?.let { add("size" to it.toString()) }
        }
        return buildUrl(preferredBlock(), "getCoverArt", params)
    }

    override fun downloadUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String =
        mediaUrl("download", externalId, maxBitRateKbps, format)

    override suspend fun submitPlays(externalId: String, playCount: Int, lastPlayedAtMs: Long?): Int {
        if (playCount <= 0) return 0
        val params = scrobbleParams(externalId, submission = true, timeMs = lastPlayedAtMs)
        var accepted = 0
        repeat(playCount) {
            // A rejected submission counts as not-accepted rather than aborting
            // the batch: the drainer reports partial acceptance as progress.
            val envelope = runCatching { call(Endpoint.SCROBBLE, params) { it } }.getOrNull()
            if (envelope?.status == STATUS_OK) accepted++
        }
        return accepted
    }

    override suspend fun sendNowPlaying(externalId: String, playedMs: Long): Boolean {
        val envelope = call(
            Endpoint.SCROBBLE,
            scrobbleParams(externalId, submission = false, timeMs = playedMs),
        ) { it }
        return envelope.status == STATUS_OK
    }

    private fun scrobbleParams(externalId: String, submission: Boolean, timeMs: Long?): List<Pair<String, String>> =
        buildList {
            add("id" to externalId)
            if (capabilities.supportsBackdatedScrobbles && timeMs != null) {
                add("time" to timeMs.toString())
            }
            add("submission" to submission.toString())
        }

    override suspend fun setFavorite(
        mediaType: ProviderMediaType,
        externalId: String,
        favorite: Boolean,
    ): Boolean {
        val envelope = call(
            if (favorite) Endpoint.STAR else Endpoint.UNSTAR,
            mediaType.idParams(externalId),
        ) { it }
        return envelope.status == STATUS_OK
    }

    override suspend fun setUserRating(mediaType: ProviderMediaType, externalId: String, rating: Int): Boolean {
        val envelope = call(
            Endpoint.SET_RATING,
            listOf("id" to externalId, "rating" to rating.toString()),
        ) { it }
        return envelope.status == STATUS_OK
    }

    override suspend fun saveBookmark(externalId: String, positionMs: Long): Boolean {
        val envelope = call(
            Endpoint.CREATE_BOOKMARK,
            listOf("id" to externalId, "position" to positionMs.toString()),
        ) { it }
        return envelope.status == STATUS_OK
    }

    override suspend fun deleteBookmark(externalId: String): Boolean {
        val envelope = call(Endpoint.DELETE_BOOKMARK, listOf("id" to externalId)) { it }
        return envelope.status == STATUS_OK
    }

    override suspend fun similarSongs(externalId: String, count: Int): List<ProviderSong> = call(
        Endpoint.GET_SIMILAR_SONGS,
        listOf("id" to externalId, "count" to count.toString()),
    ) { envelope -> envelope.similarSongs?.song.orEmpty().mapNotNull { it.toSong() } }

    private fun ProviderMediaType.idParams(externalId: String): List<Pair<String, String>> = when (this) {
        ProviderMediaType.SONG -> listOf("id" to externalId)
        ProviderMediaType.ALBUM -> listOf("albumId" to externalId)
        ProviderMediaType.ARTIST -> listOf("artistId" to externalId)
    }

    private fun AlbumListType.toParam(): String = when (this) {
        AlbumListType.NEWEST -> "newest"
        AlbumListType.ALPHABETICAL_BY_NAME -> "alphabeticalByName"
        AlbumListType.ALPHABETICAL_BY_ARTIST -> "alphabeticalByArtist"
        AlbumListType.BY_GENRE -> "byGenre"
        AlbumListType.RANDOM -> "random"
    }

    private fun mediaUrl(path: String, externalId: String, maxBitRateKbps: Int?, format: String?): String {
        val params = buildList {
            add("id" to externalId)
            maxBitRateKbps?.let { add("maxBitRate" to it.toString()) }
            format?.let { add("format" to it) }
        }
        return buildUrl(preferredBlock(), path, params)
    }

    private fun preferredBlock(): ConnectionBlock =
        if (failover.preferredBlock == ProviderConnection.SECONDARY_BLOCK) {
            connection.secondary ?: connection.primary
        } else {
            connection.primary
        }

    // ---- transport -----------------------------------------------------------

    private suspend fun <T> call(
        endpoint: String,
        params: List<Pair<String, String>> = emptyList(),
        decode: (SubsonicEnvelope) -> T,
    ): T = when (val attempt = failover.attempt { block -> requestAttempt(block, endpoint, params, decode) }) {
        is BlockAttempt.Success -> attempt.value
        is BlockAttempt.StatusError -> throw ProviderHttpError(attempt.httpCode, attempt.message)
        is BlockAttempt.TransportError -> throw ProviderTransportException(attempt.cause)
        is BlockAttempt.FatalError -> throw attempt.cause
    }

    private fun buildUrl(
        block: ConnectionBlock,
        endpoint: String,
        params: List<Pair<String, String>>,
    ): String {
        val builder = "${block.baseUrl()}/rest/$endpoint.view".toHttpUrl().newBuilder()
        (authParams() + params).forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build().toString()
    }

    private fun authParams(): List<Pair<String, String>> {
        val params = mutableListOf<Pair<String, String>>()
        when (connection.authMethod) {
            ProviderAuthMethod.TOKEN -> {
                val salt = newSalt()
                params += "u" to connection.username
                params += "t" to tokenFor(connection.password ?: "", salt)
                params += "s" to salt
            }
            ProviderAuthMethod.LEGACY_PASSWORD -> {
                params += "u" to connection.username
                params += "p" to "enc:" + connection.password
                    ?.toByteArray(Charsets.UTF_8)
                    ?.joinToString("") { "%02x".format(it) }
                    .orEmpty()
            }
            ProviderAuthMethod.API_KEY -> {
                params += "apiKey" to connection.apiKey.orEmpty()
            }
        }
        params += "v" to API_VERSION
        params += "c" to CLIENT_ID
        params += "f" to "json"
        return params
    }

    private fun tokenFor(password: String, salt: String): String =
        MessageDigest.getInstance("MD5")
            .digest((password + salt).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private suspend fun <T> requestAttempt(
        block: ConnectionBlock,
        endpoint: String,
        params: List<Pair<String, String>>,
        decode: (SubsonicEnvelope) -> T,
    ): BlockAttempt<T> {
        val request = Request.Builder().url(buildUrl(block, endpoint, params)).build()
        val response = runCatching { http.newCall(request).awaitResponse() }
            .getOrElse { failure -> return BlockAttempt.TransportError(failure) }
        return response.use { handled ->
            if (handled.code >= 300 || handled.code < 200) {
                return BlockAttempt.StatusError(handled.code, handled.message)
            }
            val body = handled.body?.string().orEmpty()
            if (body.isBlank()) {
                return BlockAttempt.StatusError(handled.code, "empty response")
            }
            val envelope = runCatching { decodeEnvelope(body) }.getOrElse { parseFailure ->
                return BlockAttempt.FatalError(ProviderFatalException(parseFailure))
            } ?: return BlockAttempt.FatalError(ProviderFatalException(IllegalStateException("empty subsonic envelope")))
            if (envelope.status != STATUS_OK) {
                return BlockAttempt.FatalError(ProviderFatalException(IllegalStateException(failureDescription(envelope))))
            }
            BlockAttempt.Success(decode(envelope))
        }
    }
    /**
     * The Subsonic/OpenSubsonic spec wraps responses in a top-level
     * `subsonic-response` object; a few servers and older clients post the
     * envelope body directly. Try the direct shape first, then unwrap.
     */
    private fun decodeEnvelope(body: String): SubsonicEnvelope? {
        val direct = SubsonicJson.moshi.adapter(SubsonicEnvelope::class.java).fromJson(body)
        if (direct?.status != null) {
            return direct
        }
        val wrapped = SubsonicJson.moshi.adapter(SubsonicBody::class.java).fromJson(body)
        return wrapped?.subsonicResponse ?: direct
    }

    private fun failureDescription(envelope: SubsonicEnvelope): String =
        envelope.error?.let { "code=${it.code} ${it.message.orEmpty()}" } ?: "status=${envelope.status ?: "undefined"}"

    companion object {
        private const val API_VERSION = "1.13.0"
        private const val CLIENT_ID = "Omnisonic"
        private const val STATUS_OK = "ok"
        private const val SALT_LENGTH = 12
        private const val SALT_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val saltRandom = SecureRandom()

        private fun newSalt(): String = buildString {
            for (index in 1..SALT_LENGTH) {
                append(SALT_ALPHABET[saltRandom.nextInt(SALT_ALPHABET.length)])
            }
        }
    }

    private object Endpoint {
        const val PING = "ping"
        const val OPEN_SUBSONIC_EXTENSIONS = "getOpenSubsonicExtensions"
        const val GET_ARTISTS = "getArtists"
        const val GET_INDEXES = "getIndexes"
        const val GET_MUSIC_DIRECTORY = "getMusicDirectory"
        const val GET_ALBUM = "getAlbum"
        const val GET_ALBUM_LIST2 = "getAlbumList2"
        const val GET_SONG = "getSong"
        const val SEARCH3 = "search3"
        const val GET_GENRES = "getGenres"
        const val GET_INTERNET_RADIO_STATIONS = "getInternetRadioStations"
        const val GET_PLAYLISTS = "getPlaylists"
        const val GET_PLAYLIST = "getPlaylist"
        const val GET_BOOKMARKS = "getBookmarks"
        const val SCROBBLE = "scrobble"
        const val STAR = "star"
        const val UNSTAR = "unstar"
        const val SET_RATING = "setRating"
        const val CREATE_BOOKMARK = "createBookmark"
        const val DELETE_BOOKMARK = "deleteBookmark"
        const val GET_SIMILAR_SONGS = "getSimilarSongs"
        const val STREAM = "stream"
        const val DOWNLOAD = "download"
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, failure: IOException) {
                continuation.resumeWithException(failure)
            }
        },
    )
}
