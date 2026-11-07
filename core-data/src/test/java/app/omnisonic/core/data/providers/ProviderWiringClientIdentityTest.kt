package app.omnisonic.core.data.providers

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.silentTestLogger
import app.omnisonic.core.data.security.AesGcmCredentialCodec
import app.omnisonic.core.data.security.SecretKeySource
import app.omnisonic.core.network.ClientProfile
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.provider.api.ProviderRegistry
import app.omnisonic.provider.navidrome.SubsonicClient
import app.omnisonic.provider.navidrome.SubsonicProviderFactory
import com.google.common.truth.Truth.assertThat
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring-level proof that the registry owns one API-profile client per
 * provider id and that the provider rides that exact instance (real
 * [SubsonicProviderFactory] and the registry's default client factories —
 * the production path, no test-only factory standing in for it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderWiringClientIdentityTest : DatabaseTestBase() {

    private var builtClients = 0

    private class FixedKeySource(private val key: SecretKey) : SecretKeySource {
        override fun active(): SecretKey = key
    }

    private val codec = AesGcmCredentialCodec(
        keySource = FixedKeySource(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
    )

    private val mapper = ProviderConfigMapper(codec)

    private class RecordingApiClients {
        val specs = mutableListOf<ConnectionSpec>()
        val clients = mutableListOf<OkHttpClient>()

        fun build(spec: ConnectionSpec): OkHttpClient {
            specs += spec
            return OkHttpClient().also { clients += it }
        }
    }

    private fun wiringRegistry(
        apiClientFactory: ((ConnectionSpec) -> OkHttpClient)? = null,
    ): ProviderClientRegistry {
        val factory = SubsonicProviderFactory()
        return if (apiClientFactory == null) {
            ProviderClientRegistry(
                database = db,
                providerRegistry = ProviderRegistry(listOf(factory)),
                mapper = mapper,
                logger = silentTestLogger,
            )
        } else {
            ProviderClientRegistry(
                database = db,
                providerRegistry = ProviderRegistry(listOf(factory)),
                mapper = mapper,
                logger = silentTestLogger,
                apiClientFactory = apiClientFactory,
            )
        }
    }

    private suspend fun seedSubsonicProvider(
        id: Long,
        host: String,
        port: Int = 443,
        scheme: String = "https",
        headers: Map<String, String> = emptyMap(),
    ) {
        newProvider(id = id, name = "provider-$id").let { row ->
            providerDao.upsert(
                row.copy(
                    api = "subsonic",
                    primaryScheme = scheme,
                    primaryHost = host,
                    primaryPort = port,
                    primaryPath = null,
                    customHeadersJson = headersJson(headers),
                ),
            )
        }
    }

    private fun headersJson(headers: Map<String, String>): String? =
        if (headers.isEmpty()) {
            null
        } else {
            headers.entries.joinToString(prefix = "{", postfix = "}") { """"${it.key}":"${it.value}"""" }
        }

    @Test
    fun `provider rides the registry built api client instance`() = runDbTest {
        seedSubsonicProvider(id = 1, host = "home.example", headers = mapOf("X-Omnisonic-Wiring" to "one"))
        val registry = wiringRegistry()

        val apiClient = registry.apiClientFor(1)!!
        val provider = registry.providerFor(1)

        assertThat(provider).isInstanceOf(SubsonicClient::class.java)
        assertThat((provider as SubsonicClient).http).isSameInstanceAs(apiClient)
        assertThat(registry.apiClientFor(1)).isSameInstanceAs(apiClient)
        assertThat(registry.providerFor(1)).isSameInstanceAs(provider)
    }

    @Test
    fun `different provider ids receive independent clients built from their own rows`() = runDbTest {
        seedSubsonicProvider(id = 1, host = "one.example", headers = mapOf("X-Omnisonic-Wiring" to "one"))
        seedSubsonicProvider(id = 2, host = "two.example", headers = mapOf("X-Omnisonic-Wiring" to "two"))
        val recording = RecordingApiClients()
        val registry = wiringRegistry(apiClientFactory = recording::build)

        val clientOne = registry.apiClientFor(1)!!
        val clientTwo = registry.apiClientFor(2)!!
        val providerOne = registry.providerFor(1) as SubsonicClient
        val providerTwo = registry.providerFor(2) as SubsonicClient

        assertThat(clientOne).isNotSameInstanceAs(clientTwo)
        assertThat(providerOne.http).isSameInstanceAs(clientOne)
        assertThat(providerTwo.http).isSameInstanceAs(clientTwo)
        assertThat(recording.clients).containsExactly(clientOne, clientTwo).inOrder()
        assertThat(recording.specs.map { it.profile }).containsExactly(ClientProfile.API, ClientProfile.API)
        assertThat(recording.specs.map { it.headers["X-Omnisonic-Wiring"] })
            .containsExactly("one", "two")
            .inOrder()
    }

    @Test
    fun `the wired client carries the connection configuration to the server`() = runDbTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setBody("""{"status":"ok"}""")
            }
            server.start()
            seedSubsonicProvider(
                id = 1,
                host = server.hostName,
                port = server.port,
                scheme = "http",
                headers = mapOf("X-Omnisonic-Wiring" to "wired"),
            )
            val registry = wiringRegistry()
            val apiClient = registry.apiClientFor(1)!!
            val provider = registry.providerFor(1) as SubsonicClient

            assertThat(provider.http).isSameInstanceAs(apiClient)
            provider.probe()

            val ping = server.takeRequest()
            assertThat(ping.getHeader("X-Omnisonic-Wiring")).isEqualTo("wired")
            assertThat(ping.path).startsWith("/rest/ping.view")
        }
    }
}
