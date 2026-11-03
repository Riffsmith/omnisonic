package app.omnisonic.image

import app.omnisonic.core.data.providers.ProviderClientRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Routes image requests to the owning provider's API-profile client so cover
 * fetches carry the provider's custom headers, mTLS identity and trust anchors
 * (T4/T12 contract). Unknown URLs fall back to a plain client.
 */
class ProviderRoutedCallFactory(
    private val registry: ProviderClientRegistry,
    private val defaultClient: OkHttpClient = OkHttpClient(),
) : Call.Factory {

    override fun newCall(request: Request): Call {
        val client = runBlocking { registry.apiClientForUrl(request.url) } ?: defaultClient
        return client.newCall(request)
    }
}
