package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.model.log.OmnisonicLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Live queue-cancellation seam for a delete command. Production is the
 * foreground runtime's process-wide coordinator, which deletes the row from
 * any known state and signals an active transfer; a host with no running
 * runtime can use [Direct], where the delete removes the queue row itself.
 */
fun interface DownloadQueueCancellation {
    suspend fun cancel(rowId: Long): Boolean

    companion object {
        val Direct = DownloadQueueCancellation { false }
    }
}

/** What one manual offline delete did; [deleted] is false when the row was already gone. */
data class OfflineDeleteResult(
    val deleted: Boolean,
    val cancelledQueueItem: Boolean,
    val fileRemoved: Boolean,
)

/** How many rows/files one provider offline delete removed. */
data class OfflineProviderDeleteCounts(
    val rows: Int,
    val filesRemoved: Int,
)

/**
 * Manual and provider offline deletion (Phase 7 task 5b). Both paths remove the
 * `offline_media` row together with its stored file, and neither can be undone
 * by a stale worker: a manual delete first cancels the equivalent queue row
 * through [cancellation], and the task-5a publication transaction is fenced by
 * the still-`ACTIVE` row, so a late completion cannot recreate the row.
 *
 * A deleted file is resolved only under the active root. When that root is
 * unavailable the row is still removed and the file is left for the task-5a
 * orphan pass or the integrity sweep once the volume returns. Deleting a
 * provider's offline media is a hook that runs before the provider row delete;
 * the existing cascade still removes every remaining queue/offline row.
 */
class OfflineMediaDeletion(
    private val db: OmnisonicDatabase,
    private val storage: OfflineStorage,
    private val logger: OmnisonicLogger,
    private val cancellation: DownloadQueueCancellation = DownloadQueueCancellation.Direct,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun deleteOffline(offlineId: Long): OfflineDeleteResult = withContext(ioDispatcher) {
        val row = db.offlineFileDao().selectById(offlineId)
            ?: return@withContext OfflineDeleteResult(
                deleted = false,
                cancelledQueueItem = false,
                fileRemoved = false,
            )
        val queueRow = db.downloadDao().selectByMediaKey(row.sourceKey)
        val cancelled = queueRow != null && cancelEquivalent(queueRow.id)
        db.offlineFileDao().deleteById(row.id)
        val fileRemoved = deleteStoredFile(row.offlinePath)
        logger.info(
            TAG,
            "offline delete id=${row.id} queueCancelled=$cancelled fileRemoved=$fileRemoved",
        )
        OfflineDeleteResult(deleted = true, cancelledQueueItem = cancelled, fileRemoved = fileRemoved)
    }

    suspend fun deleteProviderOffline(providerId: Long): OfflineProviderDeleteCounts =
        withContext(ioDispatcher) {
            val rows = db.offlineFileDao().selectByProvider(providerId)
            var filesRemoved = 0
            for (row in rows) {
                if (deleteStoredFile(row.offlinePath)) filesRemoved++
                db.offlineFileDao().deleteById(row.id)
            }
            if (rows.isNotEmpty()) {
                logger.info(
                    TAG,
                    "provider offline delete provider=$providerId rows=${rows.size} files=$filesRemoved",
                )
            }
            OfflineProviderDeleteCounts(rows = rows.size, filesRemoved = filesRemoved)
        }

    private suspend fun cancelEquivalent(rowId: Long): Boolean =
        cancellation.cancel(rowId) || db.downloadDao().deleteById(rowId) == 1

    private suspend fun deleteStoredFile(relativePath: String): Boolean =
        try {
            val file = storage.resolveStoredPath(relativePath)
            file != null && file.isFile && file.delete()
        } catch (_: Exception) {
            false
        }

    private companion object {
        const val TAG = "OfflineDelete"
    }
}
