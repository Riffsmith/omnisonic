package app.omnisonic.core.data

import app.omnisonic.core.data.db.queue.OutboxPayload
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.provider.api.OutboxEnqueueResult
import app.omnisonic.provider.api.ProviderTraits
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxQueueTest : DatabaseTestBase() {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun playCountDeltaMergeAccumulatesIntoSingleRow() = runDbTest {
        val provider = newProvider(id = 1)
        val queue = OutboxQueue(db, outboxDao, moshi = moshi)

        queue.enqueuePlayCountDelta(provider.id, "track", "song-1", playedAtMs = 1_500, now = 1_000)
        queue.enqueuePlayCountDelta(provider.id, "track", "song-1", playedAtMs = 2_500, now = 2_000)

        val items = outboxDao.selectByProvider(provider.id)
        assertThat(items).hasSize(1)
        val payload = payloadOf(items.single().payloadJson!!)
        assertThat(payload.playCountDelta).isEqualTo(2)
        assertThat(payload.playTimestamps).containsExactly(1_500L, 2_500L).inOrder()
        assertThat(items.single().nextAttemptAt).isEqualTo(0)
    }

    @Test
    fun playTimestampsCapAtHundredKeepingTheNewest() = runDbTest {
        val provider = newProvider(id = 1)
        val queue = OutboxQueue(db, outboxDao, moshi = moshi)

        repeat(120) { index ->
            queue.enqueuePlayCountDelta(provider.id, "track", "song-1", playedAtMs = index.toLong(), now = index.toLong())
        }

        val payload = payloadOf(outboxDao.selectByProvider(provider.id).single().payloadJson!!)
        assertThat(payload.playCountDelta).isEqualTo(120)
        assertThat(payload.playTimestamps).hasSize(100)
        // Overflow keeps the newest timestamps.
        assertThat(payload.playTimestamps!!.first()).isEqualTo(20L)
        assertThat(payload.playTimestamps!!.last()).isEqualTo(119L)
    }

    @Test
    fun nonPlayCountChangesReplaceScalarFieldsInPlace() = runDbTest {
        val provider = newProvider(id = 1)
        val queue = OutboxQueue(db, outboxDao, moshi = moshi)

        queue.enqueueMediaChange(provider.id, "track", "song-1", OutboxQueue.ACTION_RESUME_POINT, OutboxPayload(playCountDelta = null), now = 1_000)
        queue.enqueueMediaChange(provider.id, "track", "song-1", OutboxQueue.ACTION_RESUME_POINT, OutboxPayload(resumePointMs = 500, lastPlayedAt = 2_000), now = 2_000)

        val items = outboxDao.selectByProvider(provider.id)
        assertThat(items).hasSize(1)
        assertThat(items.single().action).isEqualTo(OutboxQueue.ACTION_RESUME_POINT)
        val payload = payloadOf(items.single().payloadJson!!)
        assertThat(payload.playCountDelta).isNull()
        assertThat(payload.lastPlayedAt).isEqualTo(2_000)
        assertThat(payload.userRating).isNull()
    }

    @Test
    fun differentActionsKeepSeparateRows() = runDbTest {
        val provider = newProvider(id = 1)
        val queue = OutboxQueue(db, outboxDao, moshi = moshi)

        queue.enqueueMediaChange(provider.id, "track", "song-1", OutboxQueue.ACTION_FAVORITE, OutboxPayload(isFavorite = true), now = 1_000)
        queue.enqueueMediaChange(provider.id, "track", "song-1", OutboxQueue.ACTION_USER_RATING, OutboxPayload(userRating = 5), now = 1_000)

        val items = outboxDao.selectByProvider(provider.id)
        assertThat(items).hasSize(2)
        assertThat(items.map { it.action }).containsExactly(OutboxQueue.ACTION_FAVORITE, OutboxQueue.ACTION_USER_RATING)
    }

    @Test
    fun setupOutboxItemSeedUsesSharedActionName() = runDbTest {
        val provider = newProvider(id = 1)
        seedOutboxItem(providerId = provider.id)

        val queued = outboxDao.selectDue(providerId = provider.id, now = 5_000)
        assertThat(queued.map { it.action }).containsExactly(OutboxQueueActions.PLAY_COUNT)
    }

    @Test
    fun providersThatDoNotReportUserDataWriteNoRows() = runDbTest {
        val provider = newProvider(id = 1)
        val localTraits = ProviderTraits(hasLibrarySync = true, supportsDownload = false, supportsServerTranscode = false, reportsUserData = false)
        val queue = OutboxQueue(db, outboxDao, traitsSource = { localTraits }, moshi)

        val favorite = queue.enqueueMediaChange(
            provider.id, "track", "song-1", OutboxQueue.ACTION_FAVORITE, OutboxPayload(isFavorite = true), now = 1_000,
        )
        val plays = queue.enqueuePlayCountDelta(provider.id, "track", "song-1", playedAtMs = 1_500, now = 2_000)

        assertThat(favorite).isEqualTo(OutboxEnqueueResult.SkippedNoUserDataReporting)
        assertThat(plays).isEqualTo(OutboxEnqueueResult.SkippedNoUserDataReporting)
        assertThat(outboxDao.selectByProvider(provider.id)).isEmpty()
    }

    @Test
    fun traitsResolveThroughThePersistedRowsApi() = runDbTest {
        val remote = newProvider(id = 1).let { row -> providerDao.upsert(row.copy(api = "subsonic")); providerDao.selectById(1)!! }
        val seenApis = mutableListOf<String>()
        val queue = OutboxQueue(
            db,
            outboxDao,
            traitsSource = { api -> seenApis += api; ProviderTraits() },
            moshi,
        )

        val result = queue.enqueuePlayCountDelta(remote.id, "track", "song-1", playedAtMs = 1_500, now = 1_000)

        assertThat(result).isEqualTo(OutboxEnqueueResult.Enqueued)
        assertThat(seenApis).containsExactly("subsonic")
        assertThat(outboxDao.selectByProvider(remote.id)).hasSize(1)
    }

    private fun payloadOf(encoded: String): OutboxPayload {
        val adapter = moshi.adapter(OutboxPayload::class.java)
        return adapter.fromJson(encoded) ?: error("payload not decodable")
    }
}
