package app.omnisonic.core.data.providers

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.security.AesGcmCredentialCodec
import app.omnisonic.core.data.security.SecretKeySource
import app.omnisonic.core.data.security.SecretPayload
import app.omnisonic.provider.api.ProviderRegistry
import app.omnisonic.provider.navidrome.SubsonicProviderFactory
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.net.ssl.SSLSocket
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Full persistence -> mapper -> client registry -> real handshake proof for
 * mTLS, in the style of core-network's TlsHandshakeTest. One sealed provider
 * row feeds the mapper through the T12 registry; the API- and MEDIA-profile
 * clients built from it must both complete the handshake against a server
 * demanding client certificates (server verification stays certificate-based,
 * no insecure fallback).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MtlsEndToEndHandshakeTest : DatabaseTestBase() {

    private val testKey: SecretKey by lazy { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    private val codec by lazy { AesGcmCredentialCodec(keySource = SecretKeySource { testKey }) }
    private val mapper by lazy { ProviderConfigMapper(codec) }

    private val keyPassword = "handshake-test"
    private val loopback = "127.0.0.1"

    private val serverCertificate = HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName(loopback)
        .build()

    private val clientCertificate = HeldCertificate.Builder()
        .addSubjectAlternativeName("omnisonic-client")
        .build()

    private val keystoreBytes: ByteArray by lazy {
        val keystore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keystore.setKeyEntry(
            "omnisonic-client",
            clientCertificate.keyPair.private,
            keyPassword.toCharArray(),
            arrayOf(clientCertificate.certificate),
        )
        ByteArrayOutputStream().use { output ->
            keystore.store(output, keyPassword.toCharArray())
            output.toByteArray()
        }
    }

    private suspend fun seedMtlsProvider(id: Long) {
        val trustBlob = """["${Base64.getEncoder().encodeToString(serverCertificate.certificate.encoded)}"]"""
        providerDao.upsert(
            ProviderEntity(
                id = id,
                name = "mtls",
                api = "subsonic",
                primaryScheme = "https",
                primaryHost = loopback,
                primaryPort = 8_443,
                username = "admin",
                secretBlob = codec.seal(
                    SecretPayload(secretValues = mapOf("mtlsKeystorePassword" to keyPassword)),
                ),
                mtlsEnabled = true,
                mtlsKeystoreBlob = codec.sealBytes(keystoreBytes),
                mtlsAlias = "omnisonic-client",
                trustedCertsBlob = codec.sealBytes(trustBlob.toByteArray()),
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
    }

    private fun registry(): ProviderClientRegistry =
        ProviderClientRegistry(
            database = db,
            providerRegistry = ProviderRegistry(listOf(SubsonicProviderFactory())),
            mapper = mapper,
        )

    @Test
    fun enabledMtlsCompletesRealHandshakesThroughBothRegistryProfiles() = runDbTest {
        seedMtlsProvider(id = 1)
        val registry = registry()

        val mediaClient = registry.httpClientFor(1)!!
        val apiClient = registry.apiClientFor(1)!!

        MockWebServer().use { server ->
            server.useHttps(demandingClientCertificates(serverHandshake().sslSocketFactory()), false)
            server.enqueue(MockResponse().setBody("mtls-media"))
            server.enqueue(MockResponse().setBody("mtls-api"))
            server.start()

            val url = "https://127.0.0.1:${server.port}/rest/ping.view"
            assertThat(
                mediaClient.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() },
            ).isEqualTo("mtls-media")
            assertThat(
                apiClient.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() },
            ).isEqualTo("mtls-api")
        }
    }

    @Test
    fun disabledMtlsFailsTheHandshakeAgainstAClientCertDemandingServer() = runDbTest {
        seedMtlsProvider(id = 1)
        // Trust anchors stay; only the client identity is dropped, so the
        // failure below is the missing client certificate, not server trust.
        providerDao.upsert(providerDao.selectById(1)!!.copy(mtlsEnabled = false, updatedAt = 2_000))
        val registry = registry()

        val client = registry.apiClientFor(1)!!

        MockWebServer().use { server ->
            server.useHttps(demandingClientCertificates(serverHandshake().sslSocketFactory()), false)
            server.start()

            val failure = runCatching {
                client.newCall(
                    Request.Builder().url("https://127.0.0.1:${server.port}/rest/ping.view").build(),
                ).execute().use { it.body!!.string() }
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(javax.net.ssl.SSLHandshakeException::class.java)
        }
    }

    private fun serverHandshake(): HandshakeCertificates =
        HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .addTrustedCertificate(clientCertificate.certificate)
            .build()

    @Suppress("LargeClass")
    private fun demandingClientCertificates(delegate: javax.net.ssl.SSLSocketFactory): javax.net.ssl.SSLSocketFactory =
        object : javax.net.ssl.SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

            override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

            override fun createSocket(socket: java.net.Socket?, host: String?, port: Int, autoClose: Boolean): java.net.Socket =
                delegate.createSocket(socket, host, port, autoClose).also(::markClientCertRequired)

            override fun createSocket(host: String?, port: Int): java.net.Socket =
                delegate.createSocket(host, port).also(::markClientCertRequired)

            override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket =
                delegate.createSocket(host, port, localHost, localPort).also(::markClientCertRequired)

            override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket =
                delegate.createSocket(host, port).also(::markClientCertRequired)

            override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket =
                delegate.createSocket(address, port, localAddress, localPort).also(::markClientCertRequired)

            private fun markClientCertRequired(socket: java.net.Socket) {
                (socket as SSLSocket).needClientAuth = true
            }
        }
}
