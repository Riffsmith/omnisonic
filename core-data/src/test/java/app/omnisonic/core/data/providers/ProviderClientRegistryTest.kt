package app.omnisonic.core.data.providers

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.dao.ProviderDao
import app.omnisonic.core.network.ClientProfile
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.provider.api.ConnectionBlock
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.MediaProviderFactory
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderRegistry
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val CLIENT_KEY_PASSWORD = "registry-test"

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

    private class RecordingClientFactory {
        val specs = mutableListOf<ConnectionSpec>()

        fun build(spec: ConnectionSpec): OkHttpClient {
            specs += spec
            return OkHttpClient()
        }
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

    @Test
    fun `mtls disabled builds both profiles without client identities`() = runDbTest {
        newProvider(id = 1, name = "a").let { row -> providerDao.upsert(row.copy(api = "counting")); providerDao.selectById(1)!! }
        val media = RecordingClientFactory()
        val api = RecordingClientFactory()
        val registry = ProviderClientRegistry(
            database = db,
            providerRegistry = ProviderRegistry(listOf(CountingProviderFactory { _, _ -> })),
            mapper = ProviderConfigMapper(codec),
            mediaClientFactory = media::build,
            apiClientFactory = api::build,
        )

        registry.providerFor(1)
        registry.httpClientFor(1)
        registry.apiClientFor(1)

        assertThat(media.specs).hasSize(1)
        assertThat(api.specs).hasSize(1)
        listOf(media.specs.single(), api.specs.single()).forEach { spec ->
            assertThat(spec.clientKeyStore).isNull()
            assertThat(spec.clientKeyStorePassword).isNull()
            assertThat(spec.trustedCertificates).isEmpty()
        }
        assertThat(media.specs.single().profile).isEqualTo(ClientProfile.MEDIA)
        assertThat(api.specs.single().profile).isEqualTo(ClientProfile.API)
    }

    @Test
    fun `mtls enabled propagates the client identity into both profiles`() = runDbTest {
        val keystoreBytes = pkcs12Bytes()
        newProvider(id = 1, name = "a").let { seeded ->
            providerDao.upsert(
                seeded.copy(
                    api = "counting",
                    mtlsEnabled = true,
                    mtlsKeystoreBlob = codec.sealBytes(keystoreBytes),
                    mtlsAlias = "omnisonic-client",
                    secretBlob = codec.seal(
                        app.omnisonic.core.data.security.SecretPayload(
                            secretValues = mapOf("mtlsKeystorePassword" to CLIENT_KEY_PASSWORD),
                        ),
                    ),
                    trustedCertsBlob = codec.sealBytes("""["${base64Der()}"]""".toByteArray()),
                ),
            )
        }
        val media = RecordingClientFactory()
        val api = RecordingClientFactory()
        val registry = ProviderClientRegistry(
            database = db,
            providerRegistry = ProviderRegistry(listOf(CountingProviderFactory { _, _ -> })),
            mapper = ProviderConfigMapper(codec),
            mediaClientFactory = media::build,
            apiClientFactory = api::build,
        )

        registry.providerFor(1)
        registry.httpClientFor(1)
        registry.apiClientFor(1)

        listOf(media.specs.single(), api.specs.single()).forEach { spec ->
            assertThat(spec.clientKeyStore).isNotNull()
            assertThat(spec.clientKeyStorePassword).isEqualTo(CLIENT_KEY_PASSWORD)
            assertThat(spec.trustedCertificates).hasSize(1)
        }
    }

    @Test
    fun `corrupt mtls keystore leaves the provider unloadable`() = runDbTest {
        newProvider(id = 1, name = "a").let { seeded ->
            providerDao.upsert(
                seeded.copy(
                    api = "counting",
                    mtlsEnabled = true,
                    mtlsKeystoreBlob = codec.sealBytes("not a keystore".toByteArray()),
                ),
            )
        }
        val registry = ProviderClientRegistry(
            database = db,
            providerRegistry = ProviderRegistry(listOf(CountingProviderFactory { _, _ -> })),
            mapper = ProviderConfigMapper(codec),
            mediaClientFactory = { OkHttpClient() },
            apiClientFactory = { OkHttpClient() },
        )

        assertThat(registry.providerFor(1)).isNull()
        assertThat(registry.httpClientFor(1)).isNull()
    }

    @Test
    fun `unreadable credentials leave the provider unserved`() = runDbTest {
        newProvider(id = 1, name = "a").let { seeded ->
            providerDao.upsert(seeded.copy(api = "counting", secretBlob = "not-a-sealed-blob"))
        }
        val registry = ProviderClientRegistry(
            database = db,
            providerRegistry = ProviderRegistry(listOf(CountingProviderFactory { _, _ -> })),
            mapper = ProviderConfigMapper(codec),
            mediaClientFactory = { OkHttpClient() },
            apiClientFactory = { OkHttpClient() },
        )

        assertThat(registry.providerFor(1)).isNull()
        assertThat(registry.httpClientFor(1)).isNull()
    }

    private fun pkcs12Bytes(): ByteArray {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("omnisonic-client")
            .build()
        val keystore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keystore.setKeyEntry(
            "omnisonic-client",
            certificate.keyPair.private,
            CLIENT_KEY_PASSWORD.toCharArray(),
            arrayOf(certificate.certificate),
        )
        return ByteArrayOutputStream().use { output ->
            keystore.store(output, CLIENT_KEY_PASSWORD.toCharArray())
            output.toByteArray()
        }
    }

    private fun base64Der(): String {
        val certificate = HeldCertificate.Builder().build()
        return java.util.Base64.getEncoder().encodeToString(certificate.certificate.encoded)
    }
}
