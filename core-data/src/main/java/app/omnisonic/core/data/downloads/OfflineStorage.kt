package app.omnisonic.core.data.downloads

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface OfflineRootSource {
    fun roots(): List<OfflineRoot>
}

/**
 * App-private roots available to this install, in preference order: primary
 * external first, any removable app-private directories next, internal files
 * last. Volume ids are the directories' canonical paths (internal is the fixed
 * [OfflineRoot.INTERNAL_VOLUME]); an arbitrary user-typed path is never
 * accepted, and no storage permission or SAF document is involved.
 */
class AppPrivateOfflineRootSource(context: Context) : OfflineRootSource {

    private val appContext = context.applicationContext

    override fun roots(): List<OfflineRoot> {
        val result = mutableListOf<OfflineRoot>()
        appContext.getExternalFilesDirs(null)?.forEach { directory ->
            if (directory != null) {
                result += OfflineRoot(volumeId = canonicalOrAbsolute(directory), directory = directory)
            }
        }
        result += OfflineRoot(OfflineRoot.INTERNAL_VOLUME, appContext.filesDir)
        return result.distinctBy { it.volumeId }
    }

    private fun canonicalOrAbsolute(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
}

sealed interface OfflineRootResolution {

    data class Available(val root: OfflineRoot) : OfflineRootResolution

    /** A published or explicitly selected volume is missing; never an empty library. */
    data class Unavailable(val volumeId: String?) : OfflineRootResolution
}

enum class OfflineStorageBlock {
    ROOT_UNAVAILABLE,
    NOT_WRITABLE,
    LOW_SPACE,
}

sealed interface OfflineStorageState {

    data class Ready(
        val root: OfflineRoot,
        val mediaDir: File,
        val freeBytes: Long,
    ) : OfflineStorageState

    data class Blocked(
        val reason: OfflineStorageBlock,
        val freeBytes: Long = -1L,
        val requiredBytes: Long = 0L,
    ) : OfflineStorageState
}

interface OfflineRootWorkGate {
    suspend fun pauseForRootChange()
    suspend fun resumeAfterRootChange()

    companion object {
        /** Hosts with no running workers (fresh install, tests) can pass this. */
        val NoWorkers: OfflineRootWorkGate = object : OfflineRootWorkGate {
            override suspend fun pauseForRootChange() = Unit
            override suspend fun resumeAfterRootChange() = Unit
        }
    }
}

enum class OfflineRootChangeFailure {
    MOVE_IN_PROGRESS,
    TARGET_UNAVAILABLE,
    SOURCE_UNAVAILABLE,
    NOT_WRITABLE,
    LOW_SPACE,
    DESTINATION_CONFLICT,
    INVALID_TREE,
    COPY_FAILED,
    COMMIT_FAILED,
}

sealed interface OfflineRootChangeResult {

    data class Completed(
        val activeVolumeId: String,
        val movedFiles: Int,
        val generation: Long,
        val cleanupPending: Boolean = false,
    ) : OfflineRootChangeResult

    data object AlreadyActive : OfflineRootChangeResult

    data class Failed(
        val reason: OfflineRootChangeFailure,
        val detail: String? = null,
    ) : OfflineRootChangeResult

