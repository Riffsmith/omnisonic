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
