package app.omnisonic.provider.navidrome

import app.omnisonic.core.model.log.ConsoleLogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.core.network.HttpClientFactory
import app.omnisonic.provider.api.AlbumListType
import app.omnisonic.provider.api.ConnectionBlock
import app.omnisonic.provider.api.ProviderAlbumRequest
import app.omnisonic.provider.api.ProviderAuthMethod
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderFatalException
import app.omnisonic.provider.api.ProviderHttpError
import app.omnisonic.provider.api.ProviderMediaType
import app.omnisonic.provider.api.ProviderTransportException
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import java.net.ServerSocket
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private const val TEST_PASSWORD = "hunter2"

private class RecordingFailover {
    private val observed = mutableListOf<Int>()

    fun note(block: Int) {
        observed += block
    }

    fun changes(): List<Int> = observed.toList()
}

private fun testConnection(
    primary: ConnectionBlock,
    secondary: ConnectionBlock? = null,
    authMethod: ProviderAuthMethod = ProviderAuthMethod.TOKEN,
    apiKey: String? = null,
): ProviderConnection = ProviderConnection(
    providerId = 1L,
    username = "admin",
    password = TEST_PASSWORD,
    primary = primary,
    secondary = secondary,
    authMethod = authMethod,
    apiKey = apiKey,
)

private fun testClient(
    connection: ProviderConnection,
    onPreferredBlockChange: (Int) -> Unit = {},
): SubsonicClient = SubsonicClient(
    connection,
    HttpClientFactory(OmnisonicLogger(ConsoleLogSink())).create(ConnectionSpec()),
    onPreferredBlockChange,
)

private fun pathDispatcher(responses: Map<String, String>): Dispatcher =
    object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            val endpoint = path.substringBefore("?").substringAfter("/rest/").substringBefore(".view")
            val body = responses[endpoint] ?: "{\"status\":\"ok\"}"
            return MockResponse().setBody(body)
        }
    }

private fun pendingPort(): Int = ServerSocket(0).use { socket -> socket.localPort }

class SubsonicClientTest {

