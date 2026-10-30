package app.omnisonic.provider.navidrome

import app.omnisonic.core.model.log.ConsoleLogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.core.network.HttpClientFactory
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.MediaProviderFactory
import app.omnisonic.provider.api.ProviderConnection
import okhttp3.OkHttpClient

/** Registry binding for Subsonic-protocol servers (first target: Navidrome). */
class SubsonicProviderFactory(
    private val httpClient: OkHttpClient? = null,
    private val logger: OmnisonicLogger = OmnisonicLogger(ConsoleLogSink()),
) : MediaProviderFactory {

    override fun apiType(): String = "subsonic"

    override fun create(connection: ProviderConnection): MediaProvider =
        SubsonicClient(connection, httpClient ?: defaultClient(connection))

    private fun defaultClient(connection: ProviderConnection): OkHttpClient =
        HttpClientFactory(logger).create(
            ConnectionSpec(
                headers = connection.customHeaders,
                connectTimeoutMs = connection.connectTimeoutMs.toLong(),
                readTimeoutMs = connection.readTimeoutMs.toLong(),
            ),
        )
}
