package app.omnisonic.playback.cache

import app.omnisonic.provider.api.CoverImageKey
import coil3.key.Keyer
import coil3.request.Options

class CoverKeyer : Keyer<CoverImageKey> {
    override fun key(data: CoverImageKey, options: Options): String = data.cacheKey
}
