package app.omnisonic.playback.cache

import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient

/**
 * Supplies a Media3 [OkHttpDataSource.Factory] for each provider. The resolver must return the
 * provider's MEDIA-profile client so custom headers, mTLS identity and media timeouts apply to
 * every audio read. Factories are cached per provider because Media3 reuses their OkHttp pool.
 */
class MediaDataSourceFactoryProvider(
    private val clientForProvider: (Long) -> OkHttpClient,
) {
    private val factories = ConcurrentHashMap<Long, OkHttpDataSource.Factory>()

    fun factoryFor(providerId: Long): OkHttpDataSource.Factory =
        factories.computeIfAbsent(providerId) { id ->
            OkHttpDataSource.Factory(clientForProvider(id))
        }

    /** Drops the cached factory after a provider's connection or credentials change. */
    fun invalidate(providerId: Long) {
        factories.remove(providerId)
    }

    fun clear() {
        factories.clear()
    }
}
