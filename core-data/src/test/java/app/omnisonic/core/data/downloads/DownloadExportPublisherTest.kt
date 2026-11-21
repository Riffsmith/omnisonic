package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.silentTestLogger
import app.omnisonic.provider.api.DownloadEnqueueResult
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import app.omnisonic.provider.api.ProviderTraits
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadExportPublisherTest : DatabaseTestBase() {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private val store = FakeExportDocumentStore()
    private val journal = DownloadTransferJournal()
    private var rootIndex = 0

    private lateinit var root: File
    private lateinit var otherRoot: File
    private lateinit var settings: DownloadQueueSettingsStore
    private lateinit var storage: OfflineStorage
    private lateinit var sessions: ExportSessionJournal
    private lateinit var repository: DownloadQueueRepository
    private lateinit var coordinator: DownloadWorkerCoordinator
    private lateinit var offline: OfflineMediaRepository
    private lateinit var publisher: DownloadExportPublisher

    private val mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null)
    private val exportTarget = "MEDIASTORE_DOWNLOADS"
    private val payload = ByteArray(512) { (it % 251).toByte() }

    private suspend fun setup() {
        newProvider(id = 1)
        root = File(temp.root, "root-${rootIndex++}").apply { mkdirs() }
        otherRoot = File(temp.root, "root-move").apply { mkdirs() }
        settings = DownloadQueueSettingsStore.inMemory()
        storage = OfflineStorage(
            roots = OfflineRootSource {
                listOf(
                    OfflineRoot("root-a", root),
                    OfflineRoot("root-b", otherRoot),
                )
            },
            settings = settings,
            usableSpace = { OfflinePathRules.MIN_FREE_SPACE_BYTES * 10 },
            writableProbe = { true },
        )
        storage.prepare(0L)
        sessions = ExportSessionJournal(File(temp.root, "sessions-${rootIndex}"))
        repository = DownloadQueueRepository(downloadDao)
        offline = OfflineMediaRepository(db)
        coordinator = DownloadWorkerCoordinator(
            repository = repository,
            settings = settings,
            runGate = ProviderRunGate { ProviderStatus.OK },
            workerCount = 2,
            nowMs = { 1_000 },
        )
        publisher = DownloadExportPublisher(
            queues = repository,
            offline = offline,
            storage = storage,
            store = store,
            sessions = sessions,
            logger = silentTestLogger,
        )
    }

    private suspend fun seedRow(
        status: DownloadStatus = DownloadStatus.PENDING,
        exportTarget: String? = this.exportTarget,
        title: String = "Track",
    ): DownloadItemEntity {
        downloadDao.upsert(
            DownloadItemEntity(
                mediaKey = mediaKey,
                providerId = 1,
                status = status.name,
                title = title,
                exportTarget = exportTarget,
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
        return downloadDao.selectByMediaKey(mediaKey)!!
    }

    private suspend fun claim(): DownloadLease =
        coordinator.claimNext(ProfileScope.Unrestricted) ?: error("no claimable row")

    private fun stagedFile(bytes: ByteArray = payload): File {
        val part = File(root, "OfflineMedias/a/staged.part")
        part.parentFile?.mkdirs()
        part.writeBytes(bytes)
        journal.write(
            part,
            DownloadTransferState(
                identityHash = "a".repeat(64),
                totalBytes = bytes.size.toLong(),
                mediaKey = mediaKey,
            ),
        )
        return part
    }

    private suspend fun publish(
        lease: DownloadLease,
        item: DownloadItemEntity,
        staged: File?,
        source: File = staged ?: error("source required"),
        extension: String = "flac",
    ): DownloadExportOutcome = publisher.publish(coordinator, lease, item, extension, staged, source)

    @Test
    fun `a staged file becomes one visible document and the row is settled`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val part = stagedFile()

        val outcome = publish(lease, row, part)

        val document = store.documents.values.single()
        assertThat(outcome).isInstanceOf(DownloadExportOutcome.Published::class.java)
        assertThat(document.request.relativePath).isEqualTo("Download/Omnisonic/")
        assertThat(document.request.mimeType).isEqualTo("audio/flac")
        assertThat(document.request.displayName).startsWith("Track [")
        assertThat(document.request.displayName).endsWith(".flac")
        assertThat(document.pending).isFalse()
        assertThat(document.bytes).isEqualTo(payload)
        assertThat(store.inserts).isEqualTo(1)
        assertThat(store.clears).isEqualTo(1)
        assertThat(downloadDao.selectById(row.id)).isNull()
        assertThat(offlineFileDao.count()).isEqualTo(0)
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(part.exists()).isFalse()
        assertThat(journal.stateFile(part).exists()).isFalse()
    }

    @Test
    fun `a rejected copy removes the pending document and a retry never duplicates it`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val part = stagedFile()
        store.failWrites = true

        assertThat(publish(lease, row, part)).isEqualTo(DownloadExportOutcome.Retry)

        assertThat(store.documents).isEmpty()
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(downloadDao.selectById(row.id)!!.status).isEqualTo(DownloadStatus.ACTIVE.name)

        store.failWrites = false
        assertThat(publish(lease, row, part)).isInstanceOf(DownloadExportOutcome.Published::class.java)

        assertThat(store.documents).hasSize(1)
        assertThat(store.inserts).isEqualTo(2)
        assertThat(store.documents.values.single().bytes).isEqualTo(payload)
        assertThat(downloadDao.selectById(row.id)).isNull()
    }

    @Test
    fun `a short or corrupt copy is rejected and cleaned`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val part = stagedFile()

        store.shortWrite = true
        assertThat(publish(lease, row, part)).isEqualTo(DownloadExportOutcome.Retry)
        assertThat(store.documents).isEmpty()

        store.shortWrite = false
        store.corruptWrite = true
        assertThat(publish(lease, row, part)).isEqualTo(DownloadExportOutcome.Retry)
        assertThat(store.documents).isEmpty()
        assertThat(store.clears).isEqualTo(0)
        assertThat(sessions.read(mediaKey)).isNull()
    }

    @Test
    fun `a recorded pending session is rewritten instead of inserting a second document`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val part = stagedFile()
        val request = ExportNaming.request(mediaKey, "Track", "flac")
        val created = store.insertPending(request)!!
        sessions.write(
            ExportSession(mediaKey, created.uri, request.displayName, request.mimeType, payload.size.toLong(), ExportSessionPhase.PENDING),
        )

        val outcome = publish(lease, row, part)

        assertThat(outcome).isInstanceOf(DownloadExportOutcome.Published::class.java)
        assertThat((outcome as DownloadExportOutcome.Published).reusedDocument).isTrue()
        assertThat(store.inserts).isEqualTo(1)
        assertThat(store.documents).hasSize(1)
        assertThat(store.documents.values.single().bytes).isEqualTo(payload)
        assertThat(downloadDao.selectById(row.id)).isNull()
    }

    @Test
    fun `an unavailable resolver blocks without creating or settling anything`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val part = stagedFile()
        store.available = false

        assertThat(publish(lease, row, part)).isEqualTo(DownloadExportOutcome.Unavailable)

        assertThat(store.inserts).isEqualTo(0)
        assertThat(store.documents).isEmpty()
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(downloadDao.selectById(row.id)!!.status).isEqualTo(DownloadStatus.ACTIVE.name)
        assertThat(part.exists()).isTrue()
    }

    @Test
    fun `a cancel during the copy removes the document instead of publishing it`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val part = stagedFile()
        downloadDao.deleteById(row.id)

        assertThat(publish(lease, row, part)).isEqualTo(DownloadExportOutcome.RowSettledElsewhere)

        assertThat(store.documents).isEmpty()
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(part.exists()).isTrue()
    }

    @Test
    fun `a pause during the copy removes the document instead of publishing it`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val part = stagedFile()
        coordinator.pauseOne(row.id)

        assertThat(publish(lease, row, part)).isEqualTo(DownloadExportOutcome.RowSettledElsewhere)

        assertThat(store.documents).isEmpty()
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(downloadDao.selectById(row.id)!!.status).isEqualTo(DownloadStatus.USER_PAUSED.name)
    }

    @Test
    fun `an existing published offline file is exported without being modified`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val permanent = File(root, "OfflineMedias/b/permanent.flac").apply {
            parentFile?.mkdirs()
            writeBytes(payload)
        }
        offlineFileDao.upsert(
            OfflineFileEntity(
                providerId = 1,
                mediaType = "SONG",
                sourceKey = mediaKey,
                offlinePath = "OfflineMedias/b/permanent.flac",
                sizeBytes = payload.size.toLong(),
                tempCache = false,
            ),
        )

        val local = publisher.localExportSource(row)
        assertThat(local).isNotNull()
        assertThat(local!!.extension).isEqualTo("flac")

        assertThat(publish(lease, row, staged = null, source = local.file, extension = local.extension))
            .isInstanceOf(DownloadExportOutcome.Published::class.java)

        assertThat(permanent.readBytes()).isEqualTo(payload)
        assertThat(offlineFileDao.selectBySourceKey(mediaKey)).isNotNull()
        assertThat(store.documents.values.single().bytes).isEqualTo(payload)
        assertThat(downloadDao.selectById(row.id)).isNull()
    }

    @Test
    fun `an export failure never damages the existing permanent offline file`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val permanent = File(root, "OfflineMedias/b/permanent.flac").apply {
            parentFile?.mkdirs()
            writeBytes(payload)
        }
        offlineFileDao.upsert(
            OfflineFileEntity(
                providerId = 1,
                mediaType = "SONG",
                sourceKey = mediaKey,
                offlinePath = "OfflineMedias/b/permanent.flac",
                sizeBytes = payload.size.toLong(),
                tempCache = false,
            ),
        )
        store.failWrites = true

        val outcome = publish(lease, row, staged = null, source = permanent)

        assertThat(outcome).isEqualTo(DownloadExportOutcome.Retry)
        assertThat(permanent.readBytes()).isEqualTo(payload)
        assertThat(offlineFileDao.selectBySourceKey(mediaKey)!!.sizeBytes).isEqualTo(payload.size.toLong())
        assertThat(store.documents).isEmpty()
    }

    @Test
    fun `a published session left by process death settles its row without a second document`() = runDbTest {
        setup()
        val row = seedRow()
        val request = ExportNaming.request(mediaKey, "Track", "flac")
        val created = store.insertPending(request)!!
        store.writeTruncating(created, stagedFile())
        store.clearPending(created)
        sessions.write(
            ExportSession(mediaKey, created.uri, request.displayName, request.mimeType, payload.size.toLong(), ExportSessionPhase.PUBLISHED),
        )

        val counts = publisher.recoverSessions()

        assertThat(counts).isEqualTo(ExportRecoveryCounts(settledPublished = 1))
        assertThat(store.documents).hasSize(1)
        assertThat(store.deletes).isEqualTo(0)
        assertThat(store.inserts).isEqualTo(1)
        assertThat(downloadDao.selectById(row.id)).isNull()
        assertThat(sessions.read(mediaKey)).isNull()
    }

    @Test
    fun `an in-attempt published session whose document vanished retries instead of guessing`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val part = stagedFile()
        sessions.write(
            ExportSession(
                mediaKey,
                "content://media/external/downloads/99",
                "Track [00000000].flac",
                "audio/flac",
                payload.size.toLong(),
                ExportSessionPhase.PUBLISHED,
            ),
        )

        assertThat(publish(lease, row, part)).isEqualTo(DownloadExportOutcome.Retry)

        assertThat(store.inserts).isEqualTo(0)
        assertThat(store.documents).isEmpty()
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(downloadDao.selectById(row.id)!!.status).isEqualTo(DownloadStatus.ACTIVE.name)
    }

    @Test
    fun `recovery deletes an invisible pending document and lets the row retry`() = runDbTest {
        setup()
        val row = seedRow()
        val request = ExportNaming.request(mediaKey, "Track", "flac")
        val created = store.insertPending(request)!!
        sessions.write(
            ExportSession(mediaKey, created.uri, request.displayName, request.mimeType, payload.size.toLong(), ExportSessionPhase.PENDING),
        )

        val counts = publisher.recoverSessions()

        assertThat(counts).isEqualTo(ExportRecoveryCounts(cleanedPending = 1))
        assertThat(store.documents).isEmpty()
        assertThat(store.deletes).isEqualTo(1)
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(downloadDao.selectById(row.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
    }

    @Test
    fun `recovery keeps a published artifact that lost its row`() = runDbTest {
        setup()
        val request = ExportNaming.request(mediaKey, "Track", "flac")
        val created = store.insertPending(request)!!
        store.clearPending(created)
        sessions.write(
            ExportSession(mediaKey, created.uri, request.displayName, request.mimeType, payload.size.toLong(), ExportSessionPhase.PUBLISHED),
        )

        val counts = publisher.recoverSessions()

        assertThat(counts).isEqualTo(ExportRecoveryCounts())
        assertThat(store.documents).hasSize(1)
        assertThat(store.deletes).isEqualTo(0)
        assertThat(sessions.read(mediaKey)).isNull()
    }

    @Test
    fun `recovery reports a lost published document and leaves the row retrying`() = runDbTest {
        setup()
        val row = seedRow()
        sessions.write(
            ExportSession(
                mediaKey,
                "content://media/external/downloads/99",
                "Track [00000000].flac",
                "audio/flac",
                payload.size.toLong(),
                ExportSessionPhase.PUBLISHED,
            ),
        )

        val counts = publisher.recoverSessions()

        assertThat(counts).isEqualTo(ExportRecoveryCounts(lostDocuments = 1))
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(downloadDao.selectById(row.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
    }

    @Test
    fun `recovery drops an unreadable ledger file and keeps a pending document it cannot delete`() = runDbTest {
        setup()
        val row = seedRow()
        sessions.sessionFile(mediaKey).apply {
            parentFile?.mkdirs()
            writeText("v=9\nbroken")
        }
        val other = "stream:1:song-2:auto:auto"
        val request = ExportNaming.request(other, "Other", "mp3")
        val created = store.insertPending(request)!!
        sessions.write(
            ExportSession(other, created.uri, request.displayName, request.mimeType, 10, ExportSessionPhase.PENDING),
        )
        store.failDeletes = true

        val counts = publisher.recoverSessions()

        assertThat(counts.abandonedSessions).isEqualTo(1)
        assertThat(counts.cleanedPending).isEqualTo(0)
        assertThat(sessions.read(other)).isNotNull()
        assertThat(downloadDao.selectById(row.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
    }

    @Test
    fun `a public artifact survives internal provider deletion and a root change`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        publish(lease, row, stagedFile())
        val document = store.documents.values.single()
        val deletion = OfflineMediaDeletion(db, storage, silentTestLogger)

        deletion.deleteProviderOffline(1)
        val moved = storage.changeRoot(OfflineRootSelection.Volume("root-b"), OfflineRootWorkGate.NoWorkers)

        assertThat(moved).isInstanceOf(OfflineRootChangeResult.Completed::class.java)
        assertThat(store.deletes).isEqualTo(0)
        assertThat(store.documents).hasSize(1)
        assertThat(store.documents.values.single()).isSameInstanceAs(document)
        assertThat(document.pending).isFalse()
        assertThat(document.bytes).isEqualTo(payload)
    }

    @Test
    fun `a same-key request for another destination needs the explicit conversion command`() = runDbTest {
        setup()
        val enqueuer = DownloadEnqueuer(
            db = db,
            coordinator = coordinator,
            traitsSource = { ProviderTraits() },
            runGate = ProviderRunGate { ProviderStatus.OK },
            nowMs = { 1_000 },
        )

        assertThat(enqueuer.enqueue(providerId = 1, externalId = "song-1", title = "Track"))
            .isEqualTo(DownloadEnqueueResult.Enqueued)
        val row = downloadDao.selectByMediaKey(mediaKey)!!
        assertThat(row.exportTarget).isNull()

        val conflicting = enqueuer.enqueue(providerId = 1, externalId = "song-1", exportTarget = exportTarget)

        assertThat(conflicting).isEqualTo(DownloadEnqueueResult.NeedsDestinationChange(null))
        assertThat(downloadDao.selectByMediaKey(mediaKey)!!.exportTarget).isNull()
        assertThat(downloadDao.selectByMediaKey(mediaKey)!!.updatedAt).isEqualTo(1_000)

        assertThat(coordinator.convertDestination(row.id, exportTarget)).isTrue()
        val converted = downloadDao.selectByMediaKey(mediaKey)!!
        assertThat(converted.exportTarget).isEqualTo(exportTarget)
        assertThat(converted.updatedAt).isEqualTo(1_000)

        assertThat(enqueuer.enqueue(providerId = 1, externalId = "song-1", exportTarget = exportTarget))
            .isEqualTo(DownloadEnqueueResult.Enqueued)
        assertThat(downloadDao.selectAll(ProfileScope.Unrestricted)).hasSize(1)
    }

    @Test
    fun `recovery cleans an exact pending insert whose URI was never journaled`() = runDbTest {
        setup()
        seedRow()
        val request = ExportNaming.request(mediaKey, "Track", "flac")
        store.insertPending(request)

        val counts = publisher.recoverSessions()

        assertThat(counts.cleanedPending).isEqualTo(1)
        assertThat(store.documents).isEmpty()
        val lease = claim()
        assertThat(publish(lease, downloadDao.selectByMediaKey(mediaKey)!!, stagedFile()))
            .isInstanceOf(DownloadExportOutcome.Published::class.java)
        assertThat(store.inserts).isEqualTo(2)
        assertThat(store.deletes).isEqualTo(1)
        assertThat(store.visibleDocuments()).hasSize(1)
    }

    @Test
    fun `a visible document with a pending ledger is never truncated during retry`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val part = stagedFile()
        val request = ExportNaming.request(mediaKey, "Track", "flac")
        val created = store.insertPending(request)!!
        store.writeTruncating(created, part)
        store.clearPending(created)
        sessions.write(
            ExportSession(mediaKey, created.uri, request.displayName, request.mimeType, payload.size.toLong(), ExportSessionPhase.PENDING),
        )
        store.failDeletes = true
        val writesBefore = store.writes

        assertThat(publish(lease, row, part)).isEqualTo(DownloadExportOutcome.Retry)

        assertThat(store.writes).isEqualTo(writesBefore)
        assertThat(store.documents[created.uri]!!.bytes).isEqualTo(payload)
        assertThat(store.documents[created.uri]!!.pending).isFalse()
        assertThat(sessions.read(mediaKey)).isNotNull()
        assertThat(downloadDao.selectById(row.id)).isNotNull()

        store.failDeletes = false
        assertThat(publish(lease, row, part)).isInstanceOf(DownloadExportOutcome.Published::class.java)
        assertThat(store.visibleDocuments()).hasSize(1)
    }

    @Test
    fun `unknown document presence blocks recovery without dropping its ledger or row`() = runDbTest {
        setup()
        val row = seedRow()
        val request = ExportNaming.request(mediaKey, "Track", "flac")
        val created = store.insertPending(request)!!
        store.clearPending(created)
        sessions.write(
            ExportSession(mediaKey, created.uri, request.displayName, request.mimeType, payload.size.toLong(), ExportSessionPhase.PUBLISHED),
        )
        store.unknownPresence = true

        val failure = runCatching { publisher.recoverSessions() }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(sessions.read(mediaKey)).isNotNull()
        assertThat(downloadDao.selectById(row.id)).isNotNull()
        assertThat(store.documents).hasSize(1)
    }

    @Test
    fun `unknown pending recovery search blocks claims instead of risking a duplicate`() = runDbTest {
        setup()
        val row = seedRow()
        store.unknownPendingMatches = true

        val failure = runCatching { publisher.recoverSessions() }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(downloadDao.selectById(row.id)).isNotNull()
        assertThat(store.inserts).isEqualTo(0)
    }

    @Test
    fun `a vanished pending document is replaced instead of reused until parked`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val part = stagedFile()
        val request = ExportNaming.request(mediaKey, "Track", "flac")
        sessions.write(
            ExportSession(
                mediaKey,
                "content://media/external/downloads/99",
                request.displayName,
                request.mimeType,
                payload.size.toLong(),
                ExportSessionPhase.PENDING,
            ),
        )

        assertThat(publish(lease, row, part)).isInstanceOf(DownloadExportOutcome.Published::class.java)

        assertThat(store.inserts).isEqualTo(1)
        assertThat(store.visibleDocuments()).hasSize(1)
        assertThat(downloadDao.selectById(row.id)).isNull()
    }

    @Test
    fun `a clear pending failure cleans the document and preserves the retry`() = runDbTest {
        setup()
        val row = seedRow()
        val lease = claim()
        val part = stagedFile()
        store.failClears = true

        assertThat(publish(lease, row, part)).isEqualTo(DownloadExportOutcome.Retry)

        assertThat(store.documents).isEmpty()
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(downloadDao.selectById(row.id)).isNotNull()
        assertThat(part.exists()).isTrue()
    }

    @Test
    fun `an old published export cannot settle a row converted to offline`() = runDbTest {
        setup()
        val row = seedRow()
        val request = ExportNaming.request(mediaKey, "Track", "flac")
        val created = store.insertPending(request)!!
        store.clearPending(created)
        sessions.write(
            ExportSession(mediaKey, created.uri, request.displayName, request.mimeType, payload.size.toLong(), ExportSessionPhase.PUBLISHED),
        )
        assertThat(coordinator.convertDestination(row.id, null)).isTrue()

        val counts = publisher.recoverSessions()

        assertThat(counts.abandonedSessions).isEqualTo(1)
        assertThat(downloadDao.selectById(row.id)!!.exportTarget).isNull()
        assertThat(store.documents).hasSize(1)
        assertThat(sessions.read(mediaKey)).isNull()
    }

    @Test
    fun `coroutine cancellation during copy cleans the recorded document`() = runDbTest {
        setup()
        seedRow()
        val lease = claim()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        store.beforeWrite = {
            entered.complete(Unit)
            finish.await()
        }

        coroutineScope {
            val job = launch { publish(lease, downloadDao.selectByMediaKey(mediaKey)!!, stagedFile()) }
            withContext(Dispatchers.Default) { entered.await() }
            job.cancel()
            finish.complete(Unit)
            job.join()
        }

        assertThat(store.documents).isEmpty()
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(downloadDao.selectByMediaKey(mediaKey)).isNotNull()
    }

    @Test
    fun `coroutine cancellation after pending clears removes the visible document`() = runDbTest {
        setup()
        seedRow()
        val lease = claim()
        val cleared = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        store.afterClear = {
            cleared.complete(Unit)
            finish.await()
        }

        coroutineScope {
            val job = launch { publish(lease, downloadDao.selectByMediaKey(mediaKey)!!, stagedFile()) }
            withContext(Dispatchers.Default) { cleared.await() }
            job.cancel()
            finish.complete(Unit)
            job.join()
        }

        assertThat(store.documents).isEmpty()
        assertThat(sessions.read(mediaKey)).isNull()
        assertThat(downloadDao.selectByMediaKey(mediaKey)).isNotNull()
    }

    @Test
    fun `conversion preserves the whole retry row and wakes the runtime`() = runDbTest {
        setup()
        var starts = 0
        val triggering = DownloadWorkerCoordinator(
            repository = repository,
            settings = settings,
            runGate = ProviderRunGate { ProviderStatus.OK },
            workerCount = 1,
            nowMs = { 9_000 },
            workStart = { starts++ },
        )
        val seeded = seedRow(status = DownloadStatus.PENDING, exportTarget = null)
        downloadDao.upsert(
            seeded.copy(
                errorCount = 2,
                progressPercent = 42,
                lastError = "retry later",
                updatedAt = 5_000,
                autoRuleId = 7,
                tempCache = true,
            ),
        )
        val before = downloadDao.selectById(seeded.id)!!

        assertThat(triggering.convertDestination(seeded.id, exportTarget)).isTrue()

        assertThat(downloadDao.selectById(seeded.id)).isEqualTo(before.copy(exportTarget = exportTarget))
        assertThat(starts).isEqualTo(1)
    }

    @Test
    fun `conversion is refused while a worker owns the row`() = runDbTest {
        setup()
        val row = seedRow(exportTarget = null)
        claim()

        assertThat(coordinator.convertDestination(row.id, exportTarget)).isFalse()

        assertThat(downloadDao.selectById(row.id)!!.exportTarget).isNull()
        assertThat(coordinator.convertDestination(999L, exportTarget)).isFalse()
    }
}
