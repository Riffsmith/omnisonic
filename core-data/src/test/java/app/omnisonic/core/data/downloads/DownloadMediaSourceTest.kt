package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.providers.ProviderClientRegistry
import app.omnisonic.core.data.providers.ProviderConfigMapper
import app.omnisonic.core.data.security.AesGcmCredentialCodec
import app.omnisonic.core.data.security.SecretKeySource
import app.omnisonic.core.data.silentTestLogger
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.MediaProviderFactory
import app.omnisonic.provider.api.ProviderAuthException
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderException
import app.omnisonic.provider.api.ProviderRegistry
import app.omnisonic.provider.api.ProviderSong
import app.omnisonic.provider.api.ProviderTraits
import app.omnisonic.provider.api.ProviderTransportException
import app.omnisonic.provider.navidrome.SubsonicProviderFactory
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import javax.crypto.KeyGenerator
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadMediaSourceTest : DatabaseTestBase() {

    private val mediaClient = OkHttpClient()
    private val codec = AesGcmCredentialCodec(
        moshi = OmnisonicJson.moshi,
        keySource = SecretKeySource {
            KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        },
    )

    private class FakeFactory(
        private val fallbackPath: String?,
        private val failure: ProviderException? = null,
        private val endpointList: List<String>? = null,
        private val traits: ProviderTraits = ProviderTraits(),
    ) : MediaProviderFactory {
        var detailsCalls = 0

        override fun apiType(): String = "api-test"

        override fun traits(): ProviderTraits = traits

        override fun create(connection: ProviderConnection, apiClient: OkHttpClient): MediaProvider {
            val delegate = SubsonicProviderFactory().create(connection, apiClient)!!
            return object : MediaProvider by delegate {
                override suspend fun songDetails(externalId: String): ProviderSong? {
                    detailsCalls++
                    failure?.let { throw it }
                    return fallbackPath?.let { ProviderSong(externalId, "Fallback", filePath = it) }
                }

                override fun downloadUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String =
                    "https://media.example/download?id=$externalId"

                override fun downloadEndpoints(
                    externalId: String,
                    maxBitRateKbps: Int?,
                    format: String?,
                ): List<String> = endpointList.orEmpty()
            }
        }
    }

    private fun source(factory: FakeFactory): ProviderMediaSource {
        val registry = ProviderRegistry(listOf(factory))
        val clients = ProviderClientRegistry(
            database = db,
            providerRegistry = registry,
            mapper = ProviderConfigMapper(codec, OmnisonicJson.moshi),
            logger = silentTestLogger,
            mediaClientFactory = { mediaClient },
        )
        return ProviderMediaSource(db, clients, registry)
    }

    @Test
    fun `the song snapshot wins and endpoints use the registry media client`() = runDbTest {
        newProvider(id = 1)
        newSong(id = 7, providerId = 1, filePath = "/snapshot/track.flac")
        val factory = FakeFactory(
            fallbackPath = "/fallback/track.flac",
            endpointList = listOf("https://media.example/one", "https://media.example/two"),
        )

        val resolved = source(factory).resolve(DownloadMediaRequest(1, "song-7", 320, "mp3"))

        val available = resolved as DownloadMediaResolution.Available
        assertThat(available.providerPath).isEqualTo("/snapshot/track.flac")
        assertThat(available.endpoints.map { it.url })
            .containsExactly("https://media.example/one", "https://media.example/two")
            .inOrder()
        assertThat(available.endpoints.map { it.client }).containsExactly(mediaClient, mediaClient)
        assertThat(factory.detailsCalls).isEqualTo(0)
    }

    @Test
    fun `an unsynced song uses fresh details and the single-url fallback`() = runDbTest {
        newProvider(id = 1)
        val factory = FakeFactory(fallbackPath = "/fallback/unsynced.flac")

        val resolved = source(factory).resolve(DownloadMediaRequest(1, "unsynced", null, null))

        val available = resolved as DownloadMediaResolution.Available
        assertThat(available.providerPath).isEqualTo("/fallback/unsynced.flac")
        assertThat(available.endpoints.map { it.url })
            .containsExactly("https://media.example/download?id=unsynced")
        assertThat(factory.detailsCalls).isEqualTo(1)
    }

    @Test
    fun `unsynced authentication failure stays distinguishable from a missing path`() = runDbTest {
        newProvider(id = 1)
        val factory = FakeFactory(null, ProviderAuthException())

        assertThat(source(factory).resolve(DownloadMediaRequest(1, "unsynced", null, null)))
            .isEqualTo(DownloadMediaResolution.CredentialsUnavailable)
    }

    @Test
    fun `unsynced transport failure is not a permanent missing path`() = runDbTest {
        newProvider(id = 1)
        val factory = FakeFactory(null, ProviderTransportException(IOException("offline")))

        assertThat(source(factory).resolve(DownloadMediaRequest(1, "unsynced", null, null)))
            .isEqualTo(DownloadMediaResolution.MediaPathTransport)
    }

    @Test
    fun `unsupported capability is rejected before a provider client is built`() = runDbTest {
        newProvider(id = 1)
        val factory = FakeFactory(null, traits = ProviderTraits(supportsDownload = false))

        assertThat(source(factory).resolve(DownloadMediaRequest(1, "song-1", null, null)))
            .isEqualTo(DownloadMediaResolution.DownloadUnsupported)
        assertThat(factory.detailsCalls).isEqualTo(0)
    }
}
