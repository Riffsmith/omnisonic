package app.omnisonic.core.network

import app.omnisonic.core.model.log.OmnisonicLogger
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.net.ssl.SSLSocket
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Test

private const val CLIENT_KEY_PASSWORD = "gate-test"

class TlsHandshakeTest {

    @Test
    fun pkcs12ClientIdentityCompletesMtlsHandshakeToServerRequiringCertificates() {
        val loopback = "127.0.0.1"
        val serverCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName(loopback)
            .build()
        val clientCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("omnisonic-client")
            .build()

        val clientKeyStoreBytes = pkcs12BytesFor(clientCertificate, CLIENT_KEY_PASSWORD.toCharArray())
        val client = HttpClientFactory(OmnisonicLogger(SilentSink)).create(
            ConnectionSpec(
                clientKeyStore = pkcs12KeyStore(clientKeyStoreBytes, CLIENT_KEY_PASSWORD),
                clientKeyStorePassword = CLIENT_KEY_PASSWORD,
                trustedCertificates = listOf(serverCertificate.certificate),
            ),
        )

        MockWebServer().use { server ->
            server.useHttps(
                demandingClientCertificates(serverHandshakeFor(serverCertificate, clientCertificate).sslSocketFactory()),
                false,
            )
            server.enqueue(MockResponse().setBody("mtls-ok"))
            server.start()

            val body = client.newCall(
                Request.Builder().url("https://127.0.0.1:${server.port}/rest/ping.view").build(),
            ).execute().use { it.body!!.string() }

            assertThat(body).isEqualTo("mtls-ok")
        }
    }

    private fun pkcs12BytesFor(certificate: HeldCertificate, password: CharArray): ByteArray {
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keyStore.setKeyEntry("omnisonic-test-client", certificate.keyPair.private, password, arrayOf(certificate.certificate))
        return ByteArrayOutputStream().use { output ->
            keyStore.store(output, password)
            output.toByteArray()
        }
    }

    private fun serverHandshakeFor(certificate: HeldCertificate, clientCertificate: HeldCertificate): HandshakeCertificates =
        HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .addTrustedCertificate(clientCertificate.certificate)
            .build()

    @Suppress("LargeClass")
    fun demandingClientCertificates(
        delegate: javax.net.ssl.SSLSocketFactory,
    ): javax.net.ssl.SSLSocketFactory = object : javax.net.ssl.SSLSocketFactory() {
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

    private object SilentSink : app.omnisonic.core.model.log.LogSink {
        override fun write(level: app.omnisonic.core.model.log.LogLevel, tag: String, message: String, error: Throwable?) = Unit
    }
}
