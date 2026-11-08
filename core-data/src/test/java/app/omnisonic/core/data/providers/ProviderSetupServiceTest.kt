package app.omnisonic.core.data.providers

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.security.AesGcmCredentialCodec
import app.omnisonic.core.data.security.SecretKeySource
import app.omnisonic.core.data.silentTestLogger
import app.omnisonic.provider.api.ProviderRegistry
import app.omnisonic.provider.api.remoteEndpoint
import app.omnisonic.provider.local.LocalMediaProviderFactory
import app.omnisonic.provider.navidrome.SubsonicProviderFactory
import com.google.common.truth.Truth.assertThat
import java.net.ServerSocket
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val testKey: SecretKey by lazy {
    KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
}

private class RecordingSync : InitialLibrarySync {
    val started = mutableListOf<Long>()
    override fun start(providerId: Long) {
        started += providerId
    }
}

private fun pingOk(type: String = "navidrome"): String =
    """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"$type"}}"""

private fun dispatcher(vararg responses: String): Dispatcher = object : Dispatcher() {
    private val queue = responses.toMutableList()
    override fun dispatch(request: RecordedRequest): MockResponse {
        val body = queue.removeFirstOrNull() ?: "{\"subsonic-response\":{\"status\":\"ok\"}}"
        return MockResponse().setBody(body)
    }
}

