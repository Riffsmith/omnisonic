package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.model.log.OmnisonicLogger
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

sealed interface DownloadTransferResult {

    /** The `.part` became the permanent file; the queue row is settled. */
    data class Completed(val relativePath: String, val sizeBytes: Long) : DownloadTransferResult

    /** Pause signal: `.part` and its sidecar survive for the next attempt. */
    data class Paused(val preservedBytes: Long) : DownloadTransferResult

    /** Explicit cancel: the `.part` and sidecar are gone. */
    data object Cancelled : DownloadTransferResult

    /** Counted failure; [status] is the settled queue status (null when the lease was stale). */
    data class Retry(val failure: DownloadTransferFailure, val status: DownloadStatus?) : DownloadTransferResult

    /** Uncounted gate block (storage/auth); the row returns to the gate state. */
    data class Blocked(val block: DownloadTransferBlock) : DownloadTransferResult

    /** Uncounted terminal park: a permanent condition must not spin a retry loop. */
    data class Parked(val reason: DownloadTransferPark) : DownloadTransferResult

    /** Another command (pause/cancel/failure/newer lease) already settled the row. */
    data object LeaseLost : DownloadTransferResult

    /** The staged bytes were published as a public document and the queue row is settled. */
    data class Exported(val documentUri: String, val sizeBytes: Long) : DownloadTransferResult
}

/**
 * One resumable HTTP attempt pipeline for a claimed row (Phase 7 task 3). It
 * resolves a fresh download URL through the provider-media seam, sends
 * `Accept-Encoding: identity`, appends only a `If-Range`-validated 206, and
 * writes into the variant-owned extensionless `.part` beside its persisted
 * validator/length sidecar. Progress is conditional on the still-active row and
 * throttled; pause/cancel cancel the call and settle through the task-1
 * conditional writes without counting an error.
 *
 * The engine owns bytes and completion publication. After the complete-size
 * check and non-replacing rename it publishes `offline_media` and deletes the
 * queue row in one transaction (Phase 7 task 5a); the sidecar's verification
 * state survives the rename until that commit so a crash leaves an adoptable
 * orphan, never an unverified published file. An export row never reaches the
 * app-private final path: its complete `.part` (or an already-published offline
 * file) is handed to [DownloadExportPublisher], which publishes a public
 * document and only then settles the row (Phase 7 task 6).
 */
