package app.omnisonic.core.data.providers

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.model.log.ConsoleLogSink
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
import okhttp3.OkHttpClient

/**
 * Caches one [MediaProvider] and one OkHttpClient per provider id and client
 * profile so engines, syncers, and downloads share a single connection pool.
 * Both profiles carry the same mTLS client identity and trust anchors. Entries
 * rebuild when the provider row changes (edit) or disappears (delete);
 * [invalidate] forces it for callers that know better.
 */
class ProviderClientRegistry(
    private val database: OmnisonicDatabase,
    private val providerRegistry: ProviderRegistry,
    private val mapper: ProviderConfigMapper,
    private val mediaClientFactory: (ConnectionSpec) -> OkHttpClient = ::defaultMediaClient,
    private val apiClientFactory: (ConnectionSpec) -> OkHttpClient = ::defaultApiClient,
    private val onCredentialsUnreadable: suspend (Long) -> Unit = {},
    private val logger: OmnisonicLogger = OmnisonicLogger(ConsoleLogSink()),
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
            val provider = providerRegistry.create(row.api, connection) ?: run {
                logger.warn(TAG, "no provider factory for api ${row.api}")
                return null
            }
            val mediaSpec = specFor(connection, ClientProfile.MEDIA) ?: run {
                logger.warn(TAG, "provider $providerId has an unloadable client keystore; mTLS would be silently dropped")
                return null
            }
            val apiSpec = specFor(connection, ClientProfile.API) ?: return null
            val mediaClient = mediaClientFactory(mediaSpec)
            val apiClient = apiClientFactory(apiSpec)
            val cached = CachedClient(provider, mediaClient, apiClient, row.updatedAt)
            cache[providerId] = cached
            return cached
        }
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

        private fun defaultMediaClient(spec: ConnectionSpec): OkHttpClient =
            HttpClientFactory(OmnisonicLogger(ConsoleLogSink())).create(spec)

        private fun defaultApiClient(spec: ConnectionSpec): OkHttpClient =
            HttpClientFactory(OmnisonicLogger(ConsoleLogSink())).create(spec)
    }
}