private fun pendingPort(): Int = ServerSocket(0).use { socket -> socket.localPort }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderSetupServiceTest : DatabaseTestBase() {

    private val codec = AesGcmCredentialCodec(
        moshi = OmnisonicJson.moshi,
        keySource = SecretKeySource { testKey },
    )

    private val sync = RecordingSync()

    private fun clients(): ProviderClientRegistry = ProviderClientRegistry(
        database = db,
        providerRegistry = ProviderRegistry(
            listOf(SubsonicProviderFactory(), LocalMediaProviderFactory(appContext.contentResolver)),
        ),
        mapper = ProviderConfigMapper(codec, OmnisonicJson.moshi),
        logger = silentTestLogger,
    )

    private fun service(clients: ProviderClientRegistry = clients()): ProviderSetupService =
        ProviderSetupService(db, codec, clients, sync, silentTestLogger)

    private fun form(
        url: String,
        secondary: String = "",
        username: String = "admin",
        password: String = "hunter2",
    ) = ProviderSetupForm(
        serverUrl = url,
        username = username,
        password = password,
        secondaryUrl = secondary,
    )

    @Test
    fun `save seals the password and round-trips through the config mapper`() = runDbTest {
        val outcome = service().save(form("http://localhost:4533"))

        assertThat(outcome).isInstanceOf(ProviderSetupOutcome.Saved::class.java)

        val row = providerDao.selectAll().single()
        assertThat(row.name).isEqualTo("localhost")
        assertThat(row.api).isEqualTo(ProviderSetupService.API_SUBSONIC)
        assertThat(row.secretBlob).isNotNull()
        assertThat(row.secretBlob).doesNotContain("hunter2")
        assertThat(codec.unseal(row.secretBlob)?.secretValues?.get(ProviderConfigMapper.SECRET_PASSWORD))
            .isEqualTo("hunter2")

        val loaded = ProviderConfigMapper(codec, OmnisonicJson.moshi).from(row)
        assertThat(loaded).isInstanceOf(ProviderLoadResult.Loaded::class.java)
        val connection = (loaded as ProviderLoadResult.Loaded).connection
        assertThat(connection.username).isEqualTo("admin")
        assertThat(connection.password).isEqualTo("hunter2")
        assertThat(connection.authMethod).isEqualTo(app.omnisonic.provider.api.ProviderAuthMethod.TOKEN)
        assertThat(connection.remoteEndpoint!!.primary.host).isEqualTo("localhost")
    }

    @Test
    fun `save writes the optional secondary block and enables failover`() = runDbTest {
        service().save(form("https://music.example.com", secondary = "https://backup.example.com:8443/prefix"))

        val row = providerDao.selectAll().single()
        assertThat(row.useSecondary).isTrue()
        assertThat(row.secondaryScheme).isEqualTo("https")
        assertThat(row.secondaryHost).isEqualTo("backup.example.com")
        assertThat(row.secondaryPort).isEqualTo(8443)
        assertThat(row.secondaryPath).isEqualTo("prefix")
        assertThat(row.displayOrder).isEqualTo(0)
    }

    @Test
    fun `save without a secondary URL leaves the failover block empty`() = runDbTest {
        service().save(form("https://music.example.com"))

        val row = providerDao.selectAll().single()
        assertThat(row.useSecondary).isFalse()
        assertThat(row.secondaryHost).isNull()
    }

    @Test
    fun `save rejects an invalid form without writing a row`() = runDbTest {
        val outcome = service().save(form("music.example.com", username = ""))

        assertThat(outcome).isInstanceOf(ProviderSetupOutcome.Invalid::class.java)
        assertThat((outcome as ProviderSetupOutcome.Invalid).issues)
            .containsAtLeast(
                ProviderSetupIssue.SERVER_URL_INVALID,
                ProviderSetupIssue.USERNAME_REQUIRED,
            )
        assertThat(providerDao.count()).isEqualTo(0)
    }

    @Test
    fun `saving twice appends a second provider with the next display order`() = runDbTest {
        val service = service()
        service.save(form("https://one.example.com"))
        service.save(form("https://two.example.com"))

        val rows = providerDao.selectAll()
        assertThat(rows.map { it.name }).containsExactly("one.example.com", "two.example.com").inOrder()
        assertThat(rows.map { it.displayOrder }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `startInitialSync delegates to the initial walk trigger`() = runDbTest {
        val service = service()
        val id = (service.save(form("https://music.example.com")) as ProviderSetupOutcome.Saved).providerId

        service.startInitialSync(id)

        assertThat(sync.started).containsExactly(id)
    }

    @Test
    fun `probe reports success and the server software hint`() = runDbTest {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(
                pingOk(),
                """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","openSubsonic":true,"openSubsonicExtensions":[]}}""",
            )
            server.start()

            val result = service().probe(form("http://${server.hostName}:${server.port}"))

            assertThat(result).isInstanceOf(ProviderProbeResult.Success::class.java)
            assertThat((result as ProviderProbeResult.Success).capabilities.serverSoftwareHint).isEqualTo("navidrome")
        }
    }

    @Test
    fun `probe maps a rejected login to an authentication failure`() = runDbTest {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(
                """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""",
            )
            server.start()

            val result = service().probe(form("http://${server.hostName}:${server.port}"))

            assertThat((result as ProviderProbeResult.Failure).reason)
                .isEqualTo(ProviderProbeResult.FailureReason.AUTHENTICATION)
        }
    }

    @Test
    fun `probe maps a server error status to a server failure`() = runDbTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(500)
            }
            server.start()

            val result = service().probe(form("http://${server.hostName}:${server.port}"))

            assertThat((result as ProviderProbeResult.Failure).reason)
                .isEqualTo(ProviderProbeResult.FailureReason.SERVER_ERROR)
            assertThat(result.httpCode).isEqualTo(500)
        }
    }

    @Test
    fun `probe maps an unreachable server to a network failure`() = runDbTest {
        val port = pendingPort()

        val result = service().probe(form("http://127.0.0.1:$port"))

        assertThat((result as ProviderProbeResult.Failure).reason)
            .isEqualTo(ProviderProbeResult.FailureReason.NETWORK)
    }

    @Test
    fun `probe reports an invalid form before any network call`() = runDbTest {
        val result = service().probe(form("not a url"))

        assertThat((result as ProviderProbeResult.Failure).reason)
            .isEqualTo(ProviderProbeResult.FailureReason.INVALID_INPUT)
    }

    @Test
    fun `probe on a registry without a factory reports unsupported`() = runDbTest {
        val clients = ProviderClientRegistry(
            database = db,
            providerRegistry = ProviderRegistry(listOf(LocalMediaProviderFactory(appContext.contentResolver))),
            mapper = ProviderConfigMapper(codec, OmnisonicJson.moshi),
            logger = silentTestLogger,
        )

        val result = service(clients).probe(form("https://music.example.com"))

        assertThat((result as ProviderProbeResult.Failure).reason)
            .isEqualTo(ProviderProbeResult.FailureReason.UNSUPPORTED)
    }
}
