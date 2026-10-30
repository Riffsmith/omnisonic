package app.omnisonic.playback.cache

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.stream.ResolvedStream
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackCacheManagerTest {

    private val context = applicationContext()
    private val managers = mutableListOf<PlaybackCacheManager>()

    @After
    fun tearDown() {
        managers.forEach { runCatching { it.release() } }
    }

    @Test
    fun `write-through caches uri and both factories read full bytes`() {
        val data = ByteArray(400) { (it % 251).toByte() }
        val manager = newManager(4096) { ByteArrayDataSource(data) }
        val uri = streamUri("a")

        writeThroughCache(manager, uri, data)

        assertThat(manager.cachedBytesFor(uri.toString())).isEqualTo(400)
        assertThat(manager.isCached(uri.toString())).isTrue()
        assertThat(readAll(manager.cacheFor(uri).createDataSource(), uri)).isEqualTo(data)
        val direct = manager.directFor(uri).createDataSource()
        assertThat(direct).isNotInstanceOf(CacheDataSource::class.java)
        assertThat(readAll(direct, uri)).isEqualTo(data)
    }

    @Test
    fun `salted urls for the same stream share one cache entry`() {
        val data = ByteArray(400) { (it % 251).toByte() }
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(data)))
        server.start()
        val manager = newManager(4096) { DefaultHttpDataSource.Factory().createDataSource() }
        try {
            val base = server.url("/stream.mp3").toString()
            val entry = PlaybackQueueEntry(providerId = 1L, externalId = "song-1")
            val firstStream = ResolvedStream(entry, "$base?s=salt-one")
            val secondStream = ResolvedStream(entry, "$base?s=salt-two")
            val first = Uri.parse(firstStream.uri)
            val second = Uri.parse(secondStream.uri)

            assertThat(secondStream.cacheKey).isEqualTo(firstStream.cacheKey)
            writeThroughCache(manager, first, data, firstStream.cacheKey)
            assertThat(manager.isCached(secondStream.cacheKey)).isTrue()
            assertThat(readAll(manager.cacheFor(second, secondStream.cacheKey).createDataSource(), second))
                .isEqualTo(data)

            assertThat(server.requestCount).isEqualTo(1)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `lru eviction drops oldest uri when budget exceeded`() {
        val data = ByteArray(400) { (it % 251).toByte() }
        val manager = newManager(512) { ByteArrayDataSource(data) }
        val uriA = streamUri("a")
        val uriB = streamUri("b")

        writeThroughCache(manager, uriA, data)
        assertThat(manager.cachedBytesFor(uriA.toString())).isEqualTo(400)

        writeThroughCache(manager, uriB, data)

        assertThat(manager.cachedBytesFor(uriA.toString())).isEqualTo(0)
        assertThat(manager.cachedBytesFor(uriB.toString())).isEqualTo(400)
    }

    @Test
    fun `per-uri switch overrides cache-first default`() {
        val manager = newManager(4096) { ByteArrayDataSource(ByteArray(10)) }
        val cached = streamUri("cached")
        val direct = streamUri("direct")

        manager.setPerUriCacheFirst(cached.toString(), true)
        assertThat(manager.factoryFor(cached, cached.toString()).createDataSource()).isInstanceOf(CacheDataSource::class.java)
        assertThat(manager.factoryFor(direct, direct.toString()).createDataSource()).isInstanceOf(DefaultDataSource::class.java)

        manager.defaultCacheFirst = true
        assertThat(manager.factoryFor(direct, direct.toString()).createDataSource()).isInstanceOf(CacheDataSource::class.java)
        assertThat(manager.factoryFor(cached, cached.toString()).createDataSource()).isInstanceOf(CacheDataSource::class.java)

        manager.setPerUriCacheFirst(cached.toString(), false)
        assertThat(manager.factoryFor(cached, cached.toString()).createDataSource()).isInstanceOf(DefaultDataSource::class.java)

        manager.clearPerUriOverride(cached.toString())
        assertThat(manager.factoryFor(cached, cached.toString()).createDataSource()).isInstanceOf(CacheDataSource::class.java)
    }

    @Test
    fun `applyMaxBytes keeps content and applies new budget on later writes`() {
        val data = ByteArray(400) { (it % 251).toByte() }
        val manager = newManager(512) { ByteArrayDataSource(data) }
        val uriA = streamUri("a")
        val uriB = streamUri("b")

        writeThroughCache(manager, uriA, data)
        manager.applyMaxBytes(2048)
        writeThroughCache(manager, uriB, data)

        assertThat(manager.cachedBytesFor(uriA.toString())).isEqualTo(400)
        assertThat(manager.cachedBytesFor(uriB.toString())).isEqualTo(400)
    }

    @Test
    fun `release invalidates further use`() {
        val manager = newManager(4096) { ByteArrayDataSource(ByteArray(10)) }
        manager.release()
        managers.remove(manager)

        assertThrows(IllegalStateException::class.java) { manager.factoryFor(streamUri("a"), streamUri("a").toString()) }
        assertThrows(IllegalStateException::class.java) { manager.cachedBytesFor(streamUri("a").toString()) }
        assertThrows(IllegalStateException::class.java) { manager.applyMaxBytes(1) }
    }

    private fun newManager(maxBytes: Long, upstream: () -> DataSource): PlaybackCacheManager {
        val manager = PlaybackCacheManager(
            context = context,
            cacheDir = File(context.cacheDir, "t5-${UUID.randomUUID()}"),
            databaseProvider = StandaloneDatabaseProvider(context),
            initialMaxBytes = maxBytes,
            upstreamFactory = DataSource.Factory { upstream() },
        )
        managers.add(manager)
        return manager
    }

    private fun writeThroughCache(
        manager: PlaybackCacheManager,
        uri: Uri,
        data: ByteArray,
        cacheKey: String = uri.toString(),
    ) {
        val dataSource = manager.cacheFor(uri, cacheKey).createDataSource() as CacheDataSource
        val dataSpec = DataSpec(uri, 0, data.size.toLong()).buildUpon().setKey(cacheKey).build()
        try {
            CacheWriter(dataSource, dataSpec, null, null).cache()
        } catch (e: IOException) {
            throw AssertionError("cache write failed for $uri", e)
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun readAll(dataSource: DataSource, uri: Uri): ByteArray {
        try {
            dataSource.open(DataSpec(uri, 0, 400))
            val out = ByteArray(400)
            var offset = 0
            while (offset < out.size) {
                val read = dataSource.read(out, offset, out.size - offset)
                if (read == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                offset += read
            }
            return out.copyOf(offset)
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun streamUri(name: String): Uri = Uri.parse("https://cache.test/$name.mp3")

    private fun applicationContext(): Context =
        androidx.test.core.app.ApplicationProvider.getApplicationContext()
}