    private fun md5hex(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `token auth query carries the digest and protocol line`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(emptyMap())
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            runBlocking { client.listArtists() }

            val request = server.takeRequest()
            val requested = request.requestUrl!!
            assertThat(requested.queryParameter("u")).isEqualTo("admin")
            val salt = requested.queryParameter("s")!!
            assertThat(salt).hasLength(12)
            assertThat(requested.queryParameter("t")).isEqualTo(md5hex(TEST_PASSWORD + salt))
            assertThat(requested.queryParameter("v")).isEqualTo("1.13.0")
            assertThat(requested.queryParameter("c")).isEqualTo("Omnisonic")
            assertThat(requested.queryParameter("f")).isEqualTo("json")
            assertThat(request.getHeader("Accept-Encoding")).isEqualTo("zstd, gzip")
        }
    }

    @Test
    fun `legacy password auth hex encodes the password`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(emptyMap())
            server.start()
            val client = testClient(
                testConnection(
                    ConnectionBlock("http", server.hostName, server.port),
                    authMethod = ProviderAuthMethod.LEGACY_PASSWORD,
                ),
            )

            runBlocking { client.listGenres() }

            val requested = server.takeRequest().requestUrl!!
            val expected = TEST_PASSWORD.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
            assertThat(requested.queryParameter("p")).isEqualTo("enc:$expected")
            assertThat(requested.queryParameter("t")).isNull()
            assertThat(requested.queryParameter("s")).isNull()
        }
    }

    @Test
    fun `api key auth uses the key parameter`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(emptyMap())
            server.start()
            val client = testClient(
                testConnection(
                    ConnectionBlock("http", server.hostName, server.port),
                    authMethod = ProviderAuthMethod.API_KEY,
                    apiKey = "navi-key-1",
                ),
            )

            runBlocking { client.listGenres() }

            val requested = server.takeRequest().requestUrl!!
            assertThat(requested.queryParameter("apiKey")).isEqualTo("navi-key-1")
            assertThat(requested.queryParameter("t")).isNull()
        }
    }

    @Test
    fun `listArtists flattens indexes and accepts a single object`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getArtists" to """
                    {"status":"ok","artists":{"index":[
                        {"name":"B","artist":[{"id":"ar-2","name":"Bytes","albumCount":2}]},
                        {"name":"A","artist":{"id":"ar-1","name":"Alpha","coverArt":"al-ca"}}]}}
                    """.trimIndent(),
                ),
            )
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val artists = runBlocking { client.listArtists() }

            assertThat(artists.map { it.externalId }).containsExactly("ar-2", "ar-1")
            assertThat(artists.first { it.externalId == "ar-1" }.imagePath).isEqualTo("al-ca")
            assertThat(artists.first { it.externalId == "ar-2" }.albumCount).isEqualTo(2)
        }
    }

    @Test
    fun `album details fall back to name and getSong works as a single object`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getAlbum" to """
                    {"status":"ok","album":{"id":"al-1","name":"Countdown","artist":"Steely Dan",
                     "artistId":"ar-1","year":1973,"songCount":2,"duration":191,"genre":"rock",
                     "song":[{"id":"song-1","title":"Bodhisattva","track":1,"path":"sd/b.flac","suffix":"flac"}]}}
                    """.trimIndent(),
                    "getSong" to """
                    {"status":"ok","song":{
                        "id":"song-1","title":"Bodhisattva","artist":"Steely Dan","artistId":"ar-1",
                        "album":"Countdown","albumId":"al-1","track":1,"discNumber":1,"year":1973,
                        "duration":191,"path":"sd/b.flac","suffix":"flac","bitRate":1000,"size":7000000,
                        "starred":"2024-05-21 08:00:00"}}
                    """.trimIndent(),
                ),
            )
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val album = runBlocking { client.albumDetails("al-1") }!!
            val song = runBlocking { client.songDetails("song-1") }!!

            assertThat(album.title).isEqualTo("Countdown")
            assertThat(album.artistExternalId).isEqualTo("ar-1")
            assertThat(album.songCount).isEqualTo(2)
            assertThat(song.title).isEqualTo("Bodhisattva")
            assertThat(song.discNo).isEqualTo(1)
            assertThat(song.codec).isEqualTo("flac")
            assertThat(song.bitrateKbps).isEqualTo(1000)
            assertThat(song.durationMs).isEqualTo(191_000L)
            assertThat(song.starredAtMs).isNotNull()
        }
    }

    @Test
    fun `directory children split albums and songs by isDir`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getMusicDirectory" to """
                    {"status":"ok","directory":{"id":"ar-1","child":[
                        {"id":"dir-1","title":"Lovers Rock","artist":"Sade","artistId":"ar-2","isDir":true,"coverArt":"c1"},
                        {"id":"song-1","title":"Smooth Operator","artist":"Sade","albumId":"dir-1","track":1,
                         "duration":260,"path":"sade/smooth.mp3","suffix":"mp3","bitRate":320,
                         "size":9331200,"discNumber":1,"year":1984,"genre":"soul"}]}}
                    """.trimIndent(),
                ),
            )
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val directory = runBlocking { client.browseDirectory("ar-1") }

            assertThat(directory.childAlbums.map { it.externalId }).containsExactly("dir-1")
            assertThat(directory.childAlbums.single().title).isEqualTo("Lovers Rock")
            val song = directory.childSongs.single()
            assertThat(song.discNo).isEqualTo(1)
            assertThat(song.bitrateKbps).isEqualTo(320)
            assertThat(song.durationMs).isEqualTo(260_000L)
            assertThat(song.genre).isEqualTo("soul")
        }
    }

    @Test
    fun `listAlbums issues typed paged requests`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getAlbumList2" to """
                    {"status":"ok","albumList2":{"album":{"id":"al-1","title":"Nik","year":1980,"songCount":9}}}
                    """.trimIndent(),
                ),
            )
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val albums = runBlocking {
                client.listAlbums(ProviderAlbumRequest(type = AlbumListType.NEWEST, size = 3, offset = 500))
            }

            val requested = server.takeRequest().requestUrl!!
            assertThat(requested.queryParameter("type")).isEqualTo("newest")
            assertThat(requested.queryParameter("size")).isEqualTo("3")
            assertThat(requested.queryParameter("offset")).isEqualTo("500")
            assertThat(albums.single().title).isEqualTo("Nik")
        }
    }

    @Test
    fun `spec-wrapped responses are unwrapped before decoding`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "ping" to "",
                    "getSong" to """
                    {"subsonic-response":{"status":"ok","song":{
                        "id":"song-1","title":"Bodhisattva","track":1,"discNumber":2}}}
                    """.trimIndent(),
                ),
            )
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val song = runBlocking { client.songDetails("song-1") }!!

            assertThat(song.title).isEqualTo("Bodhisattva")
            assertThat(song.discNo).isEqualTo(2)
        }
    }

    @Test
    fun `probe records advertised extensions and gates backdated scrobbles`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getOpenSubsonicExtensions" to """
                    {"status":"ok","openSubsonicExtensions":[
                        {"name":"formPost","versions":[1]},
                        {"name":"transcodeOffset","versions":[1]}]}
                    """.trimIndent(),
                ),
            )
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val capabilities = runBlocking { client.probe() }

            assertThat(capabilities.extensionNames).containsExactly("formPost", "transcodeOffset")
            assertThat(capabilities.supportsBackdatedScrobbles).isTrue()

            runBlocking { client.submitPlays("song-1", 2, lastPlayedAtMs = 1_700_000_000_000) }
            server.takeRequest()
            server.takeRequest()
            val first = server.takeRequest().requestUrl!!
            val second = server.takeRequest().requestUrl!!
            assertThat(first.encodedPath).endsWith("/rest/scrobble.view")
            assertThat(first.queryParameter("time")).isEqualTo("1700000000000")
            assertThat(second.queryParameter("time")).isEqualTo("1700000000000")
        }
    }

    @Test
    fun `scrobble submissions omit the timestamp on unprobed servers`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(emptyMap())
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val accepted = runBlocking { client.submitPlays("song-1", 3, lastPlayedAtMs = 1_700_000_000_000) }

            assertThat(accepted).isEqualTo(3)
            val first = server.takeRequest().requestUrl!!
            val second = server.takeRequest().requestUrl!!
            val third = server.takeRequest().requestUrl!!
            assertThat(first.queryParameter("time")).isNull()
            assertThat(second.queryParameter("time")).isNull()
            assertThat(third.queryParameter("time")).isNull()
            assertThat(first.queryParameter("submission")).isEqualTo("true")
        }
    }

    @Test
    fun `now playing marks submission false`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(emptyMap())
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val delivered = runBlocking { client.sendNowPlaying("song-1", playedMs = 45_000) }

            assertThat(delivered).isTrue()
            val requested = server.takeRequest().requestUrl!!
            assertThat(requested.queryParameter("submission")).isEqualTo("false")
            assertThat(requested.queryParameter("id")).isEqualTo("song-1")
        }
    }

    @Test
    fun `favorites use typed id parameters`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(emptyMap())
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            runBlocking {
                client.setFavorite(ProviderMediaType.ALBUM, "al-1", favorite = true)
                client.setFavorite(ProviderMediaType.ARTIST, "ar-1", favorite = false)
            }

            val star = server.takeRequest().requestUrl!!
            assertThat(star.encodedPath).endsWith("/rest/star.view")
            assertThat(star.queryParameter("albumId")).isEqualTo("al-1")
            val unstar = server.takeRequest().requestUrl!!
            assertThat(unstar.encodedPath).endsWith("/rest/unstar.view")
            assertThat(unstar.queryParameter("artistId")).isEqualTo("ar-1")
        }
    }

    @Test
    fun `failed status maps to a fatal error carrying the subsonic code`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getAlbumList2" to """{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}""",
                ),
            )
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val failure = assertThrows(ProviderFatalException::class.java) {
                runBlocking { client.listAlbums(ProviderAlbumRequest(type = AlbumListType.NEWEST)) }
            }
            assertThat(failure.cause).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure.cause!!.message).contains("code=40")
            assertThat(failure.cause!!.message).contains("Wrong username or password")
        }
    }

    @Test
    fun `http failures surface the code as ProviderHttpError`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(500).setBody("boom")
            }
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val failure = assertThrows(ProviderHttpError::class.java) {
                runBlocking { client.listGenres() }
            }
            assertThat(failure.code).isEqualTo(500)
        }
    }

    @Test
    fun `malformed response bodies surface as fatal`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setBody("<html>rendered</html>")
            }
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val failure = assertThrows(ProviderFatalException::class.java) {
                runBlocking { client.listGenres() }
            }
            val cause = failure.cause!!
            assertThat(cause is JsonDataException || cause is JsonEncodingException).isTrue()
        }
    }

    @Test
    fun `search decodes per-type payloads and encodes the query`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "search3" to """
                    {"status":"ok","searchResult3":{"song":[
                        {"id":"song-2","title":"Bodhisattva","path":"x/s.flac","suffix":"flac"}]}}
                    """.trimIndent(),
                ),
            )
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val songs = runBlocking { client.searchSongs(query = "blue oyster", count = 10, offset = 0) }

            val requested = server.takeRequest().requestUrl!!
            assertThat(requested.queryParameter("query")).isEqualTo("blue oyster")
            assertThat(requested.queryParameter("songCount")).isEqualTo("10")
            assertThat(requested.queryParameter("songOffset")).isEqualTo("0")
            assertThat(songs.single().title).isEqualTo("Bodhisattva")
        }
    }

    @Test
    fun `radios playlists and bookmarks map changed epochs`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(
                mapOf(
                    "getInternetRadioStations" to """
                    {"status":"ok","internetRadioStations":{"station":[
                        {"id":"radio-1","name":"Jazz 24","streamUrl":"http://j.example","homePageUrl":"http://j.example/home"}]}}
                    """.trimIndent(),
                    "getPlaylists" to """
                    {"status":"ok","playlists":{"playlist":[
                        {"id":"pl-1","name":"Fireside","songCount":3,"duration":610,"owner":"admin","changed":"2024-05-20T15:51:01.123Z"}]}}
                    """.trimIndent(),
                    "getBookmarks" to """
                    {"status":"ok","bookmarks":{"bookmark":[
                        {"entry":{"id":"song-1","title":"Trial"},"position":43210,"changed":"2024-05-21 08:00:00"}]}}
                    """.trimIndent(),
                ),
            )
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            val radios = runBlocking { client.listRadios() }
            val playlists = runBlocking { client.listPlaylists() }
            val bookmarks = runBlocking { client.listBookmarks() }

            assertThat(radios.single().streamUrl).isEqualTo("http://j.example")
            assertThat(radios.single().homePageUrl).isEqualTo("http://j.example/home")
            val playlist = playlists.single()
            assertThat(playlist.songCount).isEqualTo(3)
            assertThat(playlist.durationMs).isEqualTo(610_000L)
            assertThat(playlist.changedAtMs).isEqualTo(Instant.parse("2024-05-20T15:51:01.123Z").toEpochMilli())
            val bookmark = bookmarks.single()
            assertThat(bookmark.entryExternalId).isEqualTo("song-1")
            assertThat(bookmark.positionMs).isEqualTo(43_210)
            assertThat(bookmark.updatedAtMs).isEqualTo(Instant.parse("2024-05-21T08:00:00Z").toEpochMilli())
        }
    }

    @Test
    fun `transport refusal flips to the secondary block and reports preference once`() = runBlocking {
        val switchEvents = RecordingFailover()
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(emptyMap())
            server.start()
            val client = testClient(
                testConnection(
                    primary = ConnectionBlock("http", "127.0.0.1", pendingPort()),
                    secondary = ConnectionBlock("http", "127.0.0.1", server.port),
                ),
                onPreferredBlockChange = { switchEvents.note(it) },
            )

            val first = runBlocking { client.listArtists() }
            assertThat(first).isEmpty()
            val second = runBlocking { client.listGenres() }
            assertThat(second).isEmpty()

            assertThat(switchEvents.changes()).containsExactly(ProviderConnection.SECONDARY_BLOCK)
        }
    }

    @Test
    fun `stream cover and download urls carry auth and rate parameters`() {
        val client = testClient(testConnection(ConnectionBlock("https", "navidrome.local", 4_443)))

        val stream = client.streamUrl("song-9", maxBitRateKbps = 96, format = "mp3").toHttpUrl()
        assertThat(stream.encodedPath).isEqualTo("/rest/stream.view")
        assertThat(stream.queryParameter("id")).isEqualTo("song-9")
        assertThat(stream.queryParameter("maxBitRate")).isEqualTo("96")
        assertThat(stream.queryParameter("format")).isEqualTo("mp3")
        assertThat(stream.queryParameter("u")).isEqualTo("admin")
        assertThat(stream.queryParameter("t")).isEqualTo(md5hex(TEST_PASSWORD + stream.queryParameter("s")))

        val artUrl = client.coverArtUrl("ca-1", sizePx = 300)!!.toHttpUrl()
        assertThat(artUrl.encodedPath).isEqualTo("/rest/getCoverArt.view")
        assertThat(artUrl.queryParameter("size")).isEqualTo("300")

        assertThat(client.downloadUrl("song-9").toHttpUrl().encodedPath).isEqualTo("/rest/download.view")
        assertThat(client.coverArtUrl(null)).isNull()
    }

    @Test
    fun `rating set and bookmark actions hit the right endpoints`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = pathDispatcher(emptyMap())
            server.start()
            val client = testClient(testConnection(ConnectionBlock("http", server.hostName, server.port)))

            runBlocking {
                client.setUserRating(ProviderMediaType.SONG, "song-1", rating = 4)
                client.saveBookmark("song-1", positionMs = 25_000)
                client.deleteBookmark("song-1")
            }

            val rating = server.takeRequest().requestUrl!!
            assertThat(rating.encodedPath).endsWith("/rest/setRating.view")
            assertThat(rating.queryParameter("rating")).isEqualTo("4")
            val save = server.takeRequest().requestUrl!!
            assertThat(save.encodedPath).endsWith("/rest/createBookmark.view")
            assertThat(save.queryParameter("position")).isEqualTo("25000")
            val delete = server.takeRequest().requestUrl!!
            assertThat(delete.encodedPath).endsWith("/rest/deleteBookmark.view")
        }
    }
}
