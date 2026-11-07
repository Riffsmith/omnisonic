package app.omnisonic.provider.navidrome

import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.MediaProviderFactory
import app.omnisonic.provider.api.ProviderConnection
import okhttp3.OkHttpClient

/**
 * Registry binding for Subsonic-protocol servers (first target: Navidrome).
 * The provider rides the API client the registry built for the connection —
 * custom headers, timeouts, mTLS identity and trust anchors included — so the
 * registry's apiClientFor and the provider's own traffic share one instance.
 * Factories never build clients themselves.
 */
class SubsonicProviderFactory(
    private val onPreferredBlockChange: suspend (providerId: Long, block: Int) -> Unit = { _, _ -> },
    private val onAuthSucceeded: suspend (providerId: Long) -> Unit = {},
) : MediaProviderFactory {

    override fun apiType(): String = "subsonic"

    override fun create(connection: ProviderConnection, apiClient: OkHttpClient): MediaProvider =
        SubsonicClient(
            connection,
            apiClient,
            onPreferredBlockChange = { block -> onPreferredBlockChange(connection.providerId, block) },
            onAuthSucceeded = onAuthSucceeded,
        )
}
