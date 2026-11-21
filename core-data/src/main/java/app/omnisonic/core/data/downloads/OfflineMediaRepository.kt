package app.omnisonic.core.data.downloads

import androidx.room.withTransaction
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.provider.api.ProviderMediaType
import kotlinx.coroutines.CancellationException

/** Result of the atomic publication transaction; a stale or paused row publishes nothing. */
enum class PublishSettle { APPLIED, ROW_NOT_ACTIVE, FAILED }

/** The offline row published for one completed ordinary download (Phase 7 task 5a). */
fun offlinePublicationRow(
    item: DownloadItemEntity,
    relativePath: String,
    sizeBytes: Long,
    nowMs: Long,
): OfflineFileEntity = OfflineFileEntity(
    id = 0,
    providerId = item.providerId,
    mediaType = ProviderMediaType.SONG.name,
    sourceKey = item.mediaKey,
    offlinePath = relativePath,
    sizeBytes = sizeBytes,
    title = item.title,
    imagePath = item.imagePath,
    bitrateKbps = item.transcodeBitrateKbps,
    autoRule = item.autoRuleId?.toString(),
    downloadedAt = nowMs,
    syncedAt = null,
    tempCache = item.tempCache,
    externalId = DownloadKeys.parseMediaKey(item.mediaKey)?.externalId,
)

/**
 * Atomic completion publication (Phase 7 task 5a, D-43). The `offline_media`
 * row and the queue-row deletion commit in one Room transaction, so a crash or
 * a failed write can never expose a phantom offline row or lose the queue row
 * while its file is unpublished. The variant-qualified path and the stream-key
 * `sourceKey` keep a replacement variant's old row playable until its own
 * publication commits.
 */
class OfflineMediaRepository(
    private val db: OmnisonicDatabase,
    private val offlineWrite: suspend (OfflineFileEntity) -> Unit = { db.offlineFileDao().upsert(it) },
) {

    suspend fun selectByOfflinePath(offlinePath: String): OfflineFileEntity? =
        db.offlineFileDao().selectByOfflinePath(offlinePath)

    /** The published copy of one stream key, used by export to avoid a second download. */
    suspend fun selectBySourceKey(sourceKey: String): OfflineFileEntity? =
        db.offlineFileDao().selectBySourceKey(sourceKey)

    /** Engine settlement: publishes only when the lease-fenced row is still `ACTIVE`. */
    suspend fun publishAndSettleActive(item: DownloadItemEntity, offline: OfflineFileEntity): PublishSettle = try {
        db.withTransaction {
            if (db.downloadDao().deleteIfActive(item.id) != 1) return@withTransaction PublishSettle.ROW_NOT_ACTIVE
            offlineWrite(offline)
            PublishSettle.APPLIED
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PublishSettle.FAILED
    }

    /** Pre-claim crash recovery: the row may sit in any known state after orphan reconciliation. */
    suspend fun publishAndSettleRecovered(rowId: Long, offline: OfflineFileEntity): Boolean = try {
        db.withTransaction {
            if (db.downloadDao().deleteById(rowId) != 1) return@withTransaction false
            offlineWrite(offline)
            true
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
