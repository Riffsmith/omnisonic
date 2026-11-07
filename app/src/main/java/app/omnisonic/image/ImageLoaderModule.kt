package app.omnisonic.image

import android.content.Context
import app.omnisonic.core.data.providers.ProviderClientRegistry
import app.omnisonic.playback.cache.CoverImageKey
import coil3.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {

    @Provides
    @Singleton
    fun imageLoader(
        @ApplicationContext context: Context,
        registry: ProviderClientRegistry,
    ): ImageLoader = buildImageLoader(
        context = context,
        callFactory = ProviderRoutedCallFactory(registry),
        resolveCoverUrl = { key: CoverImageKey ->
            registry.providerFor(key.providerId)?.coverArtUrl(key.imageId, key.sizePx)
        },
    )
}
