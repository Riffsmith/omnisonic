package app.omnisonic.core.data

import app.omnisonic.core.data.db.entity.OutboxItemEntity
import app.omnisonic.provider.api.OutboxActionPayload
import app.omnisonic.provider.api.OutboxActions
import app.omnisonic.provider.api.ProviderMediaType
import app.omnisonic.core.data.db.queue.OutboxQueue
import app.omnisonic.provider.api.OutboxEnqueueResult
import app.omnisonic.provider.api.ProviderTraits
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxQueueTest : DatabaseTestBase() {

    private val moshi = Moshi.Builder().build()

    @Test
    fun playCountDeltaMergeAccumulatesIntoSingleRow() = runDbTest {
        val provider = newProvider(id = 1)
        val queue = OutboxQueue(db, outboxDao, moshi = moshi)

        queue.enqueuePlayCountDelta(provider.id, ProviderMediaType.SONG, "song-1", playedAtMs = 1_500, now = 1_000)
        queue.enqueuePlayCountDelta(provider.id, ProviderMediaType.SONG, "song-1", playedAtMs = 2_500, now = 2_000)

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
            queue.enqueuePlayCountDelta(provider.id, ProviderMediaType.SONG, "song-1", playedAtMs = index.toLong(), now = index.toLong())
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

        queue.enqueueMediaChange(provider.id, ProviderMediaType.SONG, "song-1", OutboxActions.RESUME_POINT, OutboxActionPayload(playCountDelta = null), now = 1_000)
        queue.enqueueMediaChange(provider.id, ProviderMediaType.SONG, "song-1", OutboxActions.RESUME_POINT, OutboxActionPayload(resumePointMs = 500, lastPlayedAt = 2_000), now = 2_000)

        val items = outboxDao.selectByProvider(provider.id)
        assertThat(items).hasSize(1)
        assertThat(items.single().action).isEqualTo(OutboxActions.RESUME_POINT)
        val payload = payloadOf(items.single().payloadJson!!)
        assertThat(payload.playCountDelta).isNull()
        assertThat(payload.lastPlayedAt).isEqualTo(2_000)
        assertThat(payload.userRating).isNull()
    }

    @Test
    fun differentActionsKeepSeparateRows() = runDbTest {
        val provider = newProvider(id = 1)
        val queue = OutboxQueue(db, outboxDao, moshi = moshi)

        queue.enqueueMediaChange(provider.id, ProviderMediaType.SONG, "song-1", OutboxActions.FAVORITE, OutboxActionPayload(isFavorite = true), now = 1_000)
        queue.enqueueMediaChange(provider.id, ProviderMediaType.SONG, "song-1", OutboxActions.USER_RATING, OutboxActionPayload(userRating = 5), now = 1_000)

        val items = outboxDao.selectByProvider(provider.id)
        assertThat(items).hasSize(2)
        assertThat(items.map { it.action }).containsExactly(OutboxActions.FAVORITE, OutboxActions.USER_RATING)
    }

    @Test
    fun setupOutboxItemSeedUsesSharedActionName() = runDbTest {
        val provider = newProvider(id = 1)
        seedOutboxItem(providerId = provider.id)

        val queued = outboxDao.selectDue(providerId = provider.id, now = 5_000)
        assertThat(queued.map { it.action }).containsExactly(OutboxActions.PLAY_COUNT)
    }

    @Test
    fun providersThatDoNotReportUserDataWriteNoRows() = runDbTest {
        val provider = newProvider(id = 1)
        val localTraits = ProviderTraits(hasLibrarySync = true, supportsDownload = false, supportsServerTranscode = false, reportsUserData = false)
        val queue = OutboxQueue(db, outboxDao, traitsSource = { localTraits }, moshi)

        val favorite = queue.enqueueMediaChange(
            provider.id, ProviderMediaType.SONG, "song-1", OutboxActions.FAVORITE, OutboxActionPayload(isFavorite = true), now = 1_000,
        )
        val plays = queue.enqueuePlayCountDelta(provider.id, ProviderMediaType.SONG, "song-1", playedAtMs = 1_500, now = 2_000)

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

        val result = queue.enqueuePlayCountDelta(remote.id, ProviderMediaType.SONG, "song-1", playedAtMs = 1_500, now = 1_000)

        assertThat(result).isEqualTo(OutboxEnqueueResult.Enqueued)
        assertThat(seenApis).containsExactly("subsonic")
        assertThat(outboxDao.selectByProvider(remote.id)).hasSize(1)
    }

    @Test
    fun roomColumnStoresTheProviderMediaTypeEnumName() = runDbTest {
        val provider = newProvider(id = 1)
        val queue = OutboxQueue(db, outboxDao, moshi = moshi)

        queue.enqueuePlayCountDelta(provider.id, ProviderMediaType.SONG, "song-1", playedAtMs = 1_500, now = 1_000)

        val item = outboxDao.selectByProvider(provider.id).single()
        assertThat(item.mediaType).isEqualTo(app.omnisonic.provider.api.ProviderMediaType.SONG.name)
    }

    @Test
    fun selectDueOrdersByNextAttemptThenCreatedAtThenId() = runDbTest {
        val provider = newProvider(id = 1)
        // Same nextAttemptAt for both rows; created later but inserted first.
        outboxDao.upsert(
            OutboxItemEntity(
                providerId = provider.id,
                mediaType = "SONG",
                externalId = "song-a",
                action = "favorite",
                payloadJson = null,
                nextAttemptAt = 5_000,
                createdAt = 20,
            ),
        )
        outboxDao.upsert(
            OutboxItemEntity(
                providerId = provider.id,
                mediaType = "SONG",
                externalId = "song-b",
                action = "favorite",
                payloadJson = null,
                nextAttemptAt = 5_000,
                createdAt = 10,
            ),
        )
        outboxDao.upsert(
            OutboxItemEntity(
                providerId = provider.id,
                mediaType = "SONG",
                externalId = "song-c",
                action = "favorite",
                payloadJson = null,
                nextAttemptAt = 5_000,
                createdAt = 10,
            ),
        )

        val due = outboxDao.selectDue(providerId = provider.id, now = 6_000)

        assertThat(due.map { it.externalId }).containsExactly("song-b", "song-c", "song-a").inOrder()
    }

    private fun payloadOf(encoded: String): OutboxActionPayload {
        val adapter = moshi.adapter(OutboxActionPayload::class.java)
        return adapter.fromJson(encoded) ?: error("payload not decodable")
    }
}
