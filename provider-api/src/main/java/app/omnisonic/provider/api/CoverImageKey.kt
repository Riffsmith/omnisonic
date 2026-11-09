package app.omnisonic.provider.api

/**
 * Stable identity of a provider cover. The image loader resolves it through
 * the owning provider's [MediaProvider.coverArtUrl] and pins the cache key to
 * [cacheKey], so rotating auth salts change the URL but never the cache entry.
 * Pure data — the Coil Keyer/Fetcher wiring lives next to the image loader.
 */
data class CoverImageKey(
    val providerId: Long,
    val imageId: String,
    val sizePx: Int? = null,
) {
    val cacheKey: String
        get() = "cover:$providerId:$imageId:${sizePx ?: "original"}"
}
