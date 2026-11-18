package app.omnisonic.core.data.db.queue

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.dao.OutboxDao
import app.omnisonic.core.data.db.entity.OutboxItemEntity
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.core.data.providers.DefaultTraitsSource
import app.omnisonic.core.data.providers.ProviderTraitsSource
import app.omnisonic.provider.api.OutboxActionPayload
import app.omnisonic.provider.api.OutboxActions
import app.omnisonic.provider.api.OutboxEnqueueResult
import app.omnisonic.provider.api.ProviderMediaType
import app.omnisonic.provider.api.ProviderTraits
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OutboxQueue @Inject constructor(
    private val db: OmnisonicDatabase,
    private val outboxDao: OutboxDao,
    private val traitsSource: ProviderTraitsSource = DefaultTraitsSource,
    moshi: Moshi = OmnisonicJson.moshi,
) {

    private val payloadAdapter = moshi.adapter(OutboxActionPayload::class.java)

    /**
     * Providers whose traits report no user-data reporting are skipped
     * politely: nothing is written and the caller gets a typed result.
     */
    suspend fun enqueueMediaChange(
        providerId: Long,
        mediaType: ProviderMediaType,
        externalId: String,
        action: String,
        payload: OutboxActionPayload,
        now: Long,
    ): OutboxEnqueueResult {
        if (!traitsFor(providerId).reportsUserData) {
            return OutboxEnqueueResult.SkippedNoUserDataReporting
        }
        db.withTransaction {
            val existing = outboxDao.selectTarget(providerId, mediaType.name, externalId, action)
            val merged = existing
                ?.let { decode(it.payloadJson).merge(payload) }
                ?: payload
            val normalized = if (action == OutboxActions.PLAY_COUNT) {
                merged.withConsistentPlayCount()
            } else {
                merged
            }
            outboxDao.upsert(
                OutboxItemEntity(
                    id = existing?.id ?: 0,
                    providerId = providerId,
                    mediaType = mediaType.name,
                    externalId = externalId,
                    action = action,
                    payloadJson = payloadAdapter.toJson(normalized),
                    attempts = existing?.attempts ?: 0,
                    nextAttemptAt = 0,
                    lastError = existing?.lastError,
                    createdAt = existing?.createdAt ?: now,
                ),
            )
        }
        return OutboxEnqueueResult.Enqueued
    }

    suspend fun enqueuePlayCountDelta(
        providerId: Long,
        mediaType: ProviderMediaType,
        externalId: String,
        playedAtMs: Long,
        now: Long,
    ): OutboxEnqueueResult =
        enqueueMediaChange(
            providerId,
            mediaType,
            externalId,
            OutboxActions.PLAY_COUNT,
            OutboxActionPayload(playCountDelta = 1, lastPlayedAt = playedAtMs, playTimestamps = listOf(playedAtMs)),
            now,
        )

    /**
     * Drops one play timestamp from the pending play-count row of an item
     * (history delete). Returns false when no row exists or the timestamp is
     * not queued anymore (already submitted). The delete is payload-keyed, so
     * a play enqueued meanwhile survives.
     */
    suspend fun removePlayTimestamp(
        providerId: Long,
        mediaType: ProviderMediaType,
        externalId: String,
        playedAtMs: Long,
    ): Boolean = db.withTransaction {
        val existing = outboxDao.selectTarget(providerId, mediaType.name, externalId, OutboxActions.PLAY_COUNT)
            ?: return@withTransaction false
        val payload = decode(existing.payloadJson)
        val before = payload.playTimestampsForSubmission()
        val remaining = payload.afterSubmitting(listOf(playedAtMs))
        if (remaining.playTimestampsForSubmission() == before) return@withTransaction false
        writeBackPlayCount(existing, remaining)
        true
    }

    /**
     * Adjusts the pending play-count delta of an item by [delta] (history
     * edit), floored at zero: negative deltas consume the newest queued
     * timestamps, positive ones append synthesised ones, so the row keeps one
     * timestamp per play. Returns false when no row exists or delta is 0.
     */
    suspend fun adjustPlayCountDelta(
        providerId: Long,
        mediaType: ProviderMediaType,
        externalId: String,
        delta: Int,
    ): Boolean = db.withTransaction {
        if (delta == 0) return@withTransaction false
        val existing = outboxDao.selectTarget(providerId, mediaType.name, externalId, OutboxActions.PLAY_COUNT)
            ?: return@withTransaction false
        val payload = decode(existing.payloadJson)
        val adjusted = if (delta < 0) {
            payload.afterSubmitting(payload.playTimestampsForSubmission().takeLast(-delta))
        } else {
            payload.withAppendedPlays(delta).withConsistentPlayCount()
        }
        writeBackPlayCount(existing, adjusted)
        true
    }

    private suspend fun writeBackPlayCount(existing: OutboxItemEntity, payload: OutboxActionPayload) {
        if (payload.playTimestampsForSubmission().isEmpty()) {
            outboxDao.deleteByIdAndPayload(existing.id, existing.payloadJson)
        } else {
            outboxDao.recordProgress(existing.id, payloadAdapter.toJson(payload), existing.nextAttemptAt)
        }
    }

    private fun OutboxActionPayload.withAppendedPlays(count: Int): OutboxActionPayload {
        val base = playTimestampsForSubmission()
        val start = (base.lastOrNull() ?: lastPlayedAt ?: 0L) + 1
        return copy(playTimestamps = base + List(count) { start + it })
    }

    /**
     * Traits come from the persisted row's api; a row that has already gone
     * resolves to the Subsonic-family defaults (same fallback the registry
     * applies to unknown apis).
     */
    private suspend fun traitsFor(providerId: Long): ProviderTraits {
        val api = db.providerDao().selectById(providerId)?.api ?: return ProviderTraits()
        return traitsSource.traitsFor(api)
    }

    private fun decode(encoded: String?): OutboxActionPayload =
        encoded?.let { runCatching { payloadAdapter.fromJson(it) }.getOrNull() } ?: OutboxActionPayload()

    private fun OutboxActionPayload.merge(incoming: OutboxActionPayload): OutboxActionPayload {
        val incomingDelta = incoming.playCountDelta
        return if (incomingDelta != null) {
            // Legacy rows can carry a delta without an explicit timestamp list;
            // synthesise those plays so the merged row keeps one timestamp per play.
            val timestamps = playTimestampsForSubmission() + incoming.playTimestampsForSubmission()
            copy(
                playCountDelta = timestamps.size.takeIf { it > 0 },
                lastPlayedAt = incoming.lastPlayedAt ?: lastPlayedAt,
                playTimestamps = timestamps.takeIf { it.isNotEmpty() },
            )
        } else {
            copy(
                isFavorite = incoming.isFavorite ?: isFavorite,
                userRating = incoming.userRating ?: userRating,
                resumeMs = incoming.resumeMs ?: resumeMs,
                lastPlayedAt = incoming.lastPlayedAt ?: lastPlayedAt,
                playTimestamps = incoming.playTimestamps ?: playTimestamps,
            )
        }
    }

    private fun OutboxActionPayload.withConsistentPlayCount(): OutboxActionPayload {
        val timestamps = playTimestampsForSubmission()
        return if (timestamps.isEmpty()) {
            copy(playCountDelta = null, playTimestamps = null)
        } else {
            copy(
                playCountDelta = timestamps.size,
                lastPlayedAt = lastPlayedAt ?: timestamps.last(),
                playTimestamps = timestamps,
            )
        }
    }
}
