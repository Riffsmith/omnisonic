package app.omnisonic.provider.navidrome

import app.omnisonic.core.model.log.ConsoleLogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.core.network.HttpClientFactory
import app.omnisonic.core.network.pkcs12KeyStore
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.MediaProviderFactory
import app.omnisonic.provider.api.ProviderConnection
import okhttp3.OkHttpClient

/** Registry binding for Subsonic-protocol servers (first target: Navidrome). */
class SubsonicProviderFactory(
    private val httpClient: OkHttpClient? = null,
    private val logger: OmnisonicLogger = OmnisonicLogger(ConsoleLogSink()),
    private val onPreferredBlockChange: suspend (providerId: Long, block: Int) -> Unit = { _, _ -> },
) : MediaProviderFactory {

    override fun apiType(): String = "subsonic"

    override fun create(connection: ProviderConnection): MediaProvider =
        SubsonicClient(
            connection,
            httpClient ?: defaultClient(connection),
        ) { block -> onPreferredBlockChange(connection.providerId, block) }

    /**
     * Mirrors the registry's API-profile spec: the client identity and trust
     * anchors ride the connection, so standalone factory use is never silently
     * unprotected. Production wiring goes through [ProviderClientRegistry]'s
     * apiClientFor, which builds the same spec.
     */
    private fun defaultClient(connection: ProviderConnection): OkHttpClient {
        val certificate = connection.clientCertificate
        return HttpClientFactory(logger).create(
            ConnectionSpec(
                headers = connection.customHeaders,
                connectTimeoutMs = connection.connectTimeoutMs.toLong(),
                readTimeoutMs = connection.readTimeoutMs.toLong(),
                clientKeyStore = certificate?.let { pkcs12KeyStore(it.keystorePkcs12, it.keystorePassword) },
                clientKeyStorePassword = certificate?.keystorePassword,
                trustedCertificates = connection.trustedServerCertificates,
            ),
        )
    }
}
