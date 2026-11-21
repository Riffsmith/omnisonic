package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.providers.ProviderTraitsSource
import app.omnisonic.provider.api.DownloadEnqueueResult
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import app.omnisonic.provider.api.ProviderTraits

/**
 * Typed entry into the download pipeline. Consults the provider's traits first
 * (a permanent capability: unsupported providers always get the typed
 * rejection) and the run gate second (transient: blocked providers defer with
 * nothing written). Insertion is delegated to [DownloadWorkerCoordinator] so
 * the global-pause check and the insert share one lock; an already-known media
 * key is a strict whole-row no-op in every state.
 *
 * Constructed explicitly by the download wiring with real provider traits and
 * the production run gate — there is deliberately no injectable fallback.
 */
class DownloadEnqueuer(
    private val db: OmnisonicDatabase,
    private val coordinator: DownloadWorkerCoordinator,
    private val traitsSource: ProviderTraitsSource,
    private val runGate: ProviderRunGate,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    suspend fun enqueue(
        providerId: Long,
        externalId: String,
        title: String? = null,
        imagePath: String? = null,
        transcodeBitrateKbps: Int? = null,
        format: String? = null,
        tempCache: Boolean = false,
        exportTarget: String? = null,
        autoRuleId: Long? = null,
    ): DownloadEnqueueResult {
        if (traitsFor(providerId)?.supportsDownload != true) {
            return DownloadEnqueueResult.SkippedDownloadUnsupported
        }
        val status = runGate.check(providerId)
        if (status != ProviderStatus.OK) {
            return DownloadEnqueueResult.BlockedByStatus(status)
        }
        val now = nowMs()
        val attempt = coordinator.enqueueChecked(
            DownloadItemEntity(
                mediaKey = DownloadKeys.mediaKey(providerId, externalId, transcodeBitrateKbps, format),
                providerId = providerId,
                status = DownloadStatus.PENDING.name,
                title = title,
                imagePath = imagePath,
                transcodeBitrateKbps = transcodeBitrateKbps,
                tempCache = tempCache,
                exportTarget = exportTarget,
                autoRuleId = autoRuleId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return when (attempt) {
            EnqueueAttempt.Inserted, EnqueueAttempt.Duplicate -> DownloadEnqueueResult.Enqueued
            is EnqueueAttempt.DuplicateOtherDestination ->
                DownloadEnqueueResult.NeedsDestinationChange(attempt.existingExportTarget)
        }
    }

    private suspend fun traitsFor(providerId: Long): ProviderTraits? {
        val api = db.providerDao().selectById(providerId)?.api ?: return null
        return traitsSource.traitsFor(api)
    }
}