    data object NoChange : OfflineRootChangeResult
}

/**
 * Injectable offline-storage seam over plain `java.io.File` (Phase 7 task 2,
 * D-40). It plans and resolves paths, prepares `.nomedia`/free-space readiness
 * before any network work, and performs the approved root change: pause
 * workers, copy every file with digest verification, publish the new root only
 * after all copies verify, then delete the old copies. A process death leaves
 * enough persisted state to resume or roll back, and an unavailable root is
 * reported as unavailable — never as an empty library.
 *
 * This type owns no transfer and no database read/write.
 */
class OfflineStorage(
    private val roots: OfflineRootSource,
    private val settings: DownloadQueueSettingsStore,
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val writableProbe: (File) -> Boolean = ::probeWritable,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val rootChangeMutex = Mutex()

    fun availableRoots(): List<OfflineRoot> = roots.roots().distinctBy { it.volumeId }

    fun mediaDir(root: OfflineRoot): File = File(root.directory, OfflinePathRules.MEDIA_DIRECTORY_NAME)

    suspend fun activeRoot(): OfflineRootResolution {
        val state = settings.currentRootState()
        return resolveActiveRoot(state, availableRoots())
    }

    /**
     * Creates the selected root and `.nomedia`, verifies writability and the
     * 100 MiB floor, and pins the first usable root. [Auto] falls back to
     * internal files when the external root is missing or unwritable; an
     * explicitly selected or already-published volume never silently falls
     * back.
     */
    suspend fun prepare(requiredBytes: Long = 0L): OfflineStorageState = withContext(ioDispatcher) {
        val state = settings.currentRootState()
        val candidates = prepareCandidates(state, availableRoots())
            ?: return@withContext OfflineStorageState.Blocked(OfflineStorageBlock.ROOT_UNAVAILABLE)

        var lastBlocked: OfflineStorageState.Blocked? = null
        for (root in candidates) {
            val result = prepareRootState(root, requiredBytes)
            if (result is OfflineStorageState.Ready) {
                if (state.activeVolumeId == null) {
                    settings.publishRoot(state.selection, root.volumeId, state.generation)
                }
                return@withContext result
            }
            val blocked = result as OfflineStorageState.Blocked
            if (blocked.reason == OfflineStorageBlock.LOW_SPACE) return@withContext blocked
            lastBlocked = blocked
        }
        lastBlocked ?: OfflineStorageState.Blocked(OfflineStorageBlock.ROOT_UNAVAILABLE)
    }

    /**
     * Stored paths are relative to the storage root and stay canonical at
     * resolution time. Null means unavailable/unsafe; callers must not treat it
     * as an empty library or a zero-byte file.
     */
    suspend fun resolveStoredPath(relativePath: String): File? = withContext(ioDispatcher) {
        val state = settings.currentRootState()
        val root = when (val resolution = resolveActiveRoot(state, availableRoots())) {
            is OfflineRootResolution.Available -> resolution.root
            is OfflineRootResolution.Unavailable -> return@withContext null
        }
        if (!root.directory.isDirectory) return@withContext null
        OfflinePathRules.resolveUnder(root.directory, relativePath)
    }

    fun planPath(
        identity: OfflineMediaIdentity,
        assignedPaths: Map<String, String> = emptyMap(),
    ): OfflinePathResult = OfflinePathRules.plan(identity, assignedPaths)

    /**
     * Pauses worker claims, copies and verifies every existing file to the new
     * root, commits the publication, then removes the old copies. Failure
     * before the commit deletes only files created by this attempt and leaves
     * the old root authoritative.
     */
    suspend fun changeRoot(
        selection: OfflineRootSelection,
        gate: OfflineRootWorkGate,
    ): OfflineRootChangeResult = rootChangeMutex.withLock {
        withContext(ioDispatcher) { performRootChange(selection, gate, allowPendingResume = false) }
    }

    /**
     * Runs before workers start. A journaled move is re-run idempotently (the
     * filesystem is the per-file journal); a published move with leftover old
     * copies only finishes the cleanup. An unavailable target rolls the move
     * back so the old root stays authoritative.
     */
    suspend fun recoverInterruptedRootChange(
        gate: OfflineRootWorkGate,
    ): OfflineRootChangeResult = rootChangeMutex.withLock {
        withContext(ioDispatcher) {
            val state = settings.currentRootState()
            when {
                state.pendingVolumeId != null ->
                    performRootChange(
                        selection = state.pendingSelection ?: state.selection,
                        gate = gate,
                        allowPendingResume = true,
                    )
                state.cleanupVolumeId != null -> finishCleanup(state)
                else -> OfflineRootChangeResult.NoChange
            }
        }
    }

    private fun prepareCandidates(
        state: DownloadRootState,
        candidates: List<OfflineRoot>,
    ): List<OfflineRoot>? {
        state.activeVolumeId?.let { pinned ->
            return candidates.filter { it.volumeId == pinned }.ifEmpty { null }
        }
        return when (val selection = state.selection) {
            OfflineRootSelection.Auto -> {
                val external = candidates.filter { it.volumeId != OfflineRoot.INTERNAL_VOLUME }
                val internal = candidates.filter { it.volumeId == OfflineRoot.INTERNAL_VOLUME }
                (external + internal).ifEmpty { null }
            }
            is OfflineRootSelection.Volume ->
                candidates.filter { it.volumeId == selection.volumeId }.ifEmpty { null }
        }
    }

    private fun resolveActiveRoot(
        state: DownloadRootState,
        candidates: List<OfflineRoot>,
    ): OfflineRootResolution {
        state.activeVolumeId?.let { pinned ->
            return candidates.firstOrNull { it.volumeId == pinned }
                ?.let(OfflineRootResolution::Available)
                ?: OfflineRootResolution.Unavailable(pinned)
        }
        val root = when (val selection = state.selection) {
            OfflineRootSelection.Auto ->
                candidates.firstOrNull { it.volumeId != OfflineRoot.INTERNAL_VOLUME }
                    ?: candidates.firstOrNull { it.volumeId == OfflineRoot.INTERNAL_VOLUME }
            is OfflineRootSelection.Volume ->
                candidates.firstOrNull { it.volumeId == selection.volumeId }
        }
        return root?.let(OfflineRootResolution::Available)
            ?: OfflineRootResolution.Unavailable(null)
    }

    private fun prepareRootState(root: OfflineRoot, requiredBytes: Long): OfflineStorageState {
        val directory = root.directory
        if (directory.exists() && !directory.isDirectory) {
            return OfflineStorageState.Blocked(OfflineStorageBlock.NOT_WRITABLE)
        }
        // A concurrent worker may win the mkdirs; a lost race is not unwritable.
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            return OfflineStorageState.Blocked(OfflineStorageBlock.NOT_WRITABLE)
        }
        if (!writableProbe(directory)) {
            return OfflineStorageState.Blocked(OfflineStorageBlock.NOT_WRITABLE)
        }
        val media = mediaDir(root)
        if (media.exists() && !media.isDirectory) {
            return OfflineStorageState.Blocked(OfflineStorageBlock.NOT_WRITABLE)
        }
        if (!media.isDirectory && !media.mkdirs() && !media.isDirectory) {
            return OfflineStorageState.Blocked(OfflineStorageBlock.NOT_WRITABLE)
        }
        if (!writableProbe(media)) {
            return OfflineStorageState.Blocked(OfflineStorageBlock.NOT_WRITABLE)
        }
        if (!ensureNomedia(media)) {
            return OfflineStorageState.Blocked(OfflineStorageBlock.NOT_WRITABLE)
        }
        val freeBytes = usableSpace(media)
        if (!hasFreeSpace(freeBytes, requiredBytes)) {
            return OfflineStorageState.Blocked(
                reason = OfflineStorageBlock.LOW_SPACE,
                freeBytes = freeBytes,
                requiredBytes = requiredBytes.coerceAtLeast(0L),
            )
        }
        return OfflineStorageState.Ready(root, media, freeBytes)
    }

