package app.omnisonic.image

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.provider.api.CoverImageKey
import coil3.ImageLoader
import coil3.decode.BlackholeDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
class CoverImageLoaderTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var imageLoader: ImageLoader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        if (::imageLoader.isInitialized) imageLoader.shutdown()
        server.shutdown()
    }

    @Test
    fun `salted urls for one cover share a single cache entry`() = runBlocking {
        val key = CoverImageKey(providerId = 1L, imageId = "ca-${UUID.randomUUID()}", sizePx = 300)
        val salts = AtomicInteger()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(PNG_BYTES)),
        )
        imageLoader = buildImageLoader(
            context = context,
            callFactory = OkHttpClient(),
            resolveCoverUrl = { requested ->
                assertThat(requested).isEqualTo(key)
                server.url("/cover?image=${key.imageId}&salt=${salts.incrementAndGet()}").toString()
            },
            decoderFactory = BlackholeDecoder.Factory(),
        )

        val first = imageLoader.execute(coverRequest(key))
        val second = imageLoader.execute(coverRequest(key))

        assertThat(first).isInstanceOf(SuccessResult::class.java)
        assertThat(second).isInstanceOf(SuccessResult::class.java)
        assertThat(salts.get()).isEqualTo(2)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat((first as SuccessResult).diskCacheKey).isEqualTo(key.cacheKey)
        assertThat((second as SuccessResult).diskCacheKey).isEqualTo(key.cacheKey)
    }

    private fun coverRequest(key: CoverImageKey): ImageRequest =
        ImageRequest.Builder(context)
            .data(key)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()

    private companion object {
        val PNG_BYTES: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
