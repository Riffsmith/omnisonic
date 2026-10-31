package app.omnisonic.core.data.providers

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.model.log.ConsoleLogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.network.ClientProfile
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.core.network.HttpClientFactory
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Caches one [MediaProvider] and one media-profile [OkHttpClient] per provider
 * id so engines, syncers, and downloads share a single connection pool.
 * Entries rebuild when the provider row changes (edit) or disappears (delete);
 * [invalidate] forces it for callers that know better.
 */
class ProviderClientRegistry(
    private val database: OmnisonicDatabase,
    private val providerRegistry: ProviderRegistry,
    private val mapper: ProviderConfigMapper,
    private val mediaClientFactory: (ProviderConnection) -> OkHttpClient = ::defaultMediaClient,
    private val logger: OmnisonicLogger = OmnisonicLogger(ConsoleLogSink()),
) {
    private data class CachedClient(
        val provider: MediaProvider,
        val httpClient: OkHttpClient,
        val builtForUpdatedAt: Long,
    )

    private val mutex = Mutex()
    private val cache = mutableMapOf<Long, CachedClient>()

    suspend fun providerFor(providerId: Long): MediaProvider? = mutex.withLock {
        cachedLocked(providerId)?.provider
    }

    suspend fun httpClientFor(providerId: Long): OkHttpClient? = mutex.withLock {
        cachedLocked(providerId)?.httpClient
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

        val connection = mapper.from(row) ?: run {
            logger.warn(TAG, "provider $providerId has no usable connection block")
            return null
        }
        val provider = providerRegistry.create(row.api, connection) ?: run {
            logger.warn(TAG, "no provider factory for api ${row.api}")
            return null
        }
        val client = mediaClientFactory(connection)
        val cached = CachedClient(provider, client, row.updatedAt)
        cache[providerId] = cached
        return cached
    }

    companion object {
        private const val TAG = "ProviderClientRegistry"

        private fun defaultMediaClient(connection: ProviderConnection): OkHttpClient =
            HttpClientFactory(OmnisonicLogger(ConsoleLogSink())).create(
                ConnectionSpec(
                    profile = ClientProfile.MEDIA,
                    headers = connection.customHeaders,
                    connectTimeoutMs = connection.connectTimeoutMs.toLong(),
                    readTimeoutMs = connection.readTimeoutMs.toLong(),
                ),
            )
    }
}
