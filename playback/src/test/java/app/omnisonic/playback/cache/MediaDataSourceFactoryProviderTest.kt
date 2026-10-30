package app.omnisonic.playback.cache

import android.net.Uri
import androidx.media3.datasource.DataSpec
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.network.ClientProfile
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.core.network.HttpClientFactory
import app.omnisonic.core.network.pkcs12KeyStore
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.net.ssl.SSLSocket
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaDataSourceFactoryProviderTest {

    @Test
    fun `provider media client completes mtls through OkHttp data source`() {
        val loopback = "127.0.0.1"
        val serverCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName(loopback)
            .build()
        val clientCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("omnisonic-client")
            .build()
        val password = "provider-media-test"
        val clientKeyStoreBytes = pkcs12BytesFor(clientCertificate, password.toCharArray())
        val client = HttpClientFactory(OmnisonicLogger(LogSink { _, _, _, _ -> })).create(
            ConnectionSpec(
                profile = ClientProfile.MEDIA,
                clientKeyStore = pkcs12KeyStore(clientKeyStoreBytes, password),
                clientKeyStorePassword = password,
                trustedCertificates = listOf(serverCertificate.certificate),
            ),
        )
        val provider = MediaDataSourceFactoryProvider { client }

        MockWebServer().use { server ->
            server.useHttps(
                demandingClientCertificates(serverHandshakeFor(serverCertificate, clientCertificate).sslSocketFactory()),
                false,
            )
            server.enqueue(MockResponse().setBody("mtls-ok"))
            server.start()
            val dataSource = provider.factoryFor(42L).createDataSource()

            try {
                dataSource.open(DataSpec(Uri.parse(server.url("/media/track.mp3").toString())))
                val out = ByteArray(16)
                var read = 0
                while (read < out.size) {
                    val count = dataSource.read(out, read, out.size - read)
                    if (count == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                    read += count
                }
                assertThat(String(out, 0, read)).isEqualTo("mtls-ok")
            } finally {
                runCatching { dataSource.close() }
            }
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

    private fun demandingClientCertificates(
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
}
