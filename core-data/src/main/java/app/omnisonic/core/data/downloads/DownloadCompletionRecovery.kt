package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.model.log.OmnisonicLogger
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of one pre-claim orphan pass: adopted publications and quarantined/deleted orphans. */
data class DownloadRecoveryCounts(val adopted: Int, val discarded: Int)

/**
 * Pre-claim crash recovery for the task-5a publication protocol (D-43). A
 * process death, pause or cancel between the final rename and the publication
 * commit leaves a complete file whose sidecar still carries the variant hash,
 * the expected length and the owning stream key. Only an orphan whose sidecar
 * verifies its exact length is adopted — `offline_media` publishes and the
 * queue row deletes in one transaction. Anything unverifiable, cancelled or
 * unsettleable is quarantined/deleted so a surviving queue row retries safely;
 * an already-published file is never touched. The general missing-file sweep,
 * stale `.part` cleanup and unavailable-root policy belong to task 5b.
 */
class DownloadCompletionRecovery(
    private val db: OmnisonicDatabase,
    private val storage: OfflineStorage,
    private val publisher: OfflineMediaRepository,
    private val logger: OmnisonicLogger,
    private val journal: DownloadTransferJournal = DownloadTransferJournal(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun recoverOrphanedFinalFiles(): DownloadRecoveryCounts = withContext(ioDispatcher) {
        val root = when (val resolution = storage.activeRoot()) {
            is OfflineRootResolution.Available -> resolution.root
            is OfflineRootResolution.Unavailable -> return@withContext DownloadRecoveryCounts(0, 0)
        }
        val mediaDir = storage.mediaDir(root)
        if (!mediaDir.isDirectory) return@withContext DownloadRecoveryCounts(0, 0)
        var adopted = 0
        var discarded = 0
        for (file in finalCandidates(mediaDir)) {
            val relative = file.relativeTo(root.directory).invariantSeparatorsPath
            if (!OfflinePathRules.isSafeRelativePath(relative)) continue
            if (db.offlineFileDao().selectIdByOfflinePath(relative) != null) continue
            val hash = file.name.substringBeforeLast('.', file.name)
            val part = File(file.parentFile, "$hash${OfflinePathRules.PART_SUFFIX}")
            val state = journal.read(part)
            val verified = state != null &&
                state.identityHash == hash &&
                state.totalBytes == file.length() &&
                file.length() > 0L
            val row = if (verified) state!!.mediaKey?.let { db.downloadDao().selectByMediaKey(it) } else null
            // An export-owned row publishes a public document, never offline media.
            if (verified && row != null && row.exportTarget == null) {
                val offline = offlinePublicationRow(row, relative, file.length(), clock())
                if (publisher.publishAndSettleRecovered(row.id, offline)) {
                    part.delete()
                    journal.delete(part)
                    adopted++
                    continue
                }
            }
            file.delete()
            part.delete()
            journal.delete(part)
            discarded++
        }
        if (adopted > 0 || discarded > 0) {
            logger.info(TAG, "orphan recovery adopted=$adopted discarded=$discarded")
        }
        DownloadRecoveryCounts(adopted, discarded)
    }

    private fun finalCandidates(mediaDir: File): List<File> {
        val result = mutableListOf<File>()
        fun visit(directory: File) {
            val children = directory.listFiles() ?: return
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) {
                    visit(child)
                    continue
                }
                if (!child.isFile) continue
                val name = child.name
                if (name == OfflinePathRules.NOMEDIA_FILE_NAME) continue
                if (name.endsWith(OfflinePathRules.PART_SUFFIX)) continue
                if (name.endsWith(DownloadTransferRules.PART_STATE_SUFFIX)) continue
                if (name.endsWith(".tmp") || name.endsWith(".moving")) continue
                result += child
            }
        }
        visit(mediaDir)
        return result
    }

    private companion object {
        const val TAG = "DownloadRecovery"
    }
}
