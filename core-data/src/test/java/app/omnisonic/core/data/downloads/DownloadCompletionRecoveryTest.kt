package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.silentTestLogger
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadCompletionRecoveryTest : DatabaseTestBase() {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private val journal = DownloadTransferJournal()
    private var now = 9_000L
    private var rootIndex = 0

    private lateinit var root: File
    private lateinit var storage: OfflineStorage

    private fun storage() {
        root = File(temp.root, "root-${rootIndex++}").apply { mkdirs() }
        storage = OfflineStorage(
            roots = OfflineRootSource { listOf(OfflineRoot("test-root", root)) },
            settings = DownloadQueueSettingsStore.inMemory(),
            usableSpace = { OfflinePathRules.MIN_FREE_SPACE_BYTES * 10 },
            writableProbe = { true },
        )
    }

    private fun recovery() = DownloadCompletionRecovery(
        db = db,
        storage = storage,
        publisher = OfflineMediaRepository(db),
        logger = silentTestLogger,
        clock = { now },
    )

    private suspend fun seedRow(
        mediaKey: String,
        status: DownloadStatus = DownloadStatus.PENDING,
        tempCache: Boolean = false,
    ): DownloadItemEntity {
        newProvider(id = 1)
        downloadDao.upsert(
            DownloadItemEntity(
                mediaKey = mediaKey,
                providerId = 1,
                status = status.name,
                title = "Track",
                tempCache = tempCache,
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
        return downloadDao.selectByMediaKey(mediaKey) ?: error("queue row missing")
    }

    private data class Orphan(
        val plan: OfflinePathPlan,
        val file: File,
        val part: File,
    )

    private fun orphan(
        externalId: String = "song-1",
        providerPath: String = "/music/library/track.flac",
        bytes: ByteArray = ByteArray(128) { (it % 251).toByte() },
        mediaKey: String? = DownloadKeys.mediaKey(1, externalId, null, null),
        totalBytes: Long? = bytes.size.toLong(),
        identityHash: String? = null,
    ): Orphan {
        val identity = OfflineMediaIdentity(providerId = 1, providerPath = providerPath)
        val plan = (storage.planPath(identity) as OfflinePathResult.Planned).plan
        val finalFile = File(root, plan.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        val part = File(root, plan.relativePartPath)
        journal.write(
            part,
            DownloadTransferState(
                identityHash = identityHash ?: plan.hash,
                totalBytes = totalBytes,
                mediaKey = mediaKey,
            ),
        )
        return Orphan(plan, finalFile, part)
    }

    @Test
    fun `a verified orphan publishes offline metadata and deletes its queue row`() = runDbTest {
        storage()
        val orphan = orphan()
        val row = seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null), tempCache = true)

        val counts = recovery().recoverOrphanedFinalFiles()

        assertThat(counts).isEqualTo(DownloadRecoveryCounts(adopted = 1, discarded = 0))
        assertThat(downloadDao.selectById(row.id)).isNull()
        val offline = offlineFileDao.selectBySourceKey(row.mediaKey)
        assertThat(offline).isNotNull()
        assertThat(offline!!.offlinePath).isEqualTo(orphan.plan.relativePath)
        assertThat(offline.sizeBytes).isEqualTo(orphan.file.length())
        assertThat(offline.tempCache).isTrue()
        assertThat(offline.externalId).isEqualTo("song-1")
        assertThat(offline.downloadedAt).isEqualTo(now)
        assertThat(orphan.file.exists()).isTrue()
        assertThat(journal.stateFile(orphan.part).exists()).isFalse()
    }

    @Test
    fun `a verified orphan whose row was paused mid-settlement is still adopted`() = runDbTest {
        storage()
        orphan()
        val row = seedRow(
            mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null),
            status = DownloadStatus.PAUSED,
        )

        val counts = recovery().recoverOrphanedFinalFiles()

        assertThat(counts.adopted).isEqualTo(1)
        assertThat(downloadDao.selectById(row.id)).isNull()
        assertThat(offlineFileDao.selectBySourceKey(row.mediaKey)).isNotNull()
    }

    @Test
    fun `a verified orphan without its queue row was cancelled and is quarantined`() = runDbTest {
        storage()
        val orphan = orphan()

        val counts = recovery().recoverOrphanedFinalFiles()

        assertThat(counts).isEqualTo(DownloadRecoveryCounts(adopted = 0, discarded = 1))
        assertThat(orphan.file.exists()).isFalse()
        assertThat(journal.stateFile(orphan.part).exists()).isFalse()
        assertThat(offlineFileDao.count()).isEqualTo(0)
    }

    @Test
    fun `an orphan with a mismatched expected length is deleted and its row retries`() = runDbTest {
        storage()
        val orphan = orphan(totalBytes = 999)
        val row = seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null))

        val counts = recovery().recoverOrphanedFinalFiles()

        assertThat(counts).isEqualTo(DownloadRecoveryCounts(adopted = 0, discarded = 1))
        assertThat(orphan.file.exists()).isFalse()
        assertThat(downloadDao.selectById(row.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(offlineFileDao.count()).isEqualTo(0)
    }

    @Test
    fun `an orphan without verification state is deleted`() = runDbTest {
        storage()
        val orphan = orphan()
        journal.delete(orphan.part)
        val row = seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null))

        val counts = recovery().recoverOrphanedFinalFiles()

        assertThat(counts.discarded).isEqualTo(1)
        assertThat(orphan.file.exists()).isFalse()
        assertThat(downloadDao.selectById(row.id)!!.status).isEqualTo(DownloadStatus.PENDING.name)
    }

    @Test
    fun `a zero-length orphan is never adopted`() = runDbTest {
        storage()
        val orphan = orphan(bytes = ByteArray(0), totalBytes = 0)
        seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null))

        val counts = recovery().recoverOrphanedFinalFiles()

        assertThat(counts.discarded).isEqualTo(1)
        assertThat(orphan.file.exists()).isFalse()
        assertThat(offlineFileDao.count()).isEqualTo(0)
    }

    @Test
    fun `an orphan with a foreign identity sidecar is deleted`() = runDbTest {
        storage()
        val orphan = orphan(identityHash = "b".repeat(64))
        seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null))

        val counts = recovery().recoverOrphanedFinalFiles()

        assertThat(counts.discarded).isEqualTo(1)
        assertThat(orphan.file.exists()).isFalse()
    }

    @Test
    fun `a published file is never touched`() = runDbTest {
        storage()
        val orphan = orphan()
        val row = seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null))
        offlineFileDao.upsert(
            OfflineFileEntity(
                providerId = 1,
                mediaType = "SONG",
                sourceKey = row.mediaKey,
                offlinePath = orphan.plan.relativePath,
                sizeBytes = orphan.file.length(),
                tempCache = false,
            ),
        )

        val counts = recovery().recoverOrphanedFinalFiles()

        assertThat(counts).isEqualTo(DownloadRecoveryCounts(adopted = 0, discarded = 0))
        assertThat(orphan.file.exists()).isTrue()
        assertThat(downloadDao.selectById(row.id)).isNotNull()
        assertThat(offlineFileDao.count()).isEqualTo(1)
    }

    @Test
    fun `an unavailable root skips the pass without deleting anything`() = runDbTest {
        storage()
        val orphan = orphan()
        seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null))
        val unavailable = DownloadCompletionRecovery(
            db = db,
            storage = OfflineStorage(
                roots = OfflineRootSource { emptyList() },
                settings = DownloadQueueSettingsStore.inMemory(),
                usableSpace = { 0L },
                writableProbe = { true },
            ),
            publisher = OfflineMediaRepository(db),
            logger = silentTestLogger,
            clock = { now },
        )

        val counts = unavailable.recoverOrphanedFinalFiles()

        assertThat(counts).isEqualTo(DownloadRecoveryCounts(adopted = 0, discarded = 0))
        assertThat(orphan.file.exists()).isTrue()
        assertThat(journal.stateFile(orphan.part).exists()).isTrue()
    }

    @Test
    fun `an orphan keyed with a colon-containing external id adopts with the exact external id`() = runDbTest {
        storage()
        orphan(externalId = "disc:one")
        val row = seedRow(mediaKey = DownloadKeys.mediaKey(1, "disc:one", null, null))

        val counts = recovery().recoverOrphanedFinalFiles()

        assertThat(counts.adopted).isEqualTo(1)
        assertThat(downloadDao.selectById(row.id)).isNull()
        assertThat(offlineFileDao.selectBySourceKey(row.mediaKey)!!.externalId).isEqualTo("disc:one")
    }

    @Test
    fun `an orphan owned by an export row is never published as offline media`() = runDbTest {
        storage()
        val orphan = orphan()
        newProvider(id = 1)
        downloadDao.upsert(
            DownloadItemEntity(
                mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null),
                providerId = 1,
                status = DownloadStatus.PENDING.name,
                title = "Track",
                exportTarget = "MEDIASTORE_DOWNLOADS",
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )

        val counts = recovery().recoverOrphanedFinalFiles()

        assertThat(counts).isEqualTo(DownloadRecoveryCounts(adopted = 0, discarded = 1))
        assertThat(orphan.file.exists()).isFalse()
        assertThat(offlineFileDao.count()).isEqualTo(0)
        assertThat(downloadDao.selectByMediaKey(DownloadKeys.mediaKey(1, "song-1", null, null))).isNotNull()
    }

    @Test
    fun `an orphan whose sidecar lost its queue key is quarantined rather than guessed`() = runDbTest {
        storage()
        val orphan = orphan(mediaKey = null)
        val row = seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null))

        val counts = recovery().recoverOrphanedFinalFiles()

        assertThat(counts).isEqualTo(DownloadRecoveryCounts(adopted = 0, discarded = 1))
        assertThat(orphan.file.exists()).isFalse()
        assertThat(downloadDao.selectById(row.id)).isNotNull()
        assertThat(offlineFileDao.count()).isEqualTo(0)
    }
}
