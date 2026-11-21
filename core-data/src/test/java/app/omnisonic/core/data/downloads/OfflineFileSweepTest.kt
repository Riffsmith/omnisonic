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
class OfflineFileSweepTest : DatabaseTestBase() {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private val journal = DownloadTransferJournal()

    private lateinit var settings: DownloadQueueSettingsStore
    private lateinit var root: File
    private lateinit var storage: OfflineStorage

    private fun storage(): OfflineStorage {
        root = File(temp.root, "root").apply { mkdirs() }
        settings = DownloadQueueSettingsStore.inMemory()
        storage = storageOver(OfflineRootSource { listOf(OfflineRoot("test-root", root)) })
        File(root, OfflinePathRules.MEDIA_DIRECTORY_NAME).mkdirs()
        return storage
    }

    private fun storageOver(roots: OfflineRootSource): OfflineStorage = OfflineStorage(
        roots = roots,
        settings = settings,
        usableSpace = { OfflinePathRules.MIN_FREE_SPACE_BYTES * 10 },
        writableProbe = { true },
    )

    private fun sweep(over: OfflineStorage = storage) =
        OfflineFileSweep(db = db, storage = over, logger = silentTestLogger)

    private data class Seeded(
        val row: OfflineFileEntity,
        val file: File,
        val plan: OfflinePathPlan,
    )

    private suspend fun offline(
        providerId: Long = 1,
        externalId: String = "song-1",
        bytes: ByteArray? = ByteArray(64) { (it % 251).toByte() },
    ): Seeded {
        val plan = (
            storage.planPath(
                OfflineMediaIdentity(providerId, "/music/library/$externalId.flac"),
            ) as OfflinePathResult.Planned
            ).plan
        val file = File(root, plan.relativePath)
        if (bytes != null) {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        val id = offlineFileDao.upsert(
            OfflineFileEntity(
                providerId = providerId,
                mediaType = "SONG",
                sourceKey = DownloadKeys.mediaKey(providerId, externalId, null, null),
                offlinePath = plan.relativePath,
                sizeBytes = bytes?.size?.toLong(),
            ),
        )
        return Seeded(offlineFileDao.selectById(id)!!, file, plan)
    }

    private suspend fun queue(
        mediaKey: String = DownloadKeys.mediaKey(1, "song-1", null, null),
        status: DownloadStatus = DownloadStatus.PENDING,
    ) {
        downloadDao.upsert(
            DownloadItemEntity(
                mediaKey = mediaKey,
                providerId = 1,
                status = status.name,
                title = "Track",
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
    }

    private fun part(
        externalId: String = "song-1",
        mediaKey: String? = null,
        withState: Boolean = true,
    ): File {
        val plan = (
            storage.planPath(OfflineMediaIdentity(1, "/music/library/$externalId.flac"))
                as OfflinePathResult.Planned
            ).plan
        val part = File(root, plan.relativePartPath).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(16))
        }
        if (withState) {
            journal.write(
                part,
                DownloadTransferState(
                    identityHash = plan.hash,
                    totalBytes = 32,
                    mediaKey = mediaKey,
                ),
            )
        }
        return part
    }

    @Test
    fun `missing files remove their rows while present media stays`() = runDbTest {
        storage()
        val present = offline(externalId = "keep")
        val missing = offline(externalId = "gone", bytes = null)

        val counts = sweep().sweep()

        assertThat(counts.missingRows).isEqualTo(1)
        assertThat(offlineFileDao.selectById(present.row.id)).isNotNull()
        assertThat(offlineFileDao.selectById(missing.row.id)).isNull()
        assertThat(present.file.isFile).isTrue()
        assertThat(missing.file.exists()).isFalse()
    }

    @Test
    fun `a zero-length file removes both the file and its row`() = runDbTest {
        storage()
        val zero = offline(externalId = "empty", bytes = ByteArray(0))

        val counts = sweep().sweep()

        assertThat(counts.zeroRows).isEqualTo(1)
        assertThat(offlineFileDao.selectById(zero.row.id)).isNull()
        assertThat(zero.file.exists()).isFalse()
    }

    @Test
    fun `an unavailable root skips the sweep without deleting anything`() = runDbTest {
        storage()
        val seeded = offline(externalId = "gone", bytes = null)

        val counts = sweep(storageOver(OfflineRootSource { emptyList() })).sweep()

        assertThat(counts).isEqualTo(OfflineSweepCounts())
        assertThat(offlineFileDao.selectById(seeded.row.id)).isNotNull()
    }

    @Test
    fun `an absent media tree is not treated as an empty library`() = runDbTest {
        storage()
        val seeded = offline()
        File(root, OfflinePathRules.MEDIA_DIRECTORY_NAME).deleteRecursively()

        val counts = sweep().sweep()

        assertThat(counts).isEqualTo(OfflineSweepCounts())
        assertThat(offlineFileDao.selectById(seeded.row.id)).isNotNull()
    }

    @Test
    fun `a part whose queue row is gone is removed with its sidecar`() = runDbTest {
        storage()
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)
        val part = part(mediaKey = key)

        val counts = sweep().sweep()

        assertThat(counts.staleParts).isEqualTo(1)
        assertThat(part.exists()).isFalse()
        assertThat(journal.stateFile(part).exists()).isFalse()
    }

