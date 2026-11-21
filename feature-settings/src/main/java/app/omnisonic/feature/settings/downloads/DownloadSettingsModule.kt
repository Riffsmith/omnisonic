package app.omnisonic.feature.settings.downloads

import app.omnisonic.core.data.downloads.DownloadQueueSettingsStore
import app.omnisonic.core.data.downloads.OfflineRootWorkGate
import app.omnisonic.core.data.downloads.OfflineStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadSettingsModule {

    @Provides
    @Singleton
    fun downloadSettingsPort(
        store: DownloadQueueSettingsStore,
        storage: OfflineStorage,
        gate: OfflineRootWorkGate,
    ): DownloadSettingsPort = CoreDownloadSettingsPort(store, storage, gate)
}
