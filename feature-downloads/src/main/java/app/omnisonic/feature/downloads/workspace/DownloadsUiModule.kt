package app.omnisonic.feature.downloads.workspace

import app.omnisonic.core.data.downloads.AutomaticRuleRepository
import app.omnisonic.core.data.downloads.DownloadQueueSettingsStore
import app.omnisonic.core.data.downloads.DownloadWorkStart
import app.omnisonic.core.data.downloads.DownloadWorkspaceRepository
import app.omnisonic.core.data.downloads.OfflineMediaDeletion
import app.omnisonic.core.data.search.ProviderVisibilityStore
import app.omnisonic.feature.downloads.service.DownloadUserActions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadsUiModule {

    @Provides
    @Singleton
    fun downloadsPort(
        workspace: DownloadWorkspaceRepository,
        rules: AutomaticRuleRepository,
        settings: DownloadQueueSettingsStore,
        visibility: ProviderVisibilityStore,
        actions: DownloadUserActions,
        deletion: OfflineMediaDeletion,
        workStart: DownloadWorkStart,
    ): DownloadsPort = RepositoryDownloadsPort(
        workspace = workspace,
        rules = rules,
        settingsStore = settings,
        visibility = visibility,
        actions = actions,
        deletion = deletion,
        workStart = workStart,
    )
}
