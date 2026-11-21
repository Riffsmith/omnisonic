package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.model.log.OmnisonicLogger
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one pre-claim integrity sweep changed; every count is an applied deletion. */
data class OfflineSweepCounts(
    val missingRows: Int = 0,
    val zeroRows: Int = 0,
    val staleParts: Int = 0,
    val staleStateFiles: Int = 0,
)

/**
 * Pre-claim offline integrity sweep (Phase 7 task 5b). It runs after the
 * task-5a orphan pass on the runtime's single recovery hook, while no lease is
 * live, so no transfer can be writing the artifacts it examines.
 *
 * - An `offline_media` row whose stored file is missing or zero-length is not
 *   playable, so the row is removed (a zero-length file is deleted too).
 * - A `.part` is stale only when its sidecar no longer names a queue row: a
 *   paused, failed or pending row keeps its resumable bytes, while cancelled
 *   and provider-deleted work leaves provably unclaimable artifacts.
 * - A `.part.state` without its part, and a half-written `.part.state.tmp`,
 *   are stale because no attempt can resume from them.
 * - An unavailable root, or a root whose media tree is absent, skips the whole
 *   pass: "missing" is not provable there and an empty library is never assumed.
 * - Only the active root's media tree is examined, so another provider's rows
 *   and another volume's files are never touched.
 *
 * Final media files are deliberately left to the task-5a adoption/quarantine
 * protocol; this sweep never deletes one. It owns no transfer and no queue
 * mutation.
 */
class OfflineFileSweep(
    private val db: OmnisonicDatabase,
    private val storage: OfflineStorage,
    private val logger: OmnisonicLogger,
    private val journal: DownloadTransferJournal = DownloadTransferJournal(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun sweep(): OfflineSweepCounts = withContext(ioDispatcher) {
        val root = when (val resolution = storage.activeRoot()) {
            is OfflineRootResolution.Available -> resolution.root
            is OfflineRootResolution.Unavailable -> return@withContext OfflineSweepCounts()
        }
        val mediaDir = storage.mediaDir(root)
        if (!mediaDir.isDirectory) return@withContext OfflineSweepCounts()
        val rows = sweepRows(root.directory)
        val queueKeys = db.downloadDao().selectAllRaw("").mapTo(HashSet()) { it.mediaKey }
        val artifacts = sweepStaleArtifacts(mediaDir, queueKeys)
        val counts = OfflineSweepCounts(
            missingRows = rows.missing,
            zeroRows = rows.zero,
            staleParts = artifacts.parts,
            staleStateFiles = artifacts.stateFiles,
        )
        if (counts != OfflineSweepCounts()) {
            logger.info(
                TAG,
                "offline sweep missing=${counts.missingRows} zero=${counts.zeroRows} " +
                    "parts=${counts.staleParts} state=${counts.staleStateFiles}",
            )
        }
        counts
    }

    private data class RowSweep(val missing: Int, val zero: Int)

    private data class ArtifactSweep(val parts: Int, val stateFiles: Int)

    private suspend fun sweepRows(root: File): RowSweep {
        var missing = 0
        var zero = 0
        for (row in db.offlineFileDao().selectAll()) {
            val file = OfflinePathRules.resolveUnder(root, row.offlinePath)
            when {
                file == null || !file.isFile -> {
                    db.offlineFileDao().deleteById(row.id)
                    missing++
                }
                file.length() == 0L -> {
                    file.delete()
                    db.offlineFileDao().deleteById(row.id)
                    zero++
                }
            }
        }
        return RowSweep(missing, zero)
    }

    private fun sweepStaleArtifacts(mediaDir: File, queueKeys: Set<String>): ArtifactSweep {
        var staleParts = 0
        var staleStateFiles = 0
        for (file in filesUnder(mediaDir)) {
            val name = file.name
            when {
                name.endsWith(STATE_WRITE_TEMP_SUFFIX) -> {
                    if (file.delete()) staleStateFiles++
                }
                name.endsWith(OfflinePathRules.PART_SUFFIX) -> {
                    val ownerKey = journal.read(file)?.mediaKey
                    if (ownerKey == null || ownerKey !in queueKeys) {
                        file.delete()
                        journal.delete(file)
                        staleParts++
                    }
                }
                name.endsWith(DownloadTransferRules.PART_STATE_SUFFIX) -> {
                    val part = File(file.parentFile, name.removeSuffix(DownloadTransferRules.PART_STATE_SUFFIX))
                    if (!part.isFile && file.delete()) staleStateFiles++
                }
            }
        }
        return ArtifactSweep(staleParts, staleStateFiles)
    }

    private fun filesUnder(mediaDir: File): List<File> {
        val result = mutableListOf<File>()
        fun visit(directory: File) {
            val children = directory.listFiles() ?: return
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) {
                    visit(child)
                    continue
                }
                if (child.isFile) result += child
            }
        }
        visit(mediaDir)
        return result
    }

    private companion object {
        const val TAG = "OfflineSweep"
        const val STATE_WRITE_TEMP_SUFFIX = "${DownloadTransferRules.PART_STATE_SUFFIX}.tmp"
    }
}