class DownloadTransferEngine(
    private val repository: DownloadQueueRepository,
    private val coordinator: DownloadWorkerCoordinator,
    private val publisher: OfflineMediaRepository,
    private val storage: OfflineStorage,
    private val media: DownloadMediaSource,
    private val auth: DownloadAuthReporting,
    private val logger: OmnisonicLogger,
    private val journal: DownloadTransferJournal = DownloadTransferJournal(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val exporter: DownloadExportPublisher? = null,
) {

    private val finalizeGuard = Mutex()
    private val finalizeLocks = HashMap<String, Mutex>()

    suspend fun transfer(lease: DownloadLease): DownloadTransferResult = try { withContext(ioDispatcher) {
        val item = repository.selectById(lease.rowId)
            ?: return@withContext settleLeaseLost(lease)
        if (item.providerId != lease.providerId) return@withContext settleLeaseLost(lease)
        val key = DownloadKeys.parseMediaKey(item.mediaKey)
            ?: return@withContext settlePark(lease, DownloadTransferPark.QUEUE_KEY_INVALID)
        if (key.providerId != item.providerId) {
            return@withContext settlePark(lease, DownloadTransferPark.QUEUE_KEY_INVALID)
        }
        val prepared = prepareStorage(0L)
        if (prepared is OfflineStorageState.Blocked) {
            return@withContext settleBlock(lease, prepared.toTransferBlock())
        }

        if (item.exportTarget != null) {
            val exportPublisher = exporter
                ?: return@withContext settlePark(lease, DownloadTransferPark.EXPORT_UNAVAILABLE)
            exportPublisher.localExportSource(item)?.let { local ->
                return@withContext settleExport(
                    lease = lease,
                    item = item,
                    extension = local.extension,
                    staged = null,
                    source = local.file,
                    publisher = exportPublisher,
                )
            }
        }

        val endpoints = when (
            val resolution = media.resolve(
                DownloadMediaRequest(
                    providerId = key.providerId,
                    externalId = key.externalId,
                    transcodeBitrateKbps = key.transcodeBitrateKbps,
                    format = key.format,
                ),
            )
        ) {
            is DownloadMediaResolution.Available -> resolution
            DownloadMediaResolution.ProviderMissing ->
                return@withContext settlePark(lease, DownloadTransferPark.PROVIDER_MISSING)
            DownloadMediaResolution.DownloadUnsupported ->
                return@withContext settlePark(lease, DownloadTransferPark.DOWNLOAD_UNSUPPORTED)
            DownloadMediaResolution.MediaPathMissing ->
                return@withContext settlePark(lease, DownloadTransferPark.MEDIA_PATH_MISSING)
            DownloadMediaResolution.MediaPathTransport ->
                return@withContext settleBlock(lease, DownloadTransferBlock.NETWORK_UNAVAILABLE)
            DownloadMediaResolution.MediaPathRejected ->
                return@withContext settleRetry(lease, DownloadTransferFailure.HTTP_STATUS)
            DownloadMediaResolution.EndpointMissing ->
                return@withContext settlePark(lease, DownloadTransferPark.DOWNLOAD_UNSUPPORTED)
            DownloadMediaResolution.CredentialsUnavailable -> {
                auth.authRejected(lease.providerId)
                return@withContext settleBlock(lease, DownloadTransferBlock.AUTH_REJECTED)
            }
        }

        val identity = OfflineMediaIdentity(
            providerId = key.providerId,
            providerPath = endpoints.providerPath,
            transcodeBitrateKbps = key.transcodeBitrateKbps,
            format = key.format,
        )
        val plan = when (val planned = storage.planPath(identity)) {
            is OfflinePathResult.Planned -> planned.plan
            is OfflinePathResult.Collision,
            is OfflinePathResult.InvalidIdentity,
            -> return@withContext settlePark(lease, DownloadTransferPark.PATH_UNPLANNABLE)
        }

        val partFile = resolveStoredPath(plan.relativePartPath)
            ?: return@withContext settleBlock(lease, DownloadTransferBlock.STORAGE_UNAVAILABLE)
        val finalFile = resolveStoredPath(plan.relativePath)
            ?: return@withContext settleBlock(lease, DownloadTransferBlock.STORAGE_UNAVAILABLE)

        if (finalFile.isFile && item.exportTarget == null) {
            resolveExistingFinal(lease, item, plan, partFile, finalFile)?.let { return@withContext it }
        }

        var state = journal.read(partFile)
        if (state != null && state.identityHash != plan.hash) {
            discard(partFile)
            state = null
        }
        if (partFile.isFile && partFile.length() == 0L) {
            journal.delete(partFile)
            state = null
        }
        if (!partFile.isFile) {
            journal.delete(partFile)
            state = null
        }
        var offset = if (partFile.isFile) partFile.length() else 0L
        if (offset > 0L && (state == null || state.ifRangeValidator() == null)) {
            discard(partFile)
            state = null
            offset = 0L
        }
        val storedTotal = state?.totalBytes
        if (storedTotal != null && (offset > storedTotal || storedTotal <= 0L)) {
            discard(partFile)
            state = null
            offset = 0L
        }
        val remainingBytes = storedTotal?.let { (it - offset).coerceAtLeast(0L) } ?: 0L
        if (remainingBytes > 0L) {
            val spaced = prepareStorage(remainingBytes)
            if (spaced is OfflineStorageState.Blocked) {
                return@withContext settleBlock(lease, spaced.toTransferBlock())
            }
        }

        val interruption = coordinator.interruptionFor(lease)
        interruption.completedOrNull()?.let { signal ->
            return@withContext settleInterruption(lease, signal, partFile)
        }

        val throttle = DownloadProgressThrottle()
        val initialPercent = DownloadTransferRules.progressPercent(offset, state?.totalBytes)
        if (throttle.shouldEmit(initialPercent, clock())) coordinator.reportProgress(lease, initialPercent)

        val completeTotal = state?.totalBytes
        if (offset > 0L && completeTotal != null && offset == completeTotal) {
            return@withContext finalize(lease, item, plan, partFile, finalFile, state)
        }

        var endpointIndex = 0
        var restarts = 1
        while (endpointIndex < endpoints.endpoints.size) {
            interruption.completedOrNull()?.let { signal ->
                return@withContext settleInterruption(lease, signal, partFile)
            }
            val endpoint = endpoints.endpoints[endpointIndex]
            when (
                val attempt = attempt(
                    endpoint = endpoint,
                    partFile = partFile,
                    offset = offset,
                    state = state,
                    identityHash = plan.hash,
                    mediaKey = item.mediaKey,
                    lease = lease,
                    interruption = interruption,
                    throttle = throttle,
                )
            ) {
                is AttemptResult.BodyWritten -> {
                    interruption.completedOrNull()?.let { signal ->
                        return@withContext settleInterruption(lease, signal, partFile)
                    }
                    return@withContext finalize(lease, item, plan, partFile, finalFile, attempt.state)
                }
                is AttemptResult.RestartRequired -> {
                    if (restarts <= 0) {
                        return@withContext settleRetry(lease, attempt.reason)
                    }
                    restarts--
                    discard(partFile)
                    coordinator.reportProgress(lease, 0)
                    state = null
                    offset = 0L
                }
                is AttemptResult.Deferred ->
                    return@withContext settleBlock(lease, attempt.block)
                is AttemptResult.Interrupted ->
                    return@withContext settleInterruption(lease, attempt.interruption, partFile)
                is AttemptResult.Failed ->
                    return@withContext settleRetry(lease, attempt.failure)
                AttemptResult.Transport -> {
                    state = journal.read(partFile)
                    offset = if (partFile.isFile) partFile.length() else 0L
                    endpointIndex++
                }
            }
        }
        settleBlock(lease, DownloadTransferBlock.NETWORK_UNAVAILABLE)
    } } catch (cancelled: CancellationException) {
        coordinator.release(lease)
        throw cancelled
    }

    private suspend fun attempt(
        endpoint: DownloadMediaEndpoint,
        partFile: File,
        offset: Long,
        state: DownloadTransferState?,
        identityHash: String,
        mediaKey: String,
        lease: DownloadLease,
        interruption: CompletableDeferred<DownloadInterruption?>,
        throttle: DownloadProgressThrottle,
    ): AttemptResult = coroutineScope {
        interruption.completedOrNull()?.let { return@coroutineScope AttemptResult.Interrupted(it) }
        val cancellation = TransferCancellation()
        val watcher = launch {
            val signal = interruption.await() ?: return@launch
            cancellation.signal(signal)
        }
        try {
            performRequest(
                endpoint = endpoint,
                partFile = partFile,
                offset = offset,
                state = state,
                identityHash = identityHash,
                mediaKey = mediaKey,
                lease = lease,
                throttle = throttle,
                cancellation = cancellation,
                interruption = interruption,
            )
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun performRequest(
        endpoint: DownloadMediaEndpoint,
        partFile: File,
        offset: Long,
        state: DownloadTransferState?,
        identityHash: String,
        mediaKey: String,
        lease: DownloadLease,
        throttle: DownloadProgressThrottle,
        cancellation: TransferCancellation,
        interruption: CompletableDeferred<DownloadInterruption?>,
    ): AttemptResult {
        val validator = if (offset > 0L) state?.ifRangeValidator() else null
        val builder = Request.Builder()
            .url(endpoint.url)
            .header(ACCEPT_ENCODING, IDENTITY_ENCODING)
        if (offset > 0L && validator != null) {
            builder.header(RANGE, "bytes=$offset-")
            builder.header(IF_RANGE, validator)
        }
        val call = endpoint.client.newCall(builder.build())
        cancellation.attach(call)
        val response = try {
            call.awaitResponse()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            return cancellation.peek()?.let { signal -> AttemptResult.Interrupted(signal) }
                ?: AttemptResult.Transport
        }
        response.use { handled ->
            cancellation.peek()?.let { signal -> return AttemptResult.Interrupted(signal) }
            val code = handled.code
            if (code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN) {
                auth.authRejected(lease.providerId)
                return AttemptResult.Deferred(DownloadTransferBlock.AUTH_REJECTED)
            }
            if (code == HTTP_RANGE_NOT_SATISFIABLE) {
                return AttemptResult.RestartRequired(DownloadTransferFailure.RANGE_UNSATISFIABLE)
            }
            if (code != HTTP_OK && code != HTTP_PARTIAL_CONTENT) {
                return AttemptResult.Failed(DownloadTransferFailure.HTTP_STATUS)
            }
            if (!DownloadTransferRules.isIdentityEncoding(handled.header(CONTENT_ENCODING))) {
                discard(partFile)
                coordinator.reportProgress(lease, 0)
                return AttemptResult.Failed(DownloadTransferFailure.CONTENT_ENCODING)
            }
            if (DownloadTransferRules.rejectsContentType(handled.header(CONTENT_TYPE))) {
                discard(partFile)
                coordinator.reportProgress(lease, 0)
                return AttemptResult.Failed(DownloadTransferFailure.PROTOCOL_PAYLOAD)
            }
            val body = handled.body ?: return AttemptResult.Failed(DownloadTransferFailure.EMPTY_BODY)
            val contentLength = body.contentLength()
            val range = if (code == HTTP_PARTIAL_CONTENT) {
                DownloadTransferRules.parseContentRange(handled.header(CONTENT_RANGE))
                    ?: return AttemptResult.RestartRequired(DownloadTransferFailure.RANGE_MISMATCH)
            } else {
                null
            }
            val start = range?.start ?: 0L
            val bodyLength = range?.let { it.end - it.start + 1 } ?: contentLength
            if (range != null) {
                if (range.start != offset) {
                    return AttemptResult.RestartRequired(DownloadTransferFailure.RANGE_MISMATCH)
                }
                if (contentLength >= 0L && contentLength != bodyLength) {
                    return AttemptResult.RestartRequired(DownloadTransferFailure.RECORD_MISMATCH)
                }
            }
            val knownTotal = state?.totalBytes
            if (range != null &&
                ((range.totalBytes != null && knownTotal != null && range.totalBytes != knownTotal) ||
                    (range.totalBytes == null && knownTotal != null))
            ) {
                return AttemptResult.RestartRequired(DownloadTransferFailure.RECORD_MISMATCH)
            }
            val total = when {
                range?.totalBytes != null -> range.totalBytes
                range != null -> knownTotal
                contentLength >= 0L -> contentLength
                else -> null
            }
            if (total != null && bodyLength >= 0L && start > total - bodyLength) {
                return AttemptResult.RestartRequired(DownloadTransferFailure.RECORD_MISMATCH)
            }
            if (total != null) {
                val spaced = prepareStorage((total - start).coerceAtLeast(0L))
                if (spaced is OfflineStorageState.Blocked) {
                    return AttemptResult.Deferred(spaced.toTransferBlock())
                }
            }

            if (code == HTTP_PARTIAL_CONTENT && offset > 0L && validator == null) {
                return AttemptResult.RestartRequired(DownloadTransferFailure.RANGE_MISMATCH)
            }
            if (code == HTTP_PARTIAL_CONTENT && state?.strongEtag != null) {
                val responseTag = handled.header(ETAG)
                if (responseTag != null && DownloadTransferRules.strongEtag(responseTag) != state.strongEtag) {
                    return AttemptResult.RestartRequired(DownloadTransferFailure.RANGE_MISMATCH)
                }
            }
            if (code == HTTP_PARTIAL_CONTENT && state?.strongEtag == null && state?.lastModified != null) {
                val responseDate = handled.header(LAST_MODIFIED)
                if (responseDate != null && DownloadTransferRules.headerDate(responseDate) != state.lastModified) {
                    return AttemptResult.RestartRequired(DownloadTransferFailure.RANGE_MISMATCH)
                }
            }
            val strongEtag = DownloadTransferRules.strongEtag(handled.header(ETAG))
            val lastModified = DownloadTransferRules.headerDate(handled.header(LAST_MODIFIED))
            val nextState = if (code == HTTP_OK) {
                DownloadTransferState(identityHash, strongEtag, lastModified, total, mediaKey)
            } else {
                DownloadTransferState(
                    identityHash = identityHash,
                    strongEtag = strongEtag ?: state?.strongEtag,
                    lastModified = lastModified ?: state?.lastModified,
                    totalBytes = total ?: knownTotal,
                    mediaKey = mediaKey,
                )
            }
            var written = 0L
            val input = body.byteStream()
            val parent = partFile.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                return writeFailure()
            }
            val file = try {
                RandomAccessFile(partFile, "rw")
            } catch (_: IOException) {
                return writeFailure()
            }
            var closeFailed = false
            try {
                try {
                    file.setLength(start)
                    file.seek(start)
                } catch (_: IOException) {
                    return writeFailure()
                }
                if (!journal.write(partFile, nextState)) {
                    return writeFailure()
                }
                val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                val sniffBytes = ByteArray(DownloadTransferRules.SNIFF_BYTES)
                var sniffLength = 0
                var sniffed = start > 0L
                while (true) {
                    cancellation.peek()?.let { signal -> return AttemptResult.Interrupted(signal) }
                    val read = try {
                        input.read(buffer)
                    } catch (failure: IOException) {
                        currentCoroutineContext().ensureActive()
                        syncQuietly(file)
                        return cancellation.peek()?.let { signal -> AttemptResult.Interrupted(signal) }
                            ?: AttemptResult.Transport
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    if (!sniffed) {
                        val copyLength = minOf(read, sniffBytes.size - sniffLength)
                        buffer.copyInto(sniffBytes, sniffLength, 0, copyLength)
                        sniffLength += copyLength
                        if (DownloadTransferRules.looksLikeTextPayload(sniffBytes, sniffLength)) {
                            discard(partFile)
                            coordinator.reportProgress(lease, 0)
                            return AttemptResult.Failed(DownloadTransferFailure.PROTOCOL_PAYLOAD)
                        }
                        sniffed = DownloadTransferRules.payloadSniffComplete(sniffBytes, sniffLength)
                    }
                    try {
                        file.write(buffer, 0, read)
                    } catch (_: IOException) {
                        syncQuietly(file)
                        return writeFailure()
                    }
                    written += read
                    val percent = DownloadTransferRules.progressPercent(start + written, nextState.totalBytes)
                    if (throttle.shouldEmit(percent, clock())) coordinator.reportProgress(lease, percent)
                }
                try {
                    file.fd.sync()
                } catch (_: IOException) {
                    return writeFailure()
                }
            } finally {
                try {
                    file.close()
                } catch (_: IOException) {
                    closeFailed = true
                }
            }
            if (closeFailed) return writeFailure()

            if (interruption.completedOrNull() != null) {
                return AttemptResult.Interrupted(interruption.completedOrNull()!!)
            }
            val sizeOnDisk = partFile.length()
            if (sizeOnDisk == 0L) {
                discard(partFile)
                return AttemptResult.Failed(DownloadTransferFailure.EMPTY_BODY)
            }
            if (bodyLength >= 0L && written != bodyLength) {
                if (written > bodyLength) {
                    discard(partFile)
                    coordinator.reportProgress(lease, 0)
                    return AttemptResult.Failed(DownloadTransferFailure.RECORD_MISMATCH)
                }
                return AttemptResult.Failed(DownloadTransferFailure.SHORT_BODY)
            }
            if (code == HTTP_PARTIAL_CONTENT && nextState.totalBytes == null) {
                return AttemptResult.Failed(DownloadTransferFailure.SHORT_BODY)
            }
            val expectedTotal = nextState.totalBytes
            if (expectedTotal != null && sizeOnDisk < expectedTotal) {
                return AttemptResult.Failed(DownloadTransferFailure.SHORT_BODY)
            }
            if (expectedTotal != null && sizeOnDisk > expectedTotal) {
                discard(partFile)
                coordinator.reportProgress(lease, 0)
                return AttemptResult.Failed(DownloadTransferFailure.RECORD_MISMATCH)
            }
            auth.authSucceeded(lease.providerId)
            return AttemptResult.BodyWritten(nextState, sizeOnDisk)
        }
    }

    private suspend fun finalize(
        lease: DownloadLease,
        item: DownloadItemEntity,
        plan: OfflinePathPlan,
        partFile: File,
        finalFile: File,
        state: DownloadTransferState?,
    ): DownloadTransferResult {
        if (item.exportTarget != null) {
            val exportPublisher = exporter
                ?: return settlePark(lease, DownloadTransferPark.EXPORT_UNAVAILABLE)
            return settleExport(
                lease = lease,
                item = item,
                extension = plan.extension,
                staged = partFile,
                source = partFile,
                publisher = exportPublisher,
            )
        }
        val mutex = finalizeGuard.withLock { finalizeLocks.getOrPut(plan.relativePath) { Mutex() } }
        return mutex.withLock {
            if (!partFile.isFile) return@withLock DownloadTransferResult.LeaseLost
            val size = partFile.length()
            val total = state?.totalBytes
            if (total != null && size > total) {
                discard(partFile)
                return@withLock settleRetry(lease, DownloadTransferFailure.RECORD_MISMATCH)
            }
            if (!DownloadTransferRules.canFinalize(size, total)) {
                return@withLock settleRetry(lease, DownloadTransferFailure.SHORT_BODY)
            }
            if (finalFile.exists()) {
                resolveExistingFinal(lease, item, plan, partFile, finalFile)?.let { return@withLock it }
            }
            val moved = try {
                moveWithoutReplace(partFile, finalFile)
                true
            } catch (_: FileAlreadyExistsException) {
                false
            } catch (_: IOException) {
                false
            }
            if (!moved) {
                return@withLock if (finalFile.exists()) {
                    settlePark(lease, DownloadTransferPark.FINAL_PATH_CONFLICT)
                } else {
                    settleRetry(lease, DownloadTransferFailure.WRITE_FAILED)
                }
            }
            val settlement = coordinator.completeWith(lease) { _ ->
                publisher.publishAndSettleActive(item, offlinePublicationRow(item, plan.relativePath, size, clock()))
            }
            when (settlement) {
                CompletionSettlement.Applied -> {
                    journal.delete(partFile)
                    logger.info(TAG, "download row ${lease.rowId} finalized ${plan.relativePath}")
                    DownloadTransferResult.Completed(plan.relativePath, size)
                }
                CompletionSettlement.Failed -> settlePark(lease, DownloadTransferPark.FINAL_PATH_CONFLICT)
                else -> settleLeaseLost(lease)
            }
        }
    }

    /**
     * A final path that already exists is never blindly overwritten or parked:
     * an already-published file settles the row idempotently, a crash orphan
     * with a matching sidecar (variant hash plus persisted expected length) is
     * adopted, and anything unverifiable is quarantined/deleted so the transfer
     * retries safely. Null means the path is now free and the part continues.
     */
    private suspend fun resolveExistingFinal(
        lease: DownloadLease,
        item: DownloadItemEntity,
        plan: OfflinePathPlan,
        partFile: File,
        finalFile: File,
    ): DownloadTransferResult? {
        val size = finalFile.length()
        val published = publisher.selectByOfflinePath(plan.relativePath)
        if (published != null) {
            if (published.sourceKey != item.mediaKey) {
                return settlePark(lease, DownloadTransferPark.FINAL_PATH_CONFLICT)
            }
            return if (coordinator.complete(lease)) {
                discard(partFile)
                logger.info(TAG, "download row ${lease.rowId} settled published ${plan.relativePath}")
                DownloadTransferResult.Completed(plan.relativePath, size)
            } else {
                settleLeaseLost(lease)
            }
        }
        val state = journal.read(partFile)
        val verified = state != null &&
            state.identityHash == plan.hash &&
            state.totalBytes == size &&
            state.mediaKey == item.mediaKey &&
            size > 0L
        if (!verified) {
            finalFile.delete()
            return null
        }
        val settlement = coordinator.completeWith(lease) { _ ->
            publisher.publishAndSettleActive(item, offlinePublicationRow(item, plan.relativePath, size, clock()))
        }
        return when (settlement) {
            CompletionSettlement.Applied -> {
                discard(partFile)
                logger.info(TAG, "download row ${lease.rowId} adopted finalized ${plan.relativePath}")
                DownloadTransferResult.Completed(plan.relativePath, size)
            }
            CompletionSettlement.Failed -> settlePark(lease, DownloadTransferPark.FINAL_PATH_CONFLICT)
            else -> settleLeaseLost(lease)
        }
    }

    private suspend fun settleExport(
        lease: DownloadLease,
        item: DownloadItemEntity,
        extension: String,
        staged: File?,
        source: File,
        publisher: DownloadExportPublisher,
    ): DownloadTransferResult {
        val outcome = try {
            publisher.publish(
                coordinator = coordinator,
                lease = lease,
                item = item,
                extension = extension,
                staged = staged,
                source = source,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DownloadExportOutcome.Retry
        }
        return when (outcome) {
            is DownloadExportOutcome.Published ->
                DownloadTransferResult.Exported(outcome.documentUri, outcome.sizeBytes)
            DownloadExportOutcome.Retry -> settleRetry(lease, DownloadTransferFailure.EXPORT_FAILED)
            DownloadExportOutcome.Unavailable ->
                settleBlock(lease, DownloadTransferBlock.STORAGE_UNAVAILABLE)
            DownloadExportOutcome.RowSettledElsewhere,
            DownloadExportOutcome.LeaseLost,
            -> settleLeaseLost(lease)
        }
    }

    private suspend fun prepareStorage(requiredBytes: Long): OfflineStorageState = try {
        storage.prepare(requiredBytes)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        OfflineStorageState.Blocked(OfflineStorageBlock.ROOT_UNAVAILABLE)
    }

    private suspend fun resolveStoredPath(relativePath: String): File? = try {
        storage.resolveStoredPath(relativePath)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun writeFailure(): AttemptResult {
        val prepared = prepareStorage(0L)
        return if (prepared is OfflineStorageState.Blocked) {
            AttemptResult.Deferred(prepared.toTransferBlock())
        } else {
            AttemptResult.Failed(DownloadTransferFailure.WRITE_FAILED)
        }
    }

    private suspend fun settleRetry(
        lease: DownloadLease,
        failure: DownloadTransferFailure,
    ): DownloadTransferResult {
        logger.warn(TAG, "download row ${lease.rowId} ${failure.description()}")
        val status = coordinator.recordFailure(lease, failure.description())
        coordinator.release(lease)
        return DownloadTransferResult.Retry(failure, status)
    }

    /** Frees this worker's slot without touching a row another command already settled. */
    private suspend fun settleLeaseLost(lease: DownloadLease): DownloadTransferResult {
        coordinator.release(lease)
        return DownloadTransferResult.LeaseLost
    }

    private suspend fun settleBlock(
        lease: DownloadLease,
        block: DownloadTransferBlock,
    ): DownloadTransferResult {
        logger.warn(TAG, "download row ${lease.rowId} blocked ${block.name.lowercase()}")
        coordinator.release(lease)
        return DownloadTransferResult.Blocked(block)
    }

    private suspend fun settlePark(
        lease: DownloadLease,
        reason: DownloadTransferPark,
    ): DownloadTransferResult {
        logger.warn(TAG, "download row ${lease.rowId} ${reason.description()}")
        val parked = coordinator.park(lease, reason.description())
        if (!parked) {
            coordinator.release(lease)
            return DownloadTransferResult.LeaseLost
        }
        return DownloadTransferResult.Parked(reason)
    }

    private suspend fun settleInterruption(
        lease: DownloadLease,
        interruption: DownloadInterruption,
        partFile: File,
    ): DownloadTransferResult {
        val cancelled = interruption == DownloadInterruption.CANCEL ||
            repository.statusOf(lease.rowId) == null
        if (cancelled) {
            discard(partFile)
            coordinator.release(lease)
            return DownloadTransferResult.Cancelled
        }
        val preserved = if (partFile.isFile) partFile.length() else 0L
        coordinator.release(lease)
        return DownloadTransferResult.Paused(preserved)
    }

    private fun discard(partFile: File) {
        partFile.delete()
        journal.delete(partFile)
    }

    private sealed interface AttemptResult {

        data class BodyWritten(
            val state: DownloadTransferState,
            val offset: Long,
        ) : AttemptResult

        data class RestartRequired(val reason: DownloadTransferFailure) : AttemptResult

        data class Failed(val failure: DownloadTransferFailure) : AttemptResult

        data class Deferred(val block: DownloadTransferBlock) : AttemptResult

        data class Interrupted(val interruption: DownloadInterruption) : AttemptResult

        data object Transport : AttemptResult
    }

    private class TransferCancellation {

        @Volatile
        private var call: Call? = null

        @Volatile
        private var interruption: DownloadInterruption? = null

        fun attach(call: Call) {
            this.call = call
            interruption?.let { call.cancel() }
        }

        fun signal(interruption: DownloadInterruption) {
            this.interruption = interruption
            call?.cancel()
        }

        fun peek(): DownloadInterruption? = interruption
    }

    private companion object {
        const val TAG = "DownloadTransfer"
        const val ACCEPT_ENCODING = "Accept-Encoding"
        const val IDENTITY_ENCODING = "identity"
        const val RANGE = "Range"
        const val IF_RANGE = "If-Range"
        const val CONTENT_ENCODING = "Content-Encoding"
        const val CONTENT_TYPE = "Content-Type"
        const val CONTENT_RANGE = "Content-Range"
        const val ETAG = "ETag"
        const val LAST_MODIFIED = "Last-Modified"
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val TRANSFER_BUFFER_SIZE = 64 * 1024
    }
}

private fun OfflineStorageState.Blocked.toTransferBlock(): DownloadTransferBlock = when (reason) {
    OfflineStorageBlock.ROOT_UNAVAILABLE -> DownloadTransferBlock.STORAGE_UNAVAILABLE
    OfflineStorageBlock.NOT_WRITABLE -> DownloadTransferBlock.STORAGE_NOT_WRITABLE
    OfflineStorageBlock.LOW_SPACE -> DownloadTransferBlock.STORAGE_LOW_SPACE
}

private fun CompletableDeferred<DownloadInterruption?>.completedOrNull(): DownloadInterruption? =
    if (isCompleted) getCompleted() else null

private fun syncQuietly(file: RandomAccessFile) {
    runCatching { file.fd.sync() }
}

/**
 * A same-directory rename that refuses an existing target: variant-qualified
 * paths never collide, and a replacement must go through the task-5/8
 * generation policy instead of silently overwriting a finished file.
 */
private fun moveWithoutReplace(source: File, target: File) {
    Files.move(source.toPath(), target.toPath())
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, failure: IOException) {
                continuation.resumeWithException(failure)
            }
        },
    )
}
