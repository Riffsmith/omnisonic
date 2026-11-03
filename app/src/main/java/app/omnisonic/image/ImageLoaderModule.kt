package app.omnisonic.image

import android.content.Context
import app.omnisonic.core.data.providers.ProviderClientRegistry
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
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
    ): ImageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        .components {
            add(OkHttpNetworkFetcherFactory(ProviderRoutedCallFactory(registry)))
        }
        .build()
}
