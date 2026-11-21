package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.provider.api.DownloadEnqueueResult
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import app.omnisonic.provider.api.ProviderTraits
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadEnqueuerTest : DatabaseTestBase() {

    private val subsonicTraits = ProviderTraits()
    private val localTraits = ProviderTraits(
        hasLibrarySync = true,
        supportsDownload = false,
        supportsServerTranscode = false,
        reportsUserData = false,
    )

    private fun coordinator(
        runGate: ProviderRunGate = ProviderRunGate { ProviderStatus.OK },
        settings: DownloadQueueSettingsStore = DownloadQueueSettingsStore.inMemory(),
        workerCount: Int = 2,
    ) = DownloadWorkerCoordinator(
        repository = DownloadQueueRepository(downloadDao),
        settings = settings,
        runGate = runGate,
        workerCount = workerCount,
        nowMs = { 1_000 },
    )

    private fun enqueuer(
        traits: ProviderTraits = subsonicTraits,
        runGate: ProviderRunGate = ProviderRunGate { ProviderStatus.OK },
        coordinator: DownloadWorkerCoordinator = coordinator(runGate = runGate),
    ) = DownloadEnqueuer(db, coordinator, { traits }, runGate, nowMs = { 1_000 })

    @Test
    fun enqueueWritesAnOwnedPendingRowWithTheStreamKeyVocabulary() = runDbTest {
        val provider = newProvider(id = 3)

        val result = enqueuer().enqueue(
            providerId = provider.id,
            externalId = "song-1",
            title = "Track one",
            transcodeBitrateKbps = 320,
            format = "mp3",
        )

        assertThat(result).isEqualTo(DownloadEnqueueResult.Enqueued)
        val rows = downloadDao.selectByProvider(3)
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.providerId).isEqualTo(3)
        assertThat(row.mediaKey).isEqualTo("stream:3:song-1:320:mp3")
        assertThat(row.status).isEqualTo(DownloadStatus.PENDING.name)
        assertThat(row.tempCache).isFalse()
        assertThat(row.exportTarget).isNull()
        assertThat(row.autoRuleId).isNull()
    }

    @Test
    fun newWorkStartsPausedWhileTheGlobalGateIsSet() = runDbTest {
        val provider = newProvider(id = 3)
        val settings = DownloadQueueSettingsStore.inMemory()
        settings.setGloballyPaused(true)

        enqueuer(coordinator = coordinator(settings = settings)).enqueue(
            providerId = provider.id,
            externalId = "song-1",
        )

        assertThat(downloadDao.selectByProvider(3).single().status).isEqualTo(DownloadStatus.PAUSED.name)
        assertThat(coordinator(settings = settings).claimNext(ProfileScope.Unrestricted)).isNull()
    }

    @Test
    fun unsupportedDownloadIsATypedRejectionWithNoRowsWritten() = runDbTest {
        val provider = newProvider(id = 3).let { row -> providerDao.upsert(row.copy(api = "local")); providerDao.selectById(3)!! }

        val result = enqueuer(traits = localTraits).enqueue(providerId = provider.id, externalId = "song-1")

        assertThat(result).isEqualTo(DownloadEnqueueResult.SkippedDownloadUnsupported)
        assertThat(downloadDao.selectByProvider(3)).isEmpty()
    }

    @Test
    fun unsupportedDownloadStaysRejectedEvenWhenTheRunGateWouldBlock() = runDbTest {
        val provider = newProvider(id = 3).let { row -> providerDao.upsert(row.copy(api = "local")); providerDao.selectById(3)!! }

        val result = enqueuer(
            traits = localTraits,
            runGate = { ProviderStatus.OFFLINE },
        ).enqueue(providerId = provider.id, externalId = "song-1")

        assertThat(result).isEqualTo(DownloadEnqueueResult.SkippedDownloadUnsupported)
    }

    @Test
    fun missingProviderIsRejectedWithoutWritingAnOrphanRow() = runDbTest {
        val result = enqueuer().enqueue(providerId = 99, externalId = "song-1")

        assertThat(result).isEqualTo(DownloadEnqueueResult.SkippedDownloadUnsupported)
        assertThat(downloadDao.selectByProvider(99)).isEmpty()
    }

    @Test
    fun blockedRunGateDefersTheEnqueueWithTheStatusAndNoRows() = runDbTest {
        val provider = newProvider(id = 3)

        val result = enqueuer(runGate = { ProviderStatus.NEEDS_WIFI })
            .enqueue(providerId = provider.id, externalId = "song-1")

        assertThat(result).isEqualTo(DownloadEnqueueResult.BlockedByStatus(ProviderStatus.NEEDS_WIFI))
        assertThat(downloadDao.selectByProvider(3)).isEmpty()
    }

    @Test
    fun reEnqueueingTheSameKeyIsIdempotent() = runDbTest {
        val provider = newProvider(id = 3)
        val enqueuer = enqueuer()

        enqueuer.enqueue(providerId = provider.id, externalId = "song-1", title = "first")
        val afterFirst = downloadDao.selectByProvider(3).single()
        downloadDao.upsert(afterFirst.copy(progressPercent = 40, updatedAt = 2_000))
        enqueuer.enqueue(providerId = provider.id, externalId = "song-1", title = "second")

        val rows = downloadDao.selectByProvider(3)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().id).isEqualTo(afterFirst.id)
        assertThat(rows.single().progressPercent).isEqualTo(40)
        assertThat(rows.single().title).isEqualTo("first")
    }

    @Test
    fun duplicateEnqueueIsAWholeRowNoOpInEveryLifecycleState() = runDbTest {
        val provider = newProvider(id = 3)
        val key = DownloadKeys.mediaKey(provider.id, "song-1", null, null)
        val enqueuer = enqueuer()

        for (status in DownloadStatus.entries) {
            downloadDao.deleteAll()
            downloadDao.upsert(
                DownloadItemEntity(
                    mediaKey = key,
                    providerId = provider.id,
                    status = status.name,
                    title = "seeded",
                    imagePath = "/image",
                    progressPercent = 42,
                    errorCount = 2,
                    lastError = "prior failure",
                    tempCache = true,
                    exportTarget = "MEDIASTORE_DOWNLOADS",
                    autoRuleId = 7,
                    createdAt = 500,
                    updatedAt = 900,
                ),
            )
            val seeded = downloadDao.selectByMediaKey(key)!!

            val result = enqueuer.enqueue(
                providerId = provider.id,
                externalId = "song-1",
                title = "replacement",
                imagePath = "/other",
                tempCache = false,
                exportTarget = "MEDIASTORE_DOWNLOADS",
                autoRuleId = null,
            )

            assertThat(result).isEqualTo(DownloadEnqueueResult.Enqueued)
            assertThat(downloadDao.selectByMediaKey(key)).isEqualTo(seeded)
        }
    }

    @Test
    fun aDuplicateEnqueueDuringGlobalPauseLeavesThePausedRowUntouched() = runDbTest {
        val provider = newProvider(id = 3)
        val settings = DownloadQueueSettingsStore.inMemory()
        val enqueuer = enqueuer(coordinator = coordinator(settings = settings))

        enqueuer.enqueue(providerId = provider.id, externalId = "song-1", title = "first")
        val coordinator = coordinator(settings = settings)
        coordinator.pauseAll()
        val paused = downloadDao.selectByProvider(3).single()
        assertThat(paused.status).isEqualTo(DownloadStatus.PAUSED.name)

        enqueuer.enqueue(providerId = provider.id, externalId = "song-1", title = "second")

        assertThat(downloadDao.selectByProvider(3)).containsExactly(paused)
    }

    @Test
    fun concurrentDuplicateEnqueuesInsertOneRow() = runDbTest {
        val provider = newProvider(id = 3)
        val enqueuer = enqueuer()

        withContext(Dispatchers.Default) {
            (1..8).map {
                async { enqueuer.enqueue(providerId = provider.id, externalId = "song-1") }
            }.awaitAll()
        }

        assertThat(downloadDao.selectByProvider(3)).hasSize(1)
    }

    @Test
    fun providerBitrateAndFormatVariantsRemainDistinctIdentities() = runDbTest {
        val provider = newProvider(id = 3)
        val other = newProvider(id = 4)
        val enqueuer = enqueuer()

        enqueuer.enqueue(providerId = provider.id, externalId = "song-1")
        enqueuer.enqueue(providerId = provider.id, externalId = "song-1", transcodeBitrateKbps = 320)
        enqueuer.enqueue(providerId = provider.id, externalId = "song-1", transcodeBitrateKbps = 320, format = "mp3")
        enqueuer.enqueue(providerId = other.id, externalId = "song-1")

        assertThat(downloadDao.selectByProvider(3)).hasSize(3)
        assertThat(downloadDao.selectByProvider(4)).hasSize(1)
    }

    @Test
    fun mediaKeysParseBackToTheirOwningProvider() {
        assertThat(DownloadKeys.providerIdFromMediaKey(DownloadKeys.mediaKey(7, "song-1", null, null)))
            .isEqualTo(7)
        assertThat(DownloadKeys.providerIdFromMediaKey(DownloadKeys.mediaKey(12, "song-1", 320, "mp3")))
            .isEqualTo(12)
    }

    @Test
    fun colonContainingExternalIdsRoundTripThroughThePersistedKey() {
        val key = DownloadKeys.mediaKey(7, "album:song:live", 320, "mp3")

        assertThat(DownloadKeys.parseMediaKey(key))
            .isEqualTo(app.omnisonic.core.data.db.DownloadStreamKey(7, "album:song:live", 320, "mp3"))
    }

    @Test
    fun keysWithoutAReliableProviderSegmentDoNotInventOne() {
        assertThat(DownloadKeys.providerIdFromMediaKey("key-1")).isNull()
        assertThat(DownloadKeys.providerIdFromMediaKey("stream:notanumber:song-1:auto:auto")).isNull()
        assertThat(DownloadKeys.providerIdFromMediaKey("stream:0:song-1:auto:auto")).isNull()
        assertThat(DownloadKeys.providerIdFromMediaKey("stream:-4:song-1:auto:auto")).isNull()
        assertThat(DownloadKeys.providerIdFromMediaKey("")).isNull()
    }
}
