package app.omnisonic.feature.downloads.service

import app.omnisonic.core.data.downloads.DownloadAuthReporting
import app.omnisonic.core.data.downloads.DownloadExportPublisher
import app.omnisonic.core.data.downloads.DownloadMediaSource
import app.omnisonic.core.data.downloads.DownloadQueueRepository
import app.omnisonic.core.data.downloads.DownloadTransferEngine
import app.omnisonic.core.data.downloads.DownloadWorkerCoordinator
import app.omnisonic.core.data.downloads.OfflineMediaRepository
import app.omnisonic.core.data.downloads.OfflineStorage
import app.omnisonic.core.model.log.OmnisonicLogger
import javax.inject.Inject
import javax.inject.Singleton

/** Binds the claimed lease loop to the real task-3 transfer pipeline. */
@Singleton
class EngineDownloadTransferHandlerFactory @Inject constructor(
    private val repository: DownloadQueueRepository,
    private val publisher: OfflineMediaRepository,
    private val storage: OfflineStorage,
    private val media: DownloadMediaSource,
    private val auth: DownloadAuthReporting,
    private val exporter: DownloadExportPublisher,
    private val logger: OmnisonicLogger,
) : DownloadTransferHandlerFactory {

    override fun create(coordinator: DownloadWorkerCoordinator): DownloadTransferHandler {
        val engine = DownloadTransferEngine(
            repository = repository,
            coordinator = coordinator,
            publisher = publisher,
            storage = storage,
            media = media,
            auth = auth,
            logger = logger,
            exporter = exporter,
        )
        return DownloadTransferHandler { lease -> engine.transfer(lease) }
    }
}
