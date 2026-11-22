package app.omnisonic.core.data.db

import android.content.Context
import android.net.ConnectivityManager
import app.omnisonic.core.data.db.dao.OutboxDao
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.network.ConnectivityManagerNetworkMonitor
import app.omnisonic.core.data.network.ConnectivityManagerNetworkStateObserver
import app.omnisonic.core.data.network.NetworkMonitor
import app.omnisonic.core.data.network.NetworkStateObserver
import app.omnisonic.core.data.providers.DefaultTraitsSource
import app.omnisonic.core.data.providers.ProviderTraitsSource
import app.omnisonic.core.data.security.AesGcmCredentialCodec
import app.omnisonic.core.data.security.AndroidKeystoreKeySource
import app.omnisonic.core.data.security.CredentialCodec
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun omnisonicDatabase(@ApplicationContext context: Context): OmnisonicDatabase =
        OmnisonicDatabaseFactory(context).open()

    @Provides
    fun moshi(): Moshi = OmnisonicJson.moshi

    @Provides
    fun outboxDao(database: OmnisonicDatabase): OutboxDao = database.outboxDao()

    /**
     * Default until a provider advertises user-data reporting; the outbox queue
     * asks per provider at enqueue time.
     */
    @Provides
    fun providerTraitsSource(): ProviderTraitsSource = DefaultTraitsSource

    @Provides
    fun credentialCodec(): CredentialCodec =
        AesGcmCredentialCodec(keySource = AndroidKeystoreKeySource())

    @Provides
    @Singleton
    fun networkMonitor(@ApplicationContext context: Context): NetworkMonitor =
        ConnectivityManagerNetworkMonitor(
            context.getSystemService(ConnectivityManager::class.java)
                ?: error("ConnectivityManager unavailable"),
        )

    @Provides
    @Singleton
    fun networkStateObserver(
        @ApplicationContext context: Context,
        monitor: NetworkMonitor,
    ): NetworkStateObserver = ConnectivityManagerNetworkStateObserver(
        context.getSystemService(ConnectivityManager::class.java)
            ?: error("ConnectivityManager unavailable"),
        monitor,
    )
}
