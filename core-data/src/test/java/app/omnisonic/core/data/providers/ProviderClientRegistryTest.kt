package app.omnisonic.core.data.providers

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.dao.ProviderDao
import app.omnisonic.provider.api.ConnectionBlock
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.MediaProviderFactory
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class CountingProviderFactory(
    private val onPreferredBlockChange: suspend (Long, Int) -> Unit,
) : MediaProviderFactory {
    val createdProviders = mutableListOf<Long>()
    val capturedConnections = mutableListOf<ProviderConnection>()
    var latestBlockChangeCallback: (suspend (Int) -> Unit)? = null

    override fun apiType(): String = "counting"

    override fun create(connection: ProviderConnection): MediaProvider {
        createdProviders += connection.providerId
        capturedConnections += connection
        val callback: suspend (Int) -> Unit = { block -> onPreferredBlockChange(connection.providerId, block) }
        latestBlockChangeCallback = callback
        return StubMediaProvider
    }
}

private object StubMediaProvider : MediaProvider {
    override suspend fun probe(): app.omnisonic.provider.api.ProviderCapabilities =
        app.omnisonic.provider.api.ProviderCapabilities()

    override suspend fun listArtists() = emptyList<app.omnisonic.provider.api.ProviderArtist>()

    override suspend fun browseDirectory(externalId: String) =
        app.omnisonic.provider.api.ProviderDirectory(externalId)

    override suspend fun getAlbum(albumExternalId: String) = null

    override suspend fun songDetails(externalId: String) = null

    override suspend fun listAlbums(request: app.omnisonic.provider.api.ProviderAlbumRequest) =
        emptyList<app.omnisonic.provider.api.ProviderAlbum>()

    override suspend fun listGenres() = emptyList<app.omnisonic.provider.api.ProviderGenre>()

    override suspend fun listRadios() = emptyList<app.omnisonic.provider.api.ProviderRadio>()

    override suspend fun listPlaylists() = emptyList<app.omnisonic.provider.api.ProviderPlaylistSummary>()

    override suspend fun getPlaylist(playlistExternalId: String) = null

    override suspend fun listBookmarks() = emptyList<app.omnisonic.provider.api.ProviderBookmark>()

    override suspend fun searchArtists(query: String, offset: Int, count: Int) =
        emptyList<app.omnisonic.provider.api.ProviderArtist>()

    override suspend fun searchAlbums(query: String, offset: Int, count: Int) =
        emptyList<app.omnisonic.provider.api.ProviderAlbum>()

    override suspend fun searchSongs(query: String, offset: Int, count: Int) =
        emptyList<app.omnisonic.provider.api.ProviderSong>()

    override fun streamUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? = null

    override fun coverArtUrl(imageId: String?, sizePx: Int?): String? = null

    override fun downloadUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? = null

    override suspend fun submitPlays(externalId: String, timestamps: List<Long>): Int = timestamps.size

    override suspend fun sendNowPlaying(externalId: String, playedMs: Long): Boolean = true

    override suspend fun setFavorite(
        mediaType: app.omnisonic.provider.api.ProviderMediaType,
        externalId: String,
        favorite: Boolean,
    ): Boolean = true

    override suspend fun setUserRating(
        mediaType: app.omnisonic.provider.api.ProviderMediaType,
        externalId: String,
        rating: Int,
    ): Boolean = true

    override suspend fun saveBookmark(externalId: String, positionMs: Long): Boolean = true

    override suspend fun deleteBookmark(externalId: String): Boolean = true

