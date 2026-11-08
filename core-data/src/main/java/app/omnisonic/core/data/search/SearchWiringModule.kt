package app.omnisonic.core.data.search

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SearchWiringModule {

    @Provides
    @Singleton
    fun searchPreferencesStore(@ApplicationContext context: Context): SearchPreferencesStore =
        SearchPreferencesStore.create(context)

    @Provides
    @Singleton
    fun providerVisibilityStore(@ApplicationContext context: Context): ProviderVisibilityStore =
        ProviderVisibilityStore.create(context)
}
