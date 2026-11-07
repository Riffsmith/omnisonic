package app.omnisonic.core.data.providers

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.network.ClientProfile
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.core.network.HttpClientFactory
import app.omnisonic.core.network.pkcs12KeyStore
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * Owns one [MediaProvider] and one OkHttpClient pair (API + MEDIA profile) per
 * provider id so engines, syncers, and downloads share a single connection
 * pool. The API-profile client is built before the provider is created and
 * injected into it, so the provider's own traffic rides the same instance
 * apiClientFor returns. Both profiles carry the same mTLS client identity and
 * trust anchors. Entries rebuild when the provider row changes (edit) or
 * disappears (delete); [invalidate] forces it for callers that know better.
 * The default client factories reuse the injected [logger], so production
 * rides the single Android-backed logger rather than a separate console sink.
 */
class ProviderClientRegistry(
    private val database: OmnisonicDatabase,
    private val providerRegistry: ProviderRegistry,
    private val mapper: ProviderConfigMapper,
    private val logger: OmnisonicLogger,
    private val mediaClientFactory: (ConnectionSpec) -> OkHttpClient = { spec ->
        HttpClientFactory(logger).create(spec)
    },
    private val apiClientFactory: (ConnectionSpec) -> OkHttpClient = { spec ->
        HttpClientFactory(logger).create(spec)
    },
    private val onCredentialsUnreadable: suspend (Long) -> Unit = {},
) {
    private data class CachedClient(
        val provider: MediaProvider,
        val mediaClient: OkHttpClient,
        val apiClient: OkHttpClient,
        val builtForUpdatedAt: Long,
    )

    private val mutex = Mutex()
    private val cache = mutableMapOf<Long, CachedClient>()

    suspend fun providerFor(providerId: Long): MediaProvider? = mutex.withLock {
        cachedLocked(providerId)?.provider
    }

    /** MEDIA-profile client for streaming and downloads. */
    suspend fun httpClientFor(providerId: Long): OkHttpClient? = mutex.withLock {
        cachedLocked(providerId)?.mediaClient
    }

    /** API-profile client for library and user-data calls. */
    suspend fun apiClientFor(providerId: Long): OkHttpClient? = mutex.withLock {
        cachedLocked(providerId)?.apiClient
    }

    /**
     * API-profile client of the provider row owning this URL (scheme/host/port
     * match on the primary or secondary block); used by the image loader so
     * cover requests ride the provider's headers, identity and trust anchors.
     * Defaults (80/443) are matched against an explicit block port. Returns
     * null when no persisted provider claims the URL; the caller falls back to
     * a plain client. The row scan is intentionally cheap; image fetches are
     * network-paced.
     */
    suspend fun apiClientForUrl(url: HttpUrl): OkHttpClient? = mutex.withLock {
        val providerId = database.providerDao().selectAll()
            .firstOrNull { row -> blockMatches(row.primaryScheme, row.primaryHost, row.primaryPort, url) }
            ?.id
            ?: database.providerDao().selectAll()
                .firstOrNull { row -> blockMatches(row.secondaryScheme, row.secondaryHost, row.secondaryPort, url) }
                ?.id
            ?: return null
        cachedLocked(providerId)?.apiClient
    }

    suspend fun invalidate(providerId: Long) {
        mutex.withLock { cache.remove(providerId) }
    }

    suspend fun invalidateAll() {
        mutex.withLock { cache.clear() }
    }

    private suspend fun cachedLocked(providerId: Long): CachedClient? {
        val row = database.providerDao().selectById(providerId) ?: run {
            cache.remove(providerId)
            return null
        }
        cache[providerId]?.takeIf { it.builtForUpdatedAt == row.updatedAt }?.let { return it }

        when (val load = mapper.from(row)) {
            is ProviderLoadResult.CredentialsUnreadable -> {
                logger.warn(TAG, "provider $providerId credentials are unreadable; re-enter the password")
                onCredentialsUnreadable(providerId)
                return null
            }

            is ProviderLoadResult.Unloadable -> {
                logger.warn(TAG, "provider $providerId has no usable connection block")
                return null
            }

            is ProviderLoadResult.Loaded -> load.connection
        }.let { connection ->
            val mediaSpec = specFor(connection, ClientProfile.MEDIA) ?: run {
                logger.warn(TAG, "provider $providerId has an unloadable client keystore; mTLS would be silently dropped")
                return null
            }
            val apiSpec = specFor(connection, ClientProfile.API) ?: return null
            // Build the API client first and hand the same instance to the
            // factory, so the provider cannot end up on a second client.
            val apiClient = apiClientFactory(apiSpec)
            val provider = providerRegistry.create(row.api, connection, apiClient) ?: run {
                logger.warn(TAG, "no provider factory for api ${row.api}")
                return null
            }
            val mediaClient = mediaClientFactory(mediaSpec)
            val cached = CachedClient(provider, mediaClient, apiClient, row.updatedAt)
            cache[providerId] = cached
            return cached
        }
    }

    private fun blockMatches(scheme: String?, host: String?, port: Int?, url: HttpUrl): Boolean {
        if (scheme == null || host == null || port == null) return false
        if (!scheme.equals(url.scheme, ignoreCase = true) || !host.equals(url.host, ignoreCase = true)) return false
        return port == url.port || url.port == defaultPortFor(url.scheme)
    }

    private fun defaultPortFor(scheme: String): Int = when (scheme.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }

    /**
     * One spec shape for both profiles; a corrupt client keystore yields null
     * so the provider is dropped instead of silently connecting without the
     * identity (no insecure fallback).
     */
    private fun specFor(connection: ProviderConnection, profile: ClientProfile): ConnectionSpec? = runCatching {
        val certificate = connection.clientCertificate
        ConnectionSpec(
            profile = profile,
            headers = connection.customHeaders,
            connectTimeoutMs = connection.connectTimeoutMs.toLong(),
            readTimeoutMs = connection.readTimeoutMs.toLong(),
            clientKeyStore = certificate?.let { pkcs12KeyStore(it.keystorePkcs12, it.keystorePassword) },
            clientKeyStorePassword = certificate?.keystorePassword,
            trustedCertificates = connection.trustedServerCertificates,
        )
    }.getOrNull()

    companion object {
        private const val TAG = "ProviderClientRegistry"
    }
}
