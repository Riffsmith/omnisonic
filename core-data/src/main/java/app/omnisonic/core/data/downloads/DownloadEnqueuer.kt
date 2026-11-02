package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.providers.DefaultTraitsSource
import app.omnisonic.core.data.providers.ProviderTraitsSource
import app.omnisonic.provider.api.AllowAllRunGate
import app.omnisonic.provider.api.DownloadEnqueueResult
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import app.omnisonic.provider.api.ProviderTraits
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed entry into the download pipeline. Consults the provider's traits
 * first (a permanent capability: unsupported providers always get the typed
 * rejection) and the [ProviderStatusTracker] run gate second (transient:
 * offline or not on required Wi-Fi defers with nothing written). Enqueueing
 * an already-known media key is idempotent and never resets progress.
 */
@Singleton
class DownloadEnqueuer @Inject constructor(
    private val db: OmnisonicDatabase,
    private val traitsSource: ProviderTraitsSource = DefaultTraitsSource,
    private val runGate: ProviderRunGate = AllowAllRunGate,
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
        if (!traitsFor(providerId).supportsDownload) {
            return DownloadEnqueueResult.SkippedDownloadUnsupported
        }
        val status = runGate.check(providerId)
        if (status != ProviderStatus.OK) {
            return DownloadEnqueueResult.BlockedByStatus(status)
        }
        val now = nowMs()
        db.downloadDao().insertIgnoring(
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
        return DownloadEnqueueResult.Enqueued
    }

    private suspend fun traitsFor(providerId: Long): ProviderTraits {
        val api = db.providerDao().selectById(providerId)?.api ?: return ProviderTraits()
        return traitsSource.traitsFor(api)
    }
}
