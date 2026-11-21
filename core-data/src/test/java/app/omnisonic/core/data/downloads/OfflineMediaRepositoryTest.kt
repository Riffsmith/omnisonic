package app.omnisonic.core.data.downloads

import android.database.sqlite.SQLiteException
import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineMediaRepositoryTest : DatabaseTestBase() {

    private var now = 5_000L

    private suspend fun seedRow(
        mediaKey: String,
        providerId: Long = 1,
        status: DownloadStatus = DownloadStatus.ACTIVE,
        tempCache: Boolean = false,
        autoRuleId: Long? = null,
        bitrate: Int? = null,
    ): DownloadItemEntity {
        newProvider(id = providerId)
        downloadDao.upsert(
            DownloadItemEntity(
                mediaKey = mediaKey,
                providerId = providerId,
                status = status.name,
                title = "Track",
                imagePath = "/img/1",
                transcodeBitrateKbps = bitrate,
                tempCache = tempCache,
                autoRuleId = autoRuleId,
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
        return downloadDao.selectByMediaKey(mediaKey) ?: error("queue row missing")
    }

    private fun publication(item: DownloadItemEntity, path: String = "OfflineMedias/a/hash.flac", size: Long = 4_096) =
        offlinePublicationRow(item, path, size, now)

    @Test
    fun `publication writes the offline row and deletes the queue row in one commit`() = runDbTest {
        val item = seedRow(
            mediaKey = DownloadKeys.mediaKey(1, "song-1", 192, "mp3"),
            tempCache = true,
            autoRuleId = 7,
            bitrate = 192,
        )
        val repository = OfflineMediaRepository(db)

        val result = repository.publishAndSettleActive(item, publication(item))

        assertThat(result).isEqualTo(PublishSettle.APPLIED)
        assertThat(downloadDao.selectById(item.id)).isNull()
        val offline = offlineFileDao.selectBySourceKey(item.mediaKey)
        assertThat(offline).isNotNull()
        assertThat(offline!!.providerId).isEqualTo(1)
        assertThat(offline.mediaType).isEqualTo("SONG")
        assertThat(offline.offlinePath).isEqualTo("OfflineMedias/a/hash.flac")
        assertThat(offline.sizeBytes).isEqualTo(4_096)
        assertThat(offline.title).isEqualTo("Track")
        assertThat(offline.imagePath).isEqualTo("/img/1")
        assertThat(offline.bitrateKbps).isEqualTo(192)
        assertThat(offline.autoRule).isEqualTo("7")
        assertThat(offline.downloadedAt).isEqualTo(now)
        assertThat(offline.tempCache).isTrue()
        assertThat(offline.externalId).isEqualTo("song-1")
    }

    @Test
    fun `a failed offline write rolls back the queue deletion without a phantom row`() = runDbTest {
        val item = seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null))
        val repository = OfflineMediaRepository(db) { throw SQLiteException("disk I/O") }

        val result = repository.publishAndSettleActive(item, publication(item))

        assertThat(result).isEqualTo(PublishSettle.FAILED)
        assertThat(downloadDao.selectById(item.id)!!.status).isEqualTo(DownloadStatus.ACTIVE.name)
        assertThat(offlineFileDao.count()).isEqualTo(0)
    }

    @Test
    fun `a paused or deleted queue row publishes nothing`() = runDbTest {
        val paused = seedRow(
            mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null),
            status = DownloadStatus.PAUSED,
        )
        val repository = OfflineMediaRepository(db)

        assertThat(repository.publishAndSettleActive(paused, publication(paused)))
            .isEqualTo(PublishSettle.ROW_NOT_ACTIVE)
        assertThat(downloadDao.selectById(paused.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)

        downloadDao.deleteById(paused.id)
        assertThat(repository.publishAndSettleActive(paused, publication(paused)))
            .isEqualTo(PublishSettle.ROW_NOT_ACTIVE)
        assertThat(offlineFileDao.count()).isEqualTo(0)
    }

    @Test
    fun `permanent and rolling variants share one provider external lookup`() = runDbTest {
        val permanent = seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", 128, "mp3"), bitrate = 128)
        val rolling = seedRow(
            mediaKey = DownloadKeys.mediaKey(1, "song-1", 320, "mp3"),
            tempCache = true,
            bitrate = 320,
        )
        val repository = OfflineMediaRepository(db)

        assertThat(repository.publishAndSettleActive(permanent, publication(permanent, "OfflineMedias/a/low.mp3")))
            .isEqualTo(PublishSettle.APPLIED)
        assertThat(repository.publishAndSettleActive(rolling, publication(rolling, "OfflineMedias/b/high.mp3", 8_192)))
            .isEqualTo(PublishSettle.APPLIED)

        val variants = offlineFileDao.selectByProviderAndExternal(1, "song-1", ProfileScope.Unrestricted)
        assertThat(variants.map { it.offlinePath }).containsExactly("OfflineMedias/a/low.mp3", "OfflineMedias/b/high.mp3")
        assertThat(variants.map { it.tempCache }).containsExactly(false, true).inOrder()
        assertThat(offlineFileDao.selectByProviderAndExternal(1, "song-1", ProfileScope().excluding(listOf(1L)))).isEmpty()
        assertThat(offlineFileDao.selectByProviderAndExternal(1, "other", ProfileScope.Unrestricted)).isEmpty()
        assertThat(offlineFileDao.selectByOfflinePath("OfflineMedias/b/high.mp3")!!.tempCache).isTrue()
    }

    @Test
    fun `a replacement variant publishes beside the old variant without an offline gap`() = runDbTest {
        val old = seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", 128, "mp3"), bitrate = 128)
        val repository = OfflineMediaRepository(db)
        assertThat(repository.publishAndSettleActive(old, publication(old, "OfflineMedias/a/low.mp3")))
            .isEqualTo(PublishSettle.APPLIED)

        val replacement = seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", 320, "mp3"), bitrate = 320)
        assertThat(repository.publishAndSettleActive(replacement, publication(replacement, "OfflineMedias/b/high.mp3")))
            .isEqualTo(PublishSettle.APPLIED)

        val retained = offlineFileDao.selectByProviderAndExternal(1, "song-1", ProfileScope.Unrestricted)
        assertThat(retained).hasSize(2)
        assertThat(retained.map { it.offlinePath }).containsExactly("OfflineMedias/a/low.mp3", "OfflineMedias/b/high.mp3")
        assertThat(offlineFileDao.selectBySourceKey(old.mediaKey)!!.sizeBytes).isEqualTo(4_096)
    }

    @Test
    fun `recovered publication settles a row in any known state atomically`() = runDbTest {
        val paused = seedRow(
            mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null),
            status = DownloadStatus.PAUSED,
        )
        val repository = OfflineMediaRepository(db)

        assertThat(repository.publishAndSettleRecovered(paused.id, publication(paused))).isTrue()
        assertThat(downloadDao.selectById(paused.id)).isNull()
        assertThat(offlineFileDao.selectBySourceKey(paused.mediaKey)).isNotNull()
    }

    @Test
    fun `recovered publication refuses an unknown raw status without a phantom row`() = runDbTest {
        val item = seedRow(mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null))
        db.openHelper.writableDatabase.execSQL("UPDATE download_queue SET status = 'RUNNING' WHERE id = ${item.id}")
        val repository = OfflineMediaRepository(db)

        assertThat(repository.publishAndSettleRecovered(item.id, publication(item))).isFalse()
        assertThat(downloadDao.selectById(item.id)!!.status).isEqualTo("RUNNING")
        assertThat(offlineFileDao.count()).isEqualTo(0)
    }

    @Test
    fun `a failed recovered write rolls back the queue deletion`() = runDbTest {
        val item = seedRow(
            mediaKey = DownloadKeys.mediaKey(1, "song-1", null, null),
            status = DownloadStatus.PAUSED,
        )
        val repository = OfflineMediaRepository(db) { throw SQLiteException("disk I/O") }

        assertThat(repository.publishAndSettleRecovered(item.id, publication(item))).isFalse()
        assertThat(downloadDao.selectById(item.id)!!.status).isEqualTo(DownloadStatus.PAUSED.name)
        assertThat(offlineFileDao.count()).isEqualTo(0)
    }

    @Test
    fun `publication keeps a colon-containing external id exact`() = runDbTest {
        val item = seedRow(mediaKey = DownloadKeys.mediaKey(1, "disc:one", null, null))
        val repository = OfflineMediaRepository(db)

        assertThat(repository.publishAndSettleActive(item, publication(item))).isEqualTo(PublishSettle.APPLIED)

        val offline: OfflineFileEntity = offlineFileDao.selectBySourceKey(item.mediaKey)!!
        assertThat(offline.externalId).isEqualTo("disc:one")
        assertThat(offlineFileDao.selectByProviderAndExternal(1, "disc:one", ProfileScope.Unrestricted)).hasSize(1)
    }
}
