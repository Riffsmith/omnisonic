package app.omnisonic.playback.cache

import coil3.key.Keyer
import coil3.request.Options

data class CoverImageKey(
    val providerId: Long,
    val imageId: String,
    val sizePx: Int? = null,
) {
    val cacheKey: String
        get() = "cover:$providerId:$imageId:${sizePx ?: "original"}"
}

class CoverKeyer : Keyer<CoverImageKey> {
    override fun key(data: CoverImageKey, options: Options): String = data.cacheKey
}