    @Test
    fun `a part owned by a pending row is preserved`() = runDbTest {
        storage()
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)
        queue(mediaKey = key)
        val part = part(mediaKey = key)

        val counts = sweep().sweep()

        assertThat(counts.staleParts).isEqualTo(0)
        assertThat(part.isFile).isTrue()
        assertThat(journal.stateFile(part).isFile).isTrue()
    }

    @Test
    fun `a part owned by an active row is preserved`() = runDbTest {
        storage()
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)
        queue(mediaKey = key, status = DownloadStatus.ACTIVE)
        val part = part(mediaKey = key)

        val counts = sweep().sweep()

        assertThat(counts.staleParts).isEqualTo(0)
        assertThat(part.isFile).isTrue()
    }

    @Test
    fun `a part owned by a failed row keeps its resumable bytes`() = runDbTest {
        storage()
        val key = DownloadKeys.mediaKey(1, "song-1", null, null)
        queue(mediaKey = key, status = DownloadStatus.FAILED)
        val part = part(mediaKey = key)

        val counts = sweep().sweep()

        assertThat(counts.staleParts).isEqualTo(0)
        assertThat(part.isFile).isTrue()
        assertThat(journal.stateFile(part).isFile).isTrue()
    }

    @Test
    fun `a symlinked artifact is skipped instead of followed or deleted`() = runDbTest {
        storage()
        val outside = File(temp.root, "outside.part").apply { writeBytes(ByteArray(8)) }
        val link = File(root, "${OfflinePathRules.MEDIA_DIRECTORY_NAME}/a/link.part").apply {
            parentFile?.mkdirs()
            java.nio.file.Files.createSymbolicLink(toPath(), outside.toPath())
        }

        val counts = sweep().sweep()

        assertThat(counts).isEqualTo(OfflineSweepCounts())
        assertThat(java.nio.file.Files.isSymbolicLink(link.toPath())).isTrue()
        assertThat(outside.isFile).isTrue()
    }

    @Test
    fun `a part without verification state is removed`() = runDbTest {
        storage()
        val part = part(withState = false)

        val counts = sweep().sweep()

        assertThat(counts.staleParts).isEqualTo(1)
        assertThat(part.exists()).isFalse()
    }

    @Test
    fun `an orphaned sidecar is removed while the published file stays`() = runDbTest {
        storage()
        val seeded = offline()
        val part = File(root, seeded.plan.relativePartPath)
        journal.write(
            part,
            DownloadTransferState(
                identityHash = seeded.plan.hash,
                totalBytes = seeded.file.length(),
                mediaKey = null,
            ),
        )

        val counts = sweep().sweep()

        assertThat(counts.staleStateFiles).isEqualTo(1)
        assertThat(journal.stateFile(part).exists()).isFalse()
        assertThat(offlineFileDao.selectById(seeded.row.id)).isNotNull()
        assertThat(seeded.file.isFile).isTrue()
    }

    @Test
    fun `a half-written state temp is removed`() = runDbTest {
        storage()
        val tempState = File(root, "OfflineMedias/a/abc.part.state.tmp").apply {
            parentFile?.mkdirs()
            writeText("v=1")
        }

        val counts = sweep().sweep()

        assertThat(counts.staleStateFiles).isEqualTo(1)
        assertThat(tempState.exists()).isFalse()
    }

    @Test
    fun `another provider's rows and files are untouched`() = runDbTest {
        storage()
        newProvider(id = 1)
        newProvider(id = 2)
        offline(providerId = 1, externalId = "gone", bytes = null)
        val other = offline(providerId = 2, externalId = "keep", bytes = ByteArray(8))

        val counts = sweep().sweep()

        assertThat(counts.missingRows).isEqualTo(1)
        assertThat(offlineFileDao.selectByProvider(2)).hasSize(1)
        assertThat(offlineFileDao.selectById(other.row.id)).isNotNull()
        assertThat(other.file.isFile).isTrue()
        assertThat(offlineFileDao.selectByProvider(1)).isEmpty()
    }

    @Test
    fun `a file on another root is never deleted by the active-root sweep`() = runDbTest {
        storage()
        val otherRoot = File(temp.root, "other-root").apply { mkdirs() }
        val relative = "${OfflinePathRules.MEDIA_DIRECTORY_NAME}/b/foreign.flac"
        val foreign = File(otherRoot, relative).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8))
        }
        offlineFileDao.upsert(
            OfflineFileEntity(
                providerId = 1,
                mediaType = "SONG",
                sourceKey = "foreign",
                offlinePath = relative,
                sizeBytes = 8,
            ),
        )
        val bothRoots = storageOver(
            OfflineRootSource {
                listOf(
                    OfflineRoot("test-root", root),
                    OfflineRoot("other-root", otherRoot),
                )
            },
        )

        val counts = sweep(bothRoots).sweep()

        assertThat(counts.missingRows).isEqualTo(1)
        assertThat(foreign.isFile).isTrue()
    }

    @Test
    fun `a final file without a row is left to the orphan pass`() = runDbTest {
        storage()
        val file = File(root, "${OfflinePathRules.MEDIA_DIRECTORY_NAME}/a/orphan.flac").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8))
        }

        val counts = sweep().sweep()

        assertThat(counts).isEqualTo(OfflineSweepCounts())
        assertThat(file.isFile).isTrue()
    }
}
