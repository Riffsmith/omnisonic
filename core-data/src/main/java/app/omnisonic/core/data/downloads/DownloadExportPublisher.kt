package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.model.log.OmnisonicLogger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** An already-published offline file that can be exported without a network round-trip. */
data class LocalExportSource(
    val file: File,
    val extension: String,
)

/** How one public export attempt ended; only [Published] means the queue row was settled. */
sealed interface DownloadExportOutcome {

    data class Published(
        val documentUri: String,
        val sizeBytes: Long,
        val reusedDocument: Boolean,
    ) : DownloadExportOutcome

    /** Counted failure; every recorded document and session was cleaned up. */
    data object Retry : DownloadExportOutcome

    /** The resolver refused the operation; nothing visible was created. */
    data object Unavailable : DownloadExportOutcome

    /** Pause/cancel/newer lease settled the row while the copy ran; the document was removed. */
    data object RowSettledElsewhere : DownloadExportOutcome

    data object LeaseLost : DownloadExportOutcome
}

/** One pre-claim export-recovery pass; every count is an applied cleanup or settlement. */
data class ExportRecoveryCounts(
    val cleanedPending: Int = 0,
    val settledPublished: Int = 0,
    val lostDocuments: Int = 0,
    val abandonedSessions: Int = 0,
)

/**
 * Public export publication (Phase 7 tasks 6a/6b). The transfer keeps its task-3
 * resumable `.part`; this type copies that complete file (or an existing
 * published offline file) into a **pending** public document with truncation,
 * verifies the copy by read-back checksum, clears pending, and only then deletes
 * the queue row. So a user never sees a partial file, and a failed or cancelled
 * attempt removes the document it created.
 *
 * Idempotence comes from [ExportSessionJournal]: a retry reuses the recorded
 * pending document instead of inserting a second one, a crash before clearing
 * pending is cleaned as invisible, and a crash after clearing (journal phase
 * `PUBLISHED`) settles the row instead of exporting again. Recovery only ever
 * touches a recorded `content://media/.../downloads/<id>` URI.
 */
