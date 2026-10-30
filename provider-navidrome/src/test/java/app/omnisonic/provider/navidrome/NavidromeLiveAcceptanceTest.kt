package app.omnisonic.provider.navidrome

import app.omnisonic.core.model.log.ConsoleLogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.core.network.HttpClientFactory
import app.omnisonic.provider.api.AlbumListType
import app.omnisonic.provider.api.ConnectionBlock
import app.omnisonic.provider.api.ProviderAlbum
import app.omnisonic.provider.api.ProviderAlbumRequest
import app.omnisonic.provider.api.ProviderAuthMethod
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderMediaType
import com.google.common.truth.Truth.assertThat
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

private fun pendingPort(): Int = ServerSocket(0).use { socket -> socket.localPort }

private fun httpBlock(url: HttpUrl): ConnectionBlock =
    ConnectionBlock(url.scheme, url.host, url.port, url.encodedPath?.takeIf { it.isNotBlank() && it != "/" })

private class NavidromeLiveConfig(
    val connection: ProviderConnection,
    val block: ConnectionBlock,
)

private fun liveNavidromeOrSkip(): NavidromeLiveConfig {
    val rawUrl = System.getenv("OMNISONIC_NAVIDROME_URL")
    val username = System.getenv("OMNISONIC_NAVIDROME_USER")
    val password = System.getenv("OMNISONIC_NAVIDROME_PASS")
    assumeTrue(
        !rawUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank(),
        "skipped: OMNISONIC_NAVIDROME_URL / USER / PASS are not set in this environment",
    )
    val url = rawUrl!!.toHttpUrl()
    val block = httpBlock(url)
    return NavidromeLiveConfig(
        ProviderConnection(
            providerId = 1L,
            username = username!!,
            password = password!!,
            primary = block,
            secondary = null,
            authMethod = ProviderAuthMethod.TOKEN,
        ),
        block,
    )
}

private suspend fun firstSongIdMatching(
    client: SubsonicClient,
    predicate: suspend (String) -> Boolean,
): String? {
    val artists = client.listArtists()
    for (artist in artists.take(5)) {
        for (album in client.browseDirectory(artist.externalId).childAlbums.take(3)) {
            for (song in client.browseDirectory(album.externalId).childSongs) {
                if (predicate(song.externalId)) return song.externalId
            }
        }
    }
    return null
}

class NavidromeLiveAcceptanceTest {

    private fun testClient(
        connection: ProviderConnection,
        onPreferredBlockChange: (Int) -> Unit = {},
    ): SubsonicClient = SubsonicClient(
        connection,
        HttpClientFactory(OmnisonicLogger(ConsoleLogSink())).create(ConnectionSpec()),
        onPreferredBlockChange,
    )

    @Test
    fun `live probe and walk counts`() = runBlocking {
        val config = liveNavidromeOrSkip()
        val client = testClient(config.connection)

        val capabilities = client.probe()
        println("live: extensions=${capabilities.extensionNames} software=${capabilities.serverSoftwareHint}")

        val artists = client.listArtists()
        val genres = client.listGenres()
        val radios = client.listRadios()
        val playlists = client.listPlaylists()
        val bookmarks = client.listBookmarks()
        val albums = mutableListOf<ProviderAlbum>()
        val pageSize = 500
        val maxPages = 40
        var offset = 0
        var capped = false
        while (true) {
            val batch = client.listAlbums(
                ProviderAlbumRequest(AlbumListType.ALPHABETICAL_BY_NAME, pageSize, offset),
            )
            albums += batch
            if (batch.size < pageSize) break
            if (albums.size >= maxPages * pageSize) {
                capped = true
                break
            }
            offset += batch.size
        }
        println(
            "live counts: artists=${artists.size} albums=${albums.size} genres=${genres.size}" +
                " radios=${radios.size} playlists=${playlists.size} bookmarks=${bookmarks.size}" +
                if (capped) " (album walk capped)" else "",
        )
    }

    @Test
    fun `live failover completes through secondary when primary is refused`() = runBlocking {
        val config = liveNavidromeOrSkip()
        val dead = ConnectionBlock("http", "127.0.0.1", pendingPort())
        val connection = config.connection.copy(primary = dead, secondary = config.block)
        val preferredBlocks = mutableListOf<Int>()
        val client = testClient(connection) { block -> preferredBlocks += block }

        val artists = client.listArtists()
        println("live failover: artists=${artists.size} preferredBlocks=$preferredBlocks")
        assumeTrue(artists.isNotEmpty(), "skipped: empty library, nothing to list through the secondary block")
        assertTrue(
            preferredBlocks.contains(ProviderConnection.SECONDARY_BLOCK),
            "expected a preferred-block change to the secondary after the dead primary refused",
        )
    }

    @Test
    fun `live favorite toggle restores initial starred state`() = runBlocking {
        val config = liveNavidromeOrSkip()
        val client = testClient(config.connection)
        val songId = firstSongIdMatching(client) { externalId ->
            client.songDetails(externalId)?.starredAtMs == null
        }
        assumeTrue(!songId.isNullOrBlank(), "skipped: no unstarred song in the first artists/albums")
        val initial = client.songDetails(songId!!)!!.starredAtMs
        assumeTrue(initial == null, "skipped: picked song was starred between listing and detail read")

        assertThat(client.setFavorite(ProviderMediaType.SONG, songId, true)).isTrue()
        assertThat(client.songDetails(songId)!!.starredAtMs).isNotNull()
        assertThat(client.setFavorite(ProviderMediaType.SONG, songId, false)).isTrue()
        assertThat(client.songDetails(songId)!!.starredAtMs).isNull()
    }

    @Test
    fun `live bookmark create-delete is net zero`() = runBlocking {
        val config = liveNavidromeOrSkip()
        val client = testClient(config.connection)
        val alreadyBookmarked = client.listBookmarks().map { it.entryExternalId }.toSet()
        val songId = firstSongIdMatching(client) { externalId -> externalId !in alreadyBookmarked }
        assumeTrue(!songId.isNullOrBlank(), "skipped: every probed song already has a bookmark")

        assertThat(client.saveBookmark(songId!!, 1234L)).isTrue()
        assertThat(
            client.listBookmarks().any { bookmark ->
                bookmark.entryExternalId == songId && bookmark.positionMs == 1234L
            },
        ).isTrue()
        assertThat(client.deleteBookmark(songId)).isTrue()
        assertThat(
            client.listBookmarks().none { bookmark -> bookmark.entryExternalId == songId },
        ).isTrue()
    }
}
