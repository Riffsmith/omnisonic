package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.providers.ProviderTraitsSource
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderTraits

/**
 * Builds a [DownloadEnqueuer] around a caller-owned coordinator. The download
 * runtime passes its one process-wide coordinator and production run gate, so
 * UI-initiated enqueues share the global-pause lock instead of constructing a
 * second queue owner (`DownloadWorkerCoordinator` §claim protocol).
 */
class DownloadEnqueuerFactory(
    private val database: OmnisonicDatabase,
    private val traitsFor: (String) -> ProviderTraits,
) {

    fun create(
        coordinator: DownloadWorkerCoordinator,
        runGate: ProviderRunGate,
    ): DownloadEnqueuer = DownloadEnqueuer(
        db = database,
        coordinator = coordinator,
        traitsSource = ProviderTraitsSource(traitsFor),
        runGate = runGate,
    )
}
