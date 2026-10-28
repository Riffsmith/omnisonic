package app.omnisonic.core.data

import app.omnisonic.core.data.db.queue.OutboxPayload
import app.omnisonic.core.data.db.queue.OutboxQueue
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
        val queue = OutboxQueue(db, outboxDao, moshi)

        queue.enqueuePlayCountDelta(provider.id, "track", "song-1", 1, now = 1_000)
        queue.enqueuePlayCountDelta(provider.id, "track", "song-1", 2, now = 2_000)

        val items = outboxDao.selectByProvider(provider.id)
        assertThat(items).hasSize(1)
        val payload = payloadOf(items.single().payloadJson!!)
        assertThat(payload.playCountDelta).isEqualTo(3)
        assertThat(items.single().nextAttemptAt).isEqualTo(0)
    }

    @Test
    fun nonPlayCountChangesReplaceScalarFieldsInPlace() = runDbTest {
        val provider = newProvider(id = 1)
        val queue = OutboxQueue(db, outboxDao, moshi)

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
        val queue = OutboxQueue(db, outboxDao, moshi)

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

    private fun payloadOf(encoded: String): OutboxPayload {
        val adapter = moshi.adapter(OutboxPayload::class.java)
        return adapter.fromJson(encoded) ?: error("payload not decodable")
    }
}
