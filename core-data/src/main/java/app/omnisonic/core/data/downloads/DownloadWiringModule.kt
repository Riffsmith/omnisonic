package app.omnisonic.core.data.downloads

import android.content.Context
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.network.ProviderStatusTracker
import app.omnisonic.core.data.providers.ProviderClientRegistry
import app.omnisonic.provider.api.ProviderRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The global download settings and root journal must survive process death,
 * so the store and the offline storage seam are app-scoped. The coordinator
 * itself is constructed by the download service wiring (Phase 7 task 4) from
 * the persisted worker count, which is why no worker-count default is bound
 * here.
 */
@Module
@InstallIn(SingletonComponent::class)
object DownloadWiringModule {

    @Provides
    @Singleton
    fun downloadQueueSettingsStore(@ApplicationContext context: Context): DownloadQueueSettingsStore =
        DownloadQueueSettingsStore.create(context)

    @Provides
    @Singleton
    fun offlineRootSource(@ApplicationContext context: Context): OfflineRootSource =
        AppPrivateOfflineRootSource(context)

    @Provides
    @Singleton
    fun offlineStorage(
        roots: OfflineRootSource,
        settings: DownloadQueueSettingsStore,
    ): OfflineStorage = OfflineStorage(roots, settings)

    /** Bytes always ride the registry MEDIA client; a provider that cannot be rebuilt fails closed. */
    @Provides
    @Singleton
    fun downloadMediaSource(
        database: OmnisonicDatabase,
        clients: ProviderClientRegistry,
        providers: ProviderRegistry,
    ): DownloadMediaSource = ProviderMediaSource(database, clients, providers)

    @Provides
    @Singleton
    fun downloadAuthReporting(tracker: ProviderStatusTracker): DownloadAuthReporting =
        ProviderAuthReporting(tracker)
}
