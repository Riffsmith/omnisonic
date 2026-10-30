package app.omnisonic.core.network

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

fun pkcs12KeyStore(bytes: ByteArray, password: String?): KeyStore =
    KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(bytes), password?.toCharArray()) }

enum class ClientProfile { API, MEDIA }

/** Options for one provider connection's OkHttpClient; see app docs (PROVIDERS §multi-URL). */
data class ConnectionSpec(
    val headers: Map<String, String> = emptyMap(),
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    val profile: ClientProfile = ClientProfile.API,
    val clientKeyStore: KeyStore? = null,
    val clientKeyStorePassword: String? = null,
    val trustedCertificates: List<X509Certificate> = emptyList(),
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
        const val DEFAULT_READ_TIMEOUT_MS = 15_000L
        const val MEDIA_READ_TIMEOUT_MS = 60_000L
    }
}

internal object TlsContexts {

    fun keyManagers(clientKeyStore: KeyStore, password: String?): Array<KeyManager> =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(clientKeyStore, password?.toCharArray()) }
            .keyManagers

    fun trustManager(extraCertificates: List<X509Certificate>): X509TrustManager {
        if (extraCertificates.isEmpty()) return defaultPlatformTrustManager()
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        extraCertificates.forEachIndexed { index, certificate ->
            trustStore.setCertificateEntry("omnisonic-trust-$index", certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trustStore) }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    private fun defaultPlatformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
