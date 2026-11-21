package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.silentTestLogger
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
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
class OfflineMediaDeletionTest : DatabaseTestBase() {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private lateinit var settings: DownloadQueueSettingsStore
    private lateinit var root: File
    private lateinit var storage: OfflineStorage

    private fun storage(): OfflineStorage {
        root = File(temp.root, "root").apply { mkdirs() }
        settings = DownloadQueueSettingsStore.inMemory()
        storage = OfflineStorage(
            roots = OfflineRootSource { listOf(OfflineRoot("test-root", root)) },
            settings = settings,
            usableSpace = { OfflinePathRules.MIN_FREE_SPACE_BYTES * 10 },
            writableProbe = { true },
        )
        return storage
    }

    private fun unavailableStorage(): OfflineStorage = OfflineStorage(
        roots = OfflineRootSource { emptyList() },
        settings = settings,
        usableSpace = { 0L },
        writableProbe = { true },
    )

    private fun deletion(
        cancellation: DownloadQueueCancellation = DownloadQueueCancellation.Direct,
        over: OfflineStorage = storage,
    ) = OfflineMediaDeletion(db, over, silentTestLogger, cancellation)

    private fun coordinator(): DownloadWorkerCoordinator = DownloadWorkerCoordinator(
        repository = DownloadQueueRepository(downloadDao),
        settings = settings,
        runGate = ProviderRunGate { ProviderStatus.OK },
        workerCount = 1,
        nowMs = { 4_000L },
    )

    private data class Seeded(
        val row: OfflineFileEntity,
        val file: File,
        val plan: OfflinePathPlan,
        val mediaKey: String,
        val queueId: Long?,
    )

