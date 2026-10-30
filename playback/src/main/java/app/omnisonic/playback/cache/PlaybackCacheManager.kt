package app.omnisonic.playback.cache

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.omnisonic.playback.api.stream.ResolvedStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Media3 SimpleCache wrapper. All non-local upstream traffic can flow through the cache;
 * `content://` and plain reads use the direct factory. Callers pass the provider-stable
 * stream key so rotating auth salts never create new cache entries.
 */
class PlaybackCacheManager(
    private val context: Context,
    private val cacheDir: File,
    private val databaseProvider: DatabaseProvider,
    initialMaxBytes: Long,
    private val upstreamFactory: (ResolvedStream) -> DataSource.Factory,
) {
    private val lock = Any()
    private var maxBytes = initialMaxBytes
    private var cache = newCache(initialMaxBytes)

    @Volatile
    var defaultCacheFirst: Boolean = false

    private val perUriCacheFirst = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var released = false

    fun cacheFor(stream: ResolvedStream): DataSource.Factory {
        check(!released) { "cache released" }
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(wrappedUpstream(stream))
            .setCacheKeyFactory { dataSpec -> dataSpec.key ?: stream.cacheKey }
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
    }

    fun directFor(stream: ResolvedStream): DataSource.Factory {
        check(!released) { "cache released" }
        return wrappedUpstream(stream)
    }

    /** Per-stream override wins over [defaultCacheFirst]; consumed by OmniPlayer when building sources. */
    fun factoryFor(stream: ResolvedStream): DataSource.Factory =
        if (perUriCacheFirst[stream.cacheKey] ?: defaultCacheFirst) cacheFor(stream) else directFor(stream)

    fun setPerUriCacheFirst(cacheKey: String, cacheFirst: Boolean) {
        perUriCacheFirst[cacheKey] = cacheFirst
    }

    fun clearPerUriOverride(cacheKey: String) {
        perUriCacheFirst.remove(cacheKey)
    }

    fun cachedBytesFor(cacheKey: String): Long {
        check(!released) { "cache released" }
        return cache.getCachedBytes(cacheKey, 0, Long.MAX_VALUE)
    }

    fun isCached(cacheKey: String): Boolean = cachedBytesFor(cacheKey) > 0

    /**
     * Recreates the underlying SimpleCache with a new LRU budget. Eviction reacts to span
     * commits, so a shrink takes effect as new content lands; call only while playback is idle
     * because open DataSources lose their cache binding on recreation.
     */
    fun applyMaxBytes(bytes: Long) {
        check(!released) { "cache released" }
        synchronized(lock) {
            if (bytes == maxBytes) return
            cache.release()
            cache = newCache(bytes)
            maxBytes = bytes
        }
    }

    fun release() {
        if (released) return
        synchronized(lock) {
            released = true
            cache.release()
        }
    }

    private fun wrappedUpstream(stream: ResolvedStream): DataSource.Factory =
        DefaultDataSource.Factory(context, upstreamFactory(stream))

    private fun newCache(maxBytes: Long): SimpleCache =
        SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(maxBytes), databaseProvider)
}