    private fun hasFreeSpace(freeBytes: Long, requiredBytes: Long): Boolean {
        if (freeBytes < 0L) return false
        val required = requiredBytes.coerceAtLeast(0L)
        if (required > Long.MAX_VALUE - OfflinePathRules.MIN_FREE_SPACE_BYTES) return false
        return freeBytes >= OfflinePathRules.MIN_FREE_SPACE_BYTES + required
    }

    private fun ensureNomedia(mediaDir: File): Boolean {
        val marker = File(mediaDir, OfflinePathRules.NOMEDIA_FILE_NAME)
        if (marker.isFile) return true
        return try {
            marker.createNewFile()
            marker.isFile
        } catch (_: IOException) {
            false
        }
    }

    private suspend fun performRootChange(
        selection: OfflineRootSelection,
        gate: OfflineRootWorkGate,
        allowPendingResume: Boolean,
    ): OfflineRootChangeResult {
        val state = settings.currentRootState()
        val pendingId = state.pendingVolumeId
        if (pendingId != null && !allowPendingResume) {
            return OfflineRootChangeResult.Failed(OfflineRootChangeFailure.MOVE_IN_PROGRESS)
        }

        val candidates = availableRoots()
        val targetId = pendingId ?: resolveSelectionId(selection, candidates)
        val target = targetId?.let { id -> candidates.firstOrNull { it.volumeId == id } }
        if (target == null) {
            if (pendingId != null) settings.rollbackRootMove()
            return OfflineRootChangeResult.Failed(
                reason = OfflineRootChangeFailure.TARGET_UNAVAILABLE,
                detail = targetId ?: selection.storedValue(),
            )
        }

        val source = when (val active = resolveActiveRoot(state, candidates)) {
            is OfflineRootResolution.Available -> active.root
            is OfflineRootResolution.Unavailable -> null
        }
        if (state.activeVolumeId != null && source == null) {
            if (pendingId != null) settings.rollbackRootMove()
            return OfflineRootChangeResult.Failed(
                reason = OfflineRootChangeFailure.SOURCE_UNAVAILABLE,
                detail = state.activeVolumeId,
            )
        }

        val generation = if (pendingId != null) {
            state.pendingGeneration
        } else {
            if (state.generation == Long.MAX_VALUE) Long.MAX_VALUE else state.generation + 1
        }

        if (source != null && source.volumeId == target.volumeId) {
            val prepared = prepareRootState(target, 0L)
            if (prepared is OfflineStorageState.Blocked) return blockedChange(prepared)
            withContext(NonCancellable) {
                if (pendingId != null) {
                    settings.commitRootMove(selection, target.volumeId, generation, cleanupVolumeId = null)
                } else {
                    settings.publishRoot(selection, target.volumeId, generation)
                }
            }
            return OfflineRootChangeResult.AlreadyActive
        }

        gate.pauseForRootChange()
        try {
            val sourceMedia = source?.let(::mediaDir)
            val entries = when {
                sourceMedia == null || !sourceMedia.exists() -> emptyList()
                !sourceMedia.isDirectory -> return OfflineRootChangeResult.Failed(
                    OfflineRootChangeFailure.INVALID_TREE,
                    OfflinePathRules.MEDIA_DIRECTORY_NAME,
                )
                else -> collectSourceEntries(sourceMedia)
                    ?: return OfflineRootChangeResult.Failed(
                        OfflineRootChangeFailure.INVALID_TREE,
                        OfflinePathRules.MEDIA_DIRECTORY_NAME,
                    )
            }

            var requiredBytes = 0L
            for (entry in entries) {
                if (requiredBytes > Long.MAX_VALUE - entry.size) {
                    return OfflineRootChangeResult.Failed(OfflineRootChangeFailure.LOW_SPACE)
                }
                requiredBytes += entry.size
            }

            val prepared = prepareRootState(target, requiredBytes)
            if (prepared is OfflineStorageState.Blocked) return blockedChange(prepared)

            if (pendingId == null) {
                withContext(NonCancellable) {
                    settings.beginRootMove(selection, target.volumeId, generation)
                }
            }

            val targetMedia = mediaDir(target)
            val created = mutableListOf<File>()
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                val targetFile = OfflinePathRules.resolveUnder(targetMedia, entry.relativePath)
                    ?: return rollbackFailure(created, OfflineRootChangeFailure.INVALID_TREE, entry.relativePath)
                if (targetFile.exists()) {
                    if (!targetFile.isFile || !sameContent(entry.file, targetFile)) {
                        return rollbackFailure(
                            created,
                            OfflineRootChangeFailure.DESTINATION_CONFLICT,
                            entry.relativePath,
                        )
                    }
                } else {
                    if (!copyVerified(entry.file, targetFile)) {
                        return rollbackFailure(created, OfflineRootChangeFailure.COPY_FAILED, entry.relativePath)
                    }
                    created += targetFile
                }
            }

            val committed = runCatching {
                withContext(NonCancellable) {
                    settings.commitRootMove(
                        selection = selection,
                        activeVolumeId = target.volumeId,
                        generation = generation,
                        cleanupVolumeId = source?.volumeId,
                    )
                }
            }.isSuccess
            if (!committed) {
                return rollbackFailure(created, OfflineRootChangeFailure.COMMIT_FAILED, null)
            }

            val cleanupComplete = cleanupSource(sourceMedia, targetMedia, entries)
            if (cleanupComplete) {
                withContext(NonCancellable) { settings.finishRootCleanup() }
            }
            return OfflineRootChangeResult.Completed(
                activeVolumeId = target.volumeId,
                movedFiles = entries.size,
                generation = generation,
                cleanupPending = !cleanupComplete,
            )
        } finally {
            withContext(NonCancellable) { gate.resumeAfterRootChange() }
        }
    }

    private fun blockedChange(blocked: OfflineStorageState.Blocked): OfflineRootChangeResult.Failed =
        OfflineRootChangeResult.Failed(
            reason = when (blocked.reason) {
                OfflineStorageBlock.ROOT_UNAVAILABLE -> OfflineRootChangeFailure.TARGET_UNAVAILABLE
                OfflineStorageBlock.NOT_WRITABLE -> OfflineRootChangeFailure.NOT_WRITABLE
                OfflineStorageBlock.LOW_SPACE -> OfflineRootChangeFailure.LOW_SPACE
            },
            detail = blocked.freeBytes.takeIf { it >= 0L }?.toString(),
        )

    private suspend fun rollbackFailure(
        created: List<File>,
        reason: OfflineRootChangeFailure,
        detail: String?,
    ): OfflineRootChangeResult {
        created.asReversed().forEach { file -> runCatching { file.delete() } }
        withContext(NonCancellable) { settings.rollbackRootMove() }
        return OfflineRootChangeResult.Failed(reason, detail)
    }

    private fun resolveSelectionId(
        selection: OfflineRootSelection,
        candidates: List<OfflineRoot>,
    ): String? = when (selection) {
        OfflineRootSelection.Auto ->
            (candidates.firstOrNull { it.volumeId != OfflineRoot.INTERNAL_VOLUME }
                ?: candidates.firstOrNull { it.volumeId == OfflineRoot.INTERNAL_VOLUME })?.volumeId
        is OfflineRootSelection.Volume -> selection.volumeId
    }

    private data class SourceEntry(
        val relativePath: String,
        val file: File,
        val size: Long,
    )

    private fun collectSourceEntries(mediaDir: File): List<SourceEntry>? {
        val entries = mutableListOf<SourceEntry>()
        fun visit(directory: File): Boolean {
            val children = directory.listFiles() ?: return false
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) return false
                if (child.isDirectory) {
                    if (!visit(child)) return false
                    continue
                }
                if (!child.isFile) return false
                val relative = child.relativeTo(mediaDir).invariantSeparatorsPath
                if (relative == OfflinePathRules.NOMEDIA_FILE_NAME) continue
                if (!OfflinePathRules.isSafeRelativePath(relative)) return false
                entries += SourceEntry(relative, child, child.length())
            }
            return true
        }
        return if (visit(mediaDir)) entries else null
    }

    private fun copyVerified(source: File, target: File): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val temporary = File(parent, "${target.name}.moving")
        return try {
            temporary.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        copied += read
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (source.length() != copied || temporary.length() != copied) {
                temporary.delete()
                return false
            }
            val expected = digest.digest()
            val actual = digestOf(temporary)
            if (actual == null) {
                temporary.delete()
                return false
            }
            if (!MessageDigest.isEqual(expected, actual)) {
                temporary.delete()
                return false
            }
            if (target.exists()) {
                temporary.delete()
                return false
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return false
            }
            target.isFile && target.length() == copied
        } catch (_: IOException) {
            temporary.delete()
            false
        } catch (_: SecurityException) {
            temporary.delete()
            false
        }
    }

    private fun digestOf(file: File): ByteArray? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun sameContent(left: File, right: File): Boolean {
        if (left.length() != right.length()) return false
        val leftDigest = digestOf(left) ?: return false
        val rightDigest = digestOf(right) ?: return false
        return MessageDigest.isEqual(leftDigest, rightDigest)
    }

    private fun cleanupSource(
        sourceMedia: File?,
        targetMedia: File,
        entries: List<SourceEntry>,
    ): Boolean {
        if (sourceMedia == null) return true
        var complete = true
        for (entry in entries) {
            val target = OfflinePathRules.resolveUnder(targetMedia, entry.relativePath)
            if (target == null || !target.isFile || target.length() != entry.size) {
                complete = false
                continue
            }
            if (!entry.file.delete()) complete = false
        }
        if (complete) {
            File(sourceMedia, OfflinePathRules.NOMEDIA_FILE_NAME).delete()
            deleteEmptyDirectories(sourceMedia)
            complete = sourceMedia.delete() || !sourceMedia.exists()
        }
        return complete
    }

    private fun deleteEmptyDirectories(directory: File) {
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory && !Files.isSymbolicLink(child.toPath())) {
                deleteEmptyDirectories(child)
                child.delete()
            }
        }
    }

    private suspend fun finishCleanup(state: DownloadRootState): OfflineRootChangeResult {
        val volumeId = state.cleanupVolumeId ?: return OfflineRootChangeResult.NoChange
        val candidates = availableRoots()
        val publishedTarget = state.activeVolumeId
            ?.let { activeId -> candidates.firstOrNull { it.volumeId == activeId } }
        if (publishedTarget == null || !mediaDir(publishedTarget).isDirectory) {
            return OfflineRootChangeResult.Completed(
                activeVolumeId = state.activeVolumeId.orEmpty(),
                movedFiles = 0,
                generation = state.generation,
                cleanupPending = true,
            )
        }
        val root = candidates.firstOrNull { it.volumeId == volumeId }
        if (root == null) {
            return OfflineRootChangeResult.Completed(
                activeVolumeId = state.activeVolumeId.orEmpty(),
                movedFiles = 0,
                generation = state.generation,
                cleanupPending = true,
            )
        }
        val media = mediaDir(root)
        val deleted = deleteTree(media)
        if (deleted) withContext(NonCancellable) { settings.finishRootCleanup() }
        return OfflineRootChangeResult.Completed(
            activeVolumeId = state.activeVolumeId.orEmpty(),
            movedFiles = 0,
            generation = state.generation,
            cleanupPending = !deleted,
        )
    }

    private fun deleteTree(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            val children = file.listFiles() ?: return false
            var complete = true
            for (child in children) {
                if (!deleteTree(child)) complete = false
            }
            val deleted = file.delete()
            return complete && deleted
        }
        return file.delete()
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
    }
}

private fun probeWritable(directory: File): Boolean {
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return false
    return try {
        val probe = File.createTempFile(".write-probe", null, directory)
        probe.delete()
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
