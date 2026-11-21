package app.omnisonic.feature.downloads.service

import app.omnisonic.core.data.downloads.AutomaticRuleReactionFactory
import app.omnisonic.core.data.downloads.DownloadCompletionRecovery
import app.omnisonic.core.data.downloads.DownloadEnqueuePort
import app.omnisonic.core.data.downloads.DownloadEnqueuerFactory
import app.omnisonic.core.data.downloads.DownloadExportPublisher
import app.omnisonic.core.data.downloads.DownloadQueueCancellation
import app.omnisonic.core.data.downloads.DownloadQueueRepository
import app.omnisonic.core.data.downloads.DownloadQueueSettingsStore
import app.omnisonic.core.data.downloads.DownloadWorkStart
import app.omnisonic.core.data.downloads.OfflineFileSweep
import app.omnisonic.core.data.downloads.OfflineRootWorkGate
import app.omnisonic.core.data.downloads.OfflineStorage
import app.omnisonic.core.data.network.NetworkMonitor
import app.omnisonic.core.data.network.ProviderStatusTracker
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.provider.api.ProviderRunGate
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadServiceBindings {

    @Binds
    @Singleton
    abstract fun downloadServiceRuntime(impl: ForegroundDownloadRuntime): DownloadServiceRuntime

    /** The real root-change gate: new claims stop and active transfers drain first. */
    @Binds
    @Singleton
    abstract fun offlineRootWorkGate(impl: ForegroundDownloadRuntime): OfflineRootWorkGate

    /** Manual offline deletes cancel an equivalent active queue row through the same coordinator. */
    @Binds
    @Singleton
    abstract fun downloadQueueCancellation(impl: ForegroundDownloadRuntime): DownloadQueueCancellation

    /** One process-wide command surface for the downloads workspace and library actions. */
    @Binds
    @Singleton
    abstract fun downloadUserActions(impl: ForegroundDownloadRuntime): DownloadUserActions

    @Binds
    @Singleton
    abstract fun downloadEnqueuePort(impl: ForegroundDownloadRuntime): DownloadEnqueuePort

    @Binds
    @Singleton
    abstract fun downloadNotificationCenter(
        impl: AndroidDownloadNotificationCenter,
    ): DownloadNotificationCenter

    @Binds
    @Singleton
    abstract fun downloadTransferLocks(impl: AndroidDownloadTransferLocks): DownloadTransferLocks

    @Binds
    @Singleton
    abstract fun downloadConnectivityObserver(
        impl: ConnectivityManagerDownloadObserver,
    ): DownloadConnectivityObserver

    @Binds
    @Singleton
    abstract fun downloadTransferHandlerFactory(
        impl: EngineDownloadTransferHandlerFactory,
    ): DownloadTransferHandlerFactory
}

@Module
@InstallIn(SingletonComponent::class)
object DownloadServiceProviders {

    @Provides
    @Singleton
    fun downloadWorkStart(starter: DownloadServiceStarter): DownloadWorkStart =
        DownloadWorkStart { starter.start() }

    /** The production provider status/network gate; never the permissive default. */
    @Provides
    @Singleton
    fun downloadProviderRunGate(tracker: ProviderStatusTracker): ProviderRunGate = tracker.runGate

    @Provides
    @Singleton
    fun foregroundDownloadRuntime(
        repository: DownloadQueueRepository,
        settings: DownloadQueueSettingsStore,
        storage: OfflineStorage,
        completionRecovery: DownloadCompletionRecovery,
        exportPublisher: DownloadExportPublisher,
        offlineSweep: OfflineFileSweep,
        network: NetworkMonitor,
        runGate: ProviderRunGate,
        locks: DownloadTransferLocks,
        notifications: DownloadNotificationCenter,
        connectivity: DownloadConnectivityObserver,
        handlers: DownloadTransferHandlerFactory,
        enqueuerFactory: DownloadEnqueuerFactory,
        workStart: DownloadWorkStart,
        automaticRuleReactions: AutomaticRuleReactionFactory,
        logger: OmnisonicLogger,
    ): ForegroundDownloadRuntime = ForegroundDownloadRuntime(
        repository = repository,
        settings = settings,
        storage = storage,
        completionRecovery = completionRecovery,
        exportPublisher = exportPublisher,
        offlineSweep = offlineSweep,
        network = network,
        runGate = runGate,
        locks = locks,
        notifications = notifications,
        connectivity = connectivity,
        handlers = handlers,
        enqueuerFactory = enqueuerFactory,
        workStart = workStart,
        automaticRuleReactions = automaticRuleReactions,
        logger = logger,
    )
}
