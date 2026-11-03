package app.omnisonic.di

import app.omnisonic.core.data.db.DatabaseModule
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.network.ProviderStatusTracker
import app.omnisonic.core.data.providers.ProviderClientRegistry
import app.omnisonic.core.data.providers.ProviderConfigMapper
import app.omnisonic.core.data.security.CredentialCodec
import app.omnisonic.provider.api.ProviderRegistry
import app.omnisonic.provider.local.LocalMediaProviderFactory
import app.omnisonic.provider.navidrome.SubsonicProviderFactory
import android.content.Context
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Wiring for the provider client registry (T12/T21) and its first production consumer. */
@Module
@InstallIn(SingletonComponent::class)
object ProviderWiringModule {

    @Provides
    @Singleton
    fun providerRegistry(@ApplicationContext context: Context): ProviderRegistry =
        ProviderRegistry(
            listOf(
                SubsonicProviderFactory(),
                LocalMediaProviderFactory(context.contentResolver),
            ),
        )

    @Provides
    @Singleton
    fun providerConfigMapper(codec: CredentialCodec, moshi: Moshi): ProviderConfigMapper =
        ProviderConfigMapper(codec, moshi)

    @Provides
    @Singleton
    fun providerClientRegistry(
        database: OmnisonicDatabase,
        providerRegistry: ProviderRegistry,
        mapper: ProviderConfigMapper,
        statusTracker: ProviderStatusTracker,
    ): ProviderClientRegistry =
        ProviderClientRegistry(
            database = database,
            providerRegistry = providerRegistry,
            mapper = mapper,
            onCredentialsUnreadable = { providerId ->
                statusTracker.markCredentialsUnreadable(providerId)
            },
        )
}
