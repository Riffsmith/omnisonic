package app.omnisonic.image

import android.content.Context
import app.omnisonic.playback.cache.CoverImageKey
import app.omnisonic.playback.cache.CoverKeyer
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.Decoder
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.request.crossfade
import coil3.toUri
import okhttp3.Call

/**
 * Resolves a provider cover to its salted stream URL, then delegates to the
 * network fetcher with the provider-stable cache key pinned. Rotating auth
 * salts change the URL but not the cache identity, so one cover keeps one
 * memory/disk entry.
 */
class CoverImageFetcher(
    private val key: CoverImageKey,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val networkFetcherFactory: Fetcher.Factory<Uri>,
    private val resolveUrl: suspend (CoverImageKey) -> String?,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val url = resolveUrl(key) ?: return null
        val delegate = networkFetcherFactory.create(
            url.toUri(),
            options.copy(diskCacheKey = key.cacheKey),
            imageLoader,
        ) ?: return null
        return delegate.fetch()
    }

    class Factory(
        private val resolveUrl: suspend (CoverImageKey) -> String?,
        private val networkFetcherFactory: Fetcher.Factory<Uri>,
    ) : Fetcher.Factory<CoverImageKey> {

        override fun create(data: CoverImageKey, options: Options, imageLoader: ImageLoader): Fetcher =
            CoverImageFetcher(data, options, imageLoader, networkFetcherFactory, resolveUrl)
    }
}

/** Wires the cover keyer and fetcher around a provider-routed OkHttp client. */
internal fun buildImageLoader(
    context: Context,
    callFactory: Call.Factory,
    resolveCoverUrl: suspend (CoverImageKey) -> String?,
    decoderFactory: Decoder.Factory? = null,
): ImageLoader {
    val networkFetcherFactory = OkHttpNetworkFetcherFactory(callFactory)
    return ImageLoader.Builder(context)
        .crossfade(true)
        .components {
            add(CoverKeyer())
            add(CoverImageFetcher.Factory(resolveCoverUrl, networkFetcherFactory))
            add(networkFetcherFactory)
            if (decoderFactory != null) add(decoderFactory)
        }
        .build()
}
