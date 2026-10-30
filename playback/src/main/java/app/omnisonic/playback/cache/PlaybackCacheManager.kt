package app.omnisonic.playback.cache

import android.content.Context
import android.net.Uri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Media3 SimpleCache wrapper. All non-local upstream traffic can flow through the cache;
 * `content://` and plain reads use the direct factory. Cache key is the URI string
 * (CacheDataSource default key factory).
 */
class PlaybackCacheManager(
    private val context: Context,
    private val cacheDir: File,
    private val databaseProvider: DatabaseProvider,
    initialMaxBytes: Long,
    private val upstreamFactory: DataSource.Factory,
) {
    private val lock = Any()
    private var maxBytes = initialMaxBytes
    private var cache = newCache(initialMaxBytes)

    @Volatile
    var defaultCacheFirst: Boolean = false

    private val perUriCacheFirst = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var released = false

    fun cacheFor(uri: Uri): DataSource.Factory {
        check(!released) { "cache released" }
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(wrappedUpstream())
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
    }

    fun directFor(uri: Uri): DataSource.Factory {
        check(!released) { "cache released" }
        return wrappedUpstream()
    }

    /** Per-URI override wins over [defaultCacheFirst]; consumed by OmniPlayer when building sources. */
    fun factoryFor(uri: Uri): DataSource.Factory =
        if (perUriCacheFirst[uri.toString()] ?: defaultCacheFirst) cacheFor(uri) else directFor(uri)

    fun setPerUriCacheFirst(uri: Uri, cacheFirst: Boolean) {
        perUriCacheFirst[uri.toString()] = cacheFirst
    }

    fun clearPerUriOverride(uri: Uri) {
        perUriCacheFirst.remove(uri.toString())
    }

    fun cachedBytesFor(uri: Uri): Long {
        check(!released) { "cache released" }
        return cache.getCachedBytes(uri.toString(), 0, Long.MAX_VALUE)
    }

    fun isCached(uri: Uri): Boolean = cachedBytesFor(uri) > 0

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

    private fun wrappedUpstream(): DataSource.Factory =
        DefaultDataSource.Factory(context, upstreamFactory)

    private fun newCache(maxBytes: Long): SimpleCache =
        SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(maxBytes), databaseProvider)
}
