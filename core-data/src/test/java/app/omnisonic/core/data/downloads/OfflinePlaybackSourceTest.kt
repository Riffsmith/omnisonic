package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.entity.OfflineFileEntity
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
class OfflinePlaybackSourceTest : DatabaseTestBase() {

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
        File(root, OfflinePathRules.MEDIA_DIRECTORY_NAME).mkdirs()
        return storage
    }

    private fun source(over: OfflineStorage = storage) = OfflinePlaybackSource(db.offlineFileDao(), over)

    private data class Seeded(val row: OfflineFileEntity, val file: File)

    private suspend fun offline(
        providerId: Long = 1,
        externalId: String = "song-1",
        bitrate: Int? = null,
        format: String? = null,
        title: String? = "Offline Title",
        downloadedAt: Long? = 4_000,
        bytes: ByteArray? = ByteArray(64) { (it % 251).toByte() },
        directory: Boolean = false,
    ): Seeded {
        newProvider(id = providerId)
        val plan = (
            storage.planPath(
                OfflineMediaIdentity(providerId, "/music/library/$externalId.flac", bitrate, format),
            ) as OfflinePathResult.Planned
            ).plan
        val file = File(root, plan.relativePath)
        when {
            directory -> file.mkdirs()
            bytes != null -> {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
        }
        val id = offlineFileDao.upsert(
            OfflineFileEntity(
                providerId = providerId,
                mediaType = "SONG",
                sourceKey = DownloadKeys.mediaKey(providerId, externalId, bitrate, format),
                offlinePath = plan.relativePath,
                sizeBytes = bytes?.size?.toLong(),
                title = title,
                bitrateKbps = bitrate,
                downloadedAt = downloadedAt,
                externalId = externalId,
            ),
        )
        return Seeded(offlineFileDao.selectById(id)!!, file)
    }

    @Test
    fun `readable files are offered with the offline row metadata`() = runDbTest {
        storage()
        val seeded = offline(bitrate = 320, format = "mp3")

        val files = source().filesFor(1, "song-1")

        assertThat(files).hasSize(1)
        val file = files.single()
        assertThat(file.id).isEqualTo(seeded.row.id)
        assertThat(file.sourceKey).isEqualTo(DownloadKeys.mediaKey(1, "song-1", 320, "mp3"))
        assertThat(file.uri).isEqualTo("file://${seeded.file.absolutePath}")
        assertThat(file.sizeBytes).isEqualTo(64)
        assertThat(file.title).isEqualTo("Offline Title")
        assertThat(file.bitrateKbps).isEqualTo(320)
        assertThat(file.downloadedAtMs).isEqualTo(4_000)
    }

    @Test
    fun `every readable variant of one song is offered`() = runDbTest {
        storage()
        offline(bitrate = 128, downloadedAt = 2_000)
        offline(bitrate = 320, downloadedAt = 5_000)

        val files = source().filesFor(1, "song-1")

        assertThat(files.map { it.bitrateKbps }).containsExactly(128, 320).inOrder()
        assertThat(files.map { it.sourceKey }).containsExactly(
            DownloadKeys.mediaKey(1, "song-1", 128, null),
            DownloadKeys.mediaKey(1, "song-1", 320, null),
        ).inOrder()
    }

    @Test
    fun `missing zero byte and directory rows are never offered`() = runDbTest {
        storage()
        offline(externalId = "missing", bytes = null)
        offline(externalId = "empty", bytes = ByteArray(0))
        offline(externalId = "directory", bytes = null, directory = true)

        assertThat(source().filesFor(1, "missing")).isEmpty()
        assertThat(source().filesFor(1, "empty")).isEmpty()
        assertThat(source().filesFor(1, "directory")).isEmpty()
    }

    @Test
    fun `other songs and providers are not offered`() = runDbTest {
        storage()
        offline(externalId = "song-1")
        offline(externalId = "song-2", downloadedAt = 9_000)
        offline(providerId = 2, externalId = "song-1", downloadedAt = 9_000)

        val files = source().filesFor(1, "song-1")

        assertThat(files).hasSize(1)
        assertThat(files.single().sourceKey).isEqualTo(DownloadKeys.mediaKey(1, "song-1", null, null))
        assertThat(files.single().uri).startsWith("file://")
    }

    @Test
    fun `an unavailable root offers nothing even when the row exists`() = runDbTest {
        storage()
        offline()
        val unavailable = OfflineStorage(
            roots = OfflineRootSource { emptyList() },
            settings = settings,
            usableSpace = { OfflinePathRules.MIN_FREE_SPACE_BYTES * 10 },
            writableProbe = { true },
        )

        assertThat(source(unavailable).filesFor(1, "song-1")).isEmpty()
        assertThat(source().filesFor(1, "song-1")).hasSize(1)
    }

    @Test
    fun `a renamed or moved library song cannot orphan the offline file`() = runDbTest {
        storage()
        offline(title = "Offline Title")
        newSong(id = 1, providerId = 1, title = "Renamed Song", filePath = "/music/moved/elsewhere.flac")

        val file = source().filesFor(1, "song-1").single()

        assertThat(file.title).isEqualTo("Offline Title")
        assertThat(file.uri).contains("/OfflineMedias/")
    }

    @Test
    fun `a downloaded file is offered without any library song row`() = runDbTest {
        storage()
        offline(title = "Kept Offline")

        assertThat(songDao.selectByProviderAndExternal(1, "song-1")).isNull()
        assertThat(source().filesFor(1, "song-1").single().title).isEqualTo("Kept Offline")
    }
}
