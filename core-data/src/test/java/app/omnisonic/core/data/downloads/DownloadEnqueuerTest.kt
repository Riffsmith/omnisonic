package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.provider.api.DownloadEnqueueResult
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import app.omnisonic.provider.api.ProviderTraits
import com.google.common.truth.Truth.assertThat
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

    private fun enqueuer(
        traits: ProviderTraits = subsonicTraits,
        runGate: ProviderRunGate = ProviderRunGate { ProviderStatus.OK },
    ) = DownloadEnqueuer(db, traitsSource = { traits }, runGate = runGate)

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
        downloadDao.setProgress(afterFirst.id, 40, updatedAt = 2_000)
        enqueuer.enqueue(providerId = provider.id, externalId = "song-1", title = "second")

        val rows = downloadDao.selectByProvider(3)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().id).isEqualTo(afterFirst.id)
        assertThat(rows.single().progressPercent).isEqualTo(40)
        assertThat(rows.single().title).isEqualTo("first")
    }

    @Test
    fun mediaKeysParseBackToTheirOwningProvider() {
        assertThat(DownloadKeys.providerIdFromMediaKey(DownloadKeys.mediaKey(7, "song-1", null, null)))
            .isEqualTo(7)
        assertThat(DownloadKeys.providerIdFromMediaKey(DownloadKeys.mediaKey(12, "song-1", 320, "mp3")))
            .isEqualTo(12)
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