class DownloadExportPublisher(
    private val queues: DownloadQueueRepository,
    private val offline: OfflineMediaRepository,
    private val storage: OfflineStorage,
    private val store: ExportDocumentStore,
    private val sessions: ExportSessionJournal,
    private val logger: OmnisonicLogger,
    private val journal: DownloadTransferJournal = DownloadTransferJournal(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * A published offline file for this stream key is a verified copy already, so
     * an export request is a copy — not a second download — and the offline file
     * itself is never written, moved or deleted here.
     */
    suspend fun localExportSource(item: DownloadItemEntity): LocalExportSource? = withContext(ioDispatcher) {
        try {
            val row = offline.selectBySourceKey(item.mediaKey) ?: return@withContext null
            val file = storage.resolveStoredPath(row.offlinePath) ?: return@withContext null
            if (!file.isFile || file.length() <= 0L) return@withContext null
            row.sizeBytes?.let { if (it != file.length()) return@withContext null }
            val extension = OfflinePathRules.sourceExtension(row.offlinePath) ?: OfflinePathRules.FALLBACK_EXTENSION
            LocalExportSource(file, extension)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun publish(
        coordinator: DownloadWorkerCoordinator,
        lease: DownloadLease,
        item: DownloadItemEntity,
        extension: String,
        staged: File?,
        source: File,
    ): DownloadExportOutcome = withContext(ioDispatcher) {
        var cleanupRef: ExportDocumentRef? = null
        try {
        val size = source.length()
        if (size <= 0L) return@withContext DownloadExportOutcome.Retry
        val request = ExportNaming.request(item.mediaKey, item.title, extension)
        val existing = sessions.read(item.mediaKey)
        if (existing != null && existing.phase == ExportSessionPhase.PUBLISHED) {
            return@withContext finishPublishedSession(coordinator, lease, item, existing, staged)
        }

        var pendingSession = existing
        if (pendingSession != null) {
            val ref = ExportDocumentRef(pendingSession.uri)
            cleanupRef = ref
            when (presence(ref)) {
                ExportDocumentPresence.UNKNOWN -> return@withContext DownloadExportOutcome.Unavailable
                ExportDocumentPresence.MISSING -> {
                    sessions.delete(item.mediaKey)
                    pendingSession = null
                    cleanupRef = null
                }
                ExportDocumentPresence.VISIBLE -> {
                    if (!deleteDocument(ref)) return@withContext DownloadExportOutcome.Retry
                    sessions.delete(item.mediaKey)
                    pendingSession = null
                    cleanupRef = null
                }
                ExportDocumentPresence.PENDING -> Unit
            }
        }
        val reused = pendingSession != null
        val ref = if (pendingSession != null) {
            val recorded = pendingSession
            if (!isOwnedExportDocumentUri(recorded.uri)) {
                sessions.delete(item.mediaKey)
                return@withContext DownloadExportOutcome.Retry
            }
            val resumed = recorded.copy(expectedBytes = size, phase = ExportSessionPhase.PENDING)
            if (!sessions.write(resumed)) {
                return@withContext abandon(ExportDocumentRef(recorded.uri), item.mediaKey, DownloadExportOutcome.Retry)
            }
            ExportDocumentRef(recorded.uri)
        } else {
            val created = try {
                store.insertPending(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@withContext DownloadExportOutcome.Unavailable
            cleanupRef = created
            val session = ExportSession(
                mediaKey = item.mediaKey,
                uri = created.uri,
                displayName = request.displayName,
                mimeType = request.mimeType,
                expectedBytes = size,
                phase = ExportSessionPhase.PENDING,
            )
            if (!sessions.write(session)) {
                return@withContext abandon(created, item.mediaKey, DownloadExportOutcome.Retry)
            }
            created
        }
        cleanupRef = ref

        val copy = try {
            store.writeTruncating(ref, source)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ExportCopyOutcome.WriteFailed
        }
        if (copy !is ExportCopyOutcome.Copied || copy.bytes != size) {
            logger.warn(TAG, "export row ${lease.rowId} copy rejected")
            return@withContext abandon(ref, item.mediaKey, DownloadExportOutcome.Retry)
        }

        if (!stillOwned(coordinator, lease)) {
            return@withContext abandon(ref, item.mediaKey, DownloadExportOutcome.RowSettledElsewhere)
        }

        val cleared = try {
            store.clearPending(ref)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!cleared) {
            return@withContext abandon(ref, item.mediaKey, DownloadExportOutcome.Retry)
        }

        val published = ExportSession(
            mediaKey = item.mediaKey,
            uri = ref.uri,
            displayName = pendingSession?.displayName ?: request.displayName,
            mimeType = pendingSession?.mimeType ?: request.mimeType,
            expectedBytes = size,
            phase = ExportSessionPhase.PUBLISHED,
        )
        if (!sessions.write(published)) {
            return@withContext abandon(ref, item.mediaKey, DownloadExportOutcome.Retry)
        }

        when (withContext(NonCancellable) { settle(coordinator, lease) }) {
            CompletionSettlement.Applied -> {
                cleanupRef = null
                sessions.delete(item.mediaKey)
                discardStaged(staged)
                logger.info(TAG, "export row ${lease.rowId} published $size bytes")
                DownloadExportOutcome.Published(ref.uri, size, reusedDocument = reused)
            }
            CompletionSettlement.LeaseStale ->
                abandon(ref, item.mediaKey, DownloadExportOutcome.LeaseLost)
            CompletionSettlement.Failed ->
                abandon(ref, item.mediaKey, DownloadExportOutcome.Retry)
            CompletionSettlement.RowAlreadySettled ->
                abandon(ref, item.mediaKey, DownloadExportOutcome.RowSettledElsewhere)
        }
        } catch (cancelled: CancellationException) {
            cleanupRef?.let { ref ->
                withContext(NonCancellable) {
                    abandon(ref, item.mediaKey, DownloadExportOutcome.RowSettledElsewhere)
                }
            }
            throw cancelled
        }
    }

    /**
     * A `PUBLISHED` session means the document was already complete and visible
     * when the process died before the row deletion. The row is settled and no
     * second document is created; a vanished document drops the session and lets
     * the attempt export again.
     */
    private suspend fun finishPublishedSession(
        coordinator: DownloadWorkerCoordinator,
        lease: DownloadLease,
        item: DownloadItemEntity,
        session: ExportSession,
        staged: File?,
    ): DownloadExportOutcome {
        val ref = ExportDocumentRef(session.uri)
        when (presence(ref)) {
            ExportDocumentPresence.UNKNOWN -> return DownloadExportOutcome.Unavailable
            ExportDocumentPresence.MISSING -> {
                sessions.delete(item.mediaKey)
                logger.warn(TAG, "export row ${lease.rowId} recorded document is gone")
                return DownloadExportOutcome.Retry
            }
            ExportDocumentPresence.PENDING -> return abandon(ref, item.mediaKey, DownloadExportOutcome.Retry)
            ExportDocumentPresence.VISIBLE -> Unit
        }
        return when (withContext(NonCancellable) { settle(coordinator, lease) }) {
            CompletionSettlement.Applied -> {
                sessions.delete(item.mediaKey)
                discardStaged(staged)
                DownloadExportOutcome.Published(session.uri, session.expectedBytes, reusedDocument = true)
            }
            CompletionSettlement.LeaseStale -> DownloadExportOutcome.LeaseLost
            CompletionSettlement.Failed -> DownloadExportOutcome.Retry
            CompletionSettlement.RowAlreadySettled -> DownloadExportOutcome.RowSettledElsewhere
        }
    }

    /**
     * Pre-claim recovery on the runtime's single hook, before claims and before
     * the task-5 orphan pass. It never touches a document the app did not record
     * and never deletes a published artifact: a `PENDING` document is removed,
     * and a `PUBLISHED` one settles its still-present queue row idempotently.
     */
    suspend fun recoverSessions(): ExportRecoveryCounts = withContext(ioDispatcher) {
        var cleanedPending = 0
        var settledPublished = 0
        var lostDocuments = 0
        var abandonedSessions = 0
        for (file in sessions.unreadable()) {
            if (file.delete()) abandonedSessions++
            logger.warn(TAG, "export recovery dropped an unreadable session file")
        }
        for (session in sessions.selectAll()) {
            val ref = ExportDocumentRef(session.uri)
            when (session.phase) {
                ExportSessionPhase.PENDING -> {
                    val removed = deleteDocument(ref)
                    when (presence(ref)) {
                        ExportDocumentPresence.MISSING -> {
                            sessions.delete(session.mediaKey)
                            cleanedPending++
                        }
                        ExportDocumentPresence.UNKNOWN -> throw ExportRecoveryException()
                        ExportDocumentPresence.PENDING,
                        ExportDocumentPresence.VISIBLE,
                        -> {
                            if (removed) {
                                sessions.delete(session.mediaKey)
                                cleanedPending++
                            } else {
                                logger.warn(TAG, "export recovery cannot remove a pending document yet")
                            }
                        }
                    }
                }
                ExportSessionPhase.PUBLISHED -> {
                    when (presence(ref)) {
                        ExportDocumentPresence.UNKNOWN -> throw ExportRecoveryException()
                        ExportDocumentPresence.MISSING -> {
                            sessions.delete(session.mediaKey)
                            lostDocuments++
                            logger.warn(TAG, "export recovery found a published document missing")
                        }
                        ExportDocumentPresence.PENDING -> {
                            if (deleteDocument(ref)) {
                                sessions.delete(session.mediaKey)
                                cleanedPending++
                            }
                        }
                        ExportDocumentPresence.VISIBLE -> {
                            val row = queues.selectByMediaKey(session.mediaKey)
                            if (row == null || row.exportTarget == null) {
                                sessions.delete(session.mediaKey)
                                if (row != null) abandonedSessions++
                                continue
                            }
                            if (queues.cancel(row.id)) {
                                sessions.delete(session.mediaKey)
                                settledPublished++
                            } else {
                                logger.warn(TAG, "export recovery could not settle a published row")
                            }
                        }
                    }
                }
            }
        }
        for (row in queues.selectVisible(ProfileScope.Unrestricted)) {
            if (row.exportTarget == null || sessions.read(row.mediaKey) != null) continue
            val matches = try {
                store.pendingMatches(row.mediaKey, row.title)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: throw ExportRecoveryException()
            if (matches.isEmpty()) continue
            if (matches.size != 1) throw ExportRecoveryException()
            if (!deleteDocument(matches.single().ref)) throw ExportRecoveryException()
            cleanedPending++
        }
        if (cleanedPending + settledPublished + lostDocuments + abandonedSessions > 0) {
            logger.info(
                TAG,
                "export recovery pending=$cleanedPending published=$settledPublished " +
                    "lost=$lostDocuments abandoned=$abandonedSessions",
            )
        }
        ExportRecoveryCounts(cleanedPending, settledPublished, lostDocuments, abandonedSessions)
    }

    private suspend fun settle(
        coordinator: DownloadWorkerCoordinator,
        lease: DownloadLease,
    ): CompletionSettlement = coordinator.completeWith(lease) { rowId ->
        if (queues.deleteIfActive(rowId)) PublishSettle.APPLIED else PublishSettle.ROW_NOT_ACTIVE
    }

    private suspend fun stillOwned(
        coordinator: DownloadWorkerCoordinator,
        lease: DownloadLease,
    ): Boolean {
        val signal = coordinator.interruptionFor(lease)
        val interrupted = signal.isCompleted &&
            runCatching { signal.getCompleted() }.getOrNull() != null
        if (interrupted) return false
        return queues.statusOf(lease.rowId) == DownloadStatus.ACTIVE.name
    }

    private suspend fun abandon(
        ref: ExportDocumentRef,
        mediaKey: String,
        outcome: DownloadExportOutcome,
    ): DownloadExportOutcome {
        if (deleteDocument(ref)) {
            sessions.delete(mediaKey)
        } else {
            logger.warn(TAG, "export cleanup could not remove the recorded document")
        }
        return outcome
    }

    private suspend fun deleteDocument(ref: ExportDocumentRef): Boolean = try {
        store.delete(ref)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun presence(ref: ExportDocumentRef): ExportDocumentPresence = try {
        store.presence(ref)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ExportDocumentPresence.UNKNOWN
    }

    private fun discardStaged(staged: File?) {
        if (staged == null) return
        staged.delete()
        journal.delete(staged)
    }

    private companion object {
        const val TAG = "ExportPublish"
    }
}

private class ExportRecoveryException : Exception()