    private suspend fun seed(
        providerId: Long = 1,
        externalId: String = "song-1",
        status: DownloadStatus? = DownloadStatus.PENDING,
        bitrateKbps: Int? = null,
        bytes: ByteArray = ByteArray(32) { (it % 251).toByte() },
    ): Seeded {
        val mediaKey = DownloadKeys.mediaKey(providerId, externalId, bitrateKbps, null)
        val plan = (
            storage.planPath(
                OfflineMediaIdentity(
                    providerId = providerId,
                    providerPath = "/music/library/$externalId.flac",
                    transcodeBitrateKbps = bitrateKbps,
                ),
            ) as OfflinePathResult.Planned
            ).plan
        val file = File(root, plan.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        val queueId = if (status != null) {
            downloadDao.upsert(
                DownloadItemEntity(
                    mediaKey = mediaKey,
                    providerId = providerId,
                    status = status.name,
                    title = "Track",
                    createdAt = 1_000,
                    updatedAt = 1_000,
                ),
            )
        } else {
            null
        }
        val id = offlineFileDao.upsert(
            OfflineFileEntity(
                providerId = providerId,
                mediaType = "SONG",
                sourceKey = mediaKey,
                offlinePath = plan.relativePath,
                sizeBytes = bytes.size.toLong(),
            ),
        )
        return Seeded(
            row = offlineFileDao.selectById(id)!!,
            file = file,
            plan = plan,
            mediaKey = mediaKey,
            queueId = queueId,
        )
    }

    @Test
    fun `deleting an offline row removes its file and cancels the equivalent queue row`() = runDbTest {
        storage()
        newProvider(id = 1)
        val seeded = seed()
        var cancelled: Long? = null

        val result = deletion(
            DownloadQueueCancellation { rowId ->
                cancelled = rowId
                downloadDao.deleteById(rowId) == 1
            },
        ).deleteOffline(seeded.row.id)

        assertThat(result).isEqualTo(
            OfflineDeleteResult(deleted = true, cancelledQueueItem = true, fileRemoved = true),
        )
        assertThat(cancelled).isEqualTo(seeded.queueId)
        assertThat(downloadDao.selectById(seeded.queueId!!)).isNull()
        assertThat(offlineFileDao.selectById(seeded.row.id)).isNull()
        assertThat(seeded.file.exists()).isFalse()
    }

    @Test
    fun `a late settlement cannot resurrect a deleted offline row`() = runDbTest {
        storage()
        newProvider(id = 1)
        val seeded = seed()
        val coordinator = coordinator()
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!
        val item = downloadDao.selectById(lease.rowId)!!

        val result = deletion(
            DownloadQueueCancellation { rowId -> coordinator.cancel(rowId) },
        ).deleteOffline(seeded.row.id)

        assertThat(result.cancelledQueueItem).isTrue()
        assertThat(coordinator.interruptionFor(lease).await())
            .isEqualTo(DownloadInterruption.CANCEL)
        assertThat(offlineFileDao.selectById(seeded.row.id)).isNull()
        assertThat(seeded.file.exists()).isFalse()

        val settlement = coordinator.completeWith(lease) { _ ->
            OfflineMediaRepository(db).publishAndSettleActive(
                item,
                offlinePublicationRow(item, seeded.plan.relativePath, 32, 5_000),
            )
        }

        assertThat(settlement).isEqualTo(CompletionSettlement.RowAlreadySettled)
        coordinator.release(lease)
        assertThat(offlineFileDao.count()).isEqualTo(0)
    }

    @Test
    fun `deleting an unknown offline id is a no-op`() = runDbTest {
        storage()

        val result = deletion().deleteOffline(999)

        assertThat(result.deleted).isFalse()
        assertThat(result.cancelledQueueItem).isFalse()
        assertThat(result.fileRemoved).isFalse()
        assertThat(offlineFileDao.count()).isEqualTo(0)
    }

    @Test
    fun `deleting one variant keeps the other variant playable`() = runDbTest {
        storage()
        newProvider(id = 1)
        val standard = seed(externalId = "song-1")
        val high = seed(externalId = "song-1", bitrateKbps = 320)

        val result = deletion().deleteOffline(standard.row.id)

        assertThat(result.fileRemoved).isTrue()
        assertThat(standard.file.exists()).isFalse()
        assertThat(offlineFileDao.selectById(standard.row.id)).isNull()
        assertThat(offlineFileDao.selectById(high.row.id)).isNotNull()
        assertThat(high.file.isFile).isTrue()
    }

    @Test
    fun `deleting with an unavailable root removes the row and leaves the file for recovery`() = runDbTest {
        storage()
        newProvider(id = 1)
        val seeded = seed()

        val result = deletion(over = unavailableStorage()).deleteOffline(seeded.row.id)

        assertThat(result.deleted).isTrue()
        assertThat(result.fileRemoved).isFalse()
        assertThat(offlineFileDao.selectById(seeded.row.id)).isNull()
        assertThat(seeded.file.isFile).isTrue()
    }

    @Test
    fun `provider deletion removes only that provider's files and the cascade stays intact`() = runDbTest {
        storage()
        newProvider(id = 1)
        newProvider(id = 2)
        val owned = seed(providerId = 1, externalId = "owned")
        val other = seed(providerId = 2, externalId = "other")

        val counts = deletion().deleteProviderOffline(1)

        assertThat(counts).isEqualTo(OfflineProviderDeleteCounts(rows = 1, filesRemoved = 1))
        assertThat(owned.file.exists()).isFalse()
        assertThat(offlineFileDao.selectByProvider(1)).isEmpty()
        assertThat(offlineFileDao.selectById(other.row.id)).isNotNull()
        assertThat(other.file.isFile).isTrue()

        providerDao.deleteById(1)

        assertThat(downloadDao.selectById(owned.queueId!!)).isNull()
        assertThat(offlineFileDao.selectByProvider(1)).isEmpty()
        assertThat(offlineFileDao.selectByProvider(2)).hasSize(1)
        assertThat(other.file.isFile).isTrue()
    }

    @Test
    fun `a provider delete cannot be undone by an active worker's late publication`() = runDbTest {
        storage()
        newProvider(id = 1)
        val seeded = seed()
        val coordinator = coordinator()
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!
        val item = downloadDao.selectById(lease.rowId)!!

        deletion().deleteProviderOffline(1)
        providerDao.deleteById(1)
        val settlement = coordinator.completeWith(lease) { _ ->
            OfflineMediaRepository(db).publishAndSettleActive(
                item,
                offlinePublicationRow(item, seeded.plan.relativePath, 32, 5_000),
            )
        }

        assertThat(settlement).isEqualTo(CompletionSettlement.RowAlreadySettled)
        coordinator.release(lease)
        assertThat(offlineFileDao.count()).isEqualTo(0)
        assertThat(seeded.file.exists()).isFalse()
    }

    @Test
    fun `provider deletion with an unavailable root keeps the file for recovery`() = runDbTest {
        storage()
        newProvider(id = 1)
        val seeded = seed()

        val counts = deletion(over = unavailableStorage()).deleteProviderOffline(1)

        assertThat(counts).isEqualTo(OfflineProviderDeleteCounts(rows = 1, filesRemoved = 0))
        assertThat(offlineFileDao.selectByProvider(1)).isEmpty()
        assertThat(seeded.file.isFile).isTrue()
    }
}