    override suspend fun similarSongs(externalId: String, count: Int) =
        emptyList<app.omnisonic.provider.api.ProviderSong>()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderClientRegistryTest : DatabaseTestBase() {

    private var builtClients = 0

    private class FixedKeySource(private val key: javax.crypto.SecretKey) : app.omnisonic.core.data.security.SecretKeySource {
        override fun active(): javax.crypto.SecretKey = key
    }

    private val codec = app.omnisonic.core.data.security.AesGcmCredentialCodec(
        keySource = FixedKeySource(javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
    )

    private fun registry(db: OmnisonicDatabase, providerDao: ProviderDao): Pair<ProviderClientRegistry, CountingProviderFactory> {
        val factory = CountingProviderFactory { providerId, block ->
            providerDao.setLastWorkingBlock(providerId, block)
        }
        val clientRegistry = ProviderClientRegistry(
            database = db,
            providerRegistry = ProviderRegistry(listOf(factory)),
            mapper = ProviderConfigMapper(codec),
            mediaClientFactory = { builtClients += 1; OkHttpClient() },
        )
        return clientRegistry to factory
    }

    @Test
    fun `one provider and one http client per provider id across calls`() = runDbTest {
        newProvider(id = 1, name = "a").let { row -> providerDao.upsert(row.copy(api = "counting")); providerDao.selectById(1)!! }
        val (registry, factory) = registry(db, providerDao)

        val first = registry.providerFor(1)
        val second = registry.providerFor(1)
        runBlocking { registry.httpClientFor(1) }

        assertThat(first).isSameInstanceAs(second)
        assertThat(factory.createdProviders).containsExactly(1L)
        assertThat(builtClients).isEqualTo(1)
    }

    @Test
    fun `learned block preference is persisted through the dao`() = runDbTest {
        newProvider(id = 1, name = "a").let { row -> providerDao.upsert(row.copy(api = "counting")); providerDao.selectById(1)!! }
        val (registry, factory) = registry(db, providerDao)
        registry.providerFor(1)

        factory.latestBlockChangeCallback!!.invoke(ProviderConnection.SECONDARY_BLOCK)

        assertThat(providerDao.selectById(1)!!.lastWorkingBlock).isEqualTo(ProviderConnection.SECONDARY_BLOCK)
    }

    @Test
    fun `invalidate rebuilds the client`() = runDbTest {
        newProvider(id = 1, name = "a").let { row -> providerDao.upsert(row.copy(api = "counting")); providerDao.selectById(1)!! }
        val (registry, factory) = registry(db, providerDao)
        registry.providerFor(1)

        registry.invalidate(1)
        registry.providerFor(1)

        assertThat(factory.createdProviders).containsExactly(1L, 1L).inOrder()
        assertThat(builtClients).isEqualTo(2)
    }

    @Test
    fun `edited provider rows rebuild the client`() = runDbTest {
        newProvider(id = 1, name = "a").let { row -> providerDao.upsert(row.copy(api = "counting")); providerDao.selectById(1)!! }
        val (registry, factory) = registry(db, providerDao)
        registry.providerFor(1)

        val row = providerDao.selectById(1)!!
        providerDao.upsert(row.copy(name = "renamed", updatedAt = row.updatedAt + 1))
        registry.providerFor(1)

        assertThat(factory.createdProviders).hasSize(2)
        assertThat(factory.capturedConnections.last().displayName).isEqualTo("renamed")
    }

    @Test
    fun `deleted providers drop out of the registry`() = runDbTest {
        newProvider(id = 1, name = "a").let { row -> providerDao.upsert(row.copy(api = "counting")); providerDao.selectById(1)!! }
        val (registry, _) = registry(db, providerDao)
        registry.providerFor(1)

        providerDao.deleteById(1)

        assertThat(registry.providerFor(1)).isNull()
        assertThat(registry.httpClientFor(1)).isNull()
    }

    @Test
    fun `unknown api types resolve to null without caching`() = runDbTest {
        val row = newProvider(id = 1, name = "a").let { seeded -> providerDao.upsert(seeded.copy(api = "counting")); providerDao.selectById(1)!! }
        providerDao.upsert(row.copy(api = "no-such-api", updatedAt = row.updatedAt + 1))
        val (registry, factory) = registry(db, providerDao)

        assertThat(registry.providerFor(1)).isNull()
        assertThat(registry.httpClientFor(1)).isNull()
        assertThat(factory.createdProviders).isEmpty()
    }
}
