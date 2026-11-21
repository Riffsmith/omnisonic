package app.omnisonic.core.data.downloads

import android.content.Context
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.network.ProviderStatusTracker
import app.omnisonic.core.data.providers.ProviderClientRegistry
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.provider.api.ProviderRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

private const val EXPORT_SESSION_DIRECTORY = "ExportSessions"

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

    @Provides
    @Singleton
    fun downloadQueueRepository(database: OmnisonicDatabase): DownloadQueueRepository =
        DownloadQueueRepository(database.downloadDao())

    @Provides
    @Singleton
    fun offlineMediaRepository(database: OmnisonicDatabase): OfflineMediaRepository =
        OfflineMediaRepository(database)

    @Provides
    @Singleton
    fun offlinePlaybackSource(
        database: OmnisonicDatabase,
        storage: OfflineStorage,
    ): OfflinePlaybackSource = OfflinePlaybackSource(database.offlineFileDao(), storage)

    @Provides
    @Singleton
    fun downloadCompletionRecovery(
        database: OmnisonicDatabase,
        storage: OfflineStorage,
        publisher: OfflineMediaRepository,
        logger: OmnisonicLogger,
    ): DownloadCompletionRecovery = DownloadCompletionRecovery(database, storage, publisher, logger)

    @Provides
    @Singleton
    fun offlineFileSweep(
        database: OmnisonicDatabase,
        storage: OfflineStorage,
        logger: OmnisonicLogger,
    ): OfflineFileSweep = OfflineFileSweep(database, storage, logger)

    /** The public-document adapter; the only type that touches `MediaStore.Downloads`. */
    @Provides
    @Singleton
    fun exportDocumentStore(
        @ApplicationContext context: Context,
        logger: OmnisonicLogger,
    ): ExportDocumentStore = MediaStoreExportDocumentStore(context.contentResolver, logger)

    /** Export state lives in app-internal storage so a root change never moves the ledger. */
    @Provides
    @Singleton
    fun exportSessionJournal(@ApplicationContext context: Context): ExportSessionJournal =
        ExportSessionJournal(File(context.filesDir, EXPORT_SESSION_DIRECTORY))

    @Provides
    @Singleton
    fun downloadExportPublisher(
        database: OmnisonicDatabase,
        storage: OfflineStorage,
        store: ExportDocumentStore,
        sessions: ExportSessionJournal,
        logger: OmnisonicLogger,
    ): DownloadExportPublisher = DownloadExportPublisher(
        queues = DownloadQueueRepository(database.downloadDao()),
        offline = OfflineMediaRepository(database),
        storage = storage,
        store = store,
        sessions = sessions,
        logger = logger,
    )

    @Provides
    @Singleton
    fun offlineMediaDeletion(
        database: OmnisonicDatabase,
        storage: OfflineStorage,
        cancellation: DownloadQueueCancellation,
        logger: OmnisonicLogger,
    ): OfflineMediaDeletion = OfflineMediaDeletion(database, storage, logger, cancellation)
}
