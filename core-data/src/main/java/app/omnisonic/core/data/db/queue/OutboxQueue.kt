package app.omnisonic.core.data.db.queue

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.dao.OutboxDao
import app.omnisonic.core.data.db.entity.OutboxItemEntity
import app.omnisonic.core.data.json.OmnisonicJson
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Singleton

data class OutboxPayload(
    val playCountDelta: Int? = null,
    val isFavorite: Boolean? = null,
    val userRating: Int? = null,
    val resumePointMs: Long? = null,
    val lastPlayedAt: Long? = null,
)

@Singleton
class OutboxQueue @Inject constructor(
    private val db: OmnisonicDatabase,
    private val outboxDao: OutboxDao,
    moshi: Moshi = OmnisonicJson.moshi,
) {

    private val payloadAdapter = moshi.adapter(OutboxPayload::class.java)

    suspend fun enqueueMediaChange(
        providerId: Long,
        mediaType: String,
        externalId: String,
        action: String,
        payload: OutboxPayload,
        now: Long,
    ) {
        db.withTransaction {
            val existing = outboxDao.selectTarget(providerId, mediaType, externalId, action)
            val merged = existing
                ?.let { decode(it.payloadJson).merge(payload) }
                ?: payload
            outboxDao.upsert(
                OutboxItemEntity(
                    id = existing?.id ?: 0,
                    providerId = providerId,
                    mediaType = mediaType,
                    externalId = externalId,
                    action = action,
                    payloadJson = payloadAdapter.toJson(merged),
                    attempts = existing?.attempts ?: 0,
                    nextAttemptAt = 0,
                    lastError = existing?.lastError,
                    createdAt = existing?.createdAt ?: now,
                ),
            )
        }
    }

    suspend fun enqueuePlayCountDelta(
        providerId: Long,
        mediaType: String,
        externalId: String,
        playCountDelta: Int,
        now: Long,
    ) {
        enqueueMediaChange(providerId, mediaType, externalId, ACTION_PLAY_COUNT, OutboxPayload(playCountDelta = playCountDelta), now)
    }

    private fun decode(encoded: String?): OutboxPayload =
        encoded?.let { runCatching { payloadAdapter.fromJson(it) }.getOrNull() } ?: OutboxPayload()

    private fun OutboxPayload.merge(incoming: OutboxPayload): OutboxPayload = if (incoming.playCountDelta != null) {
        copy(playCountDelta = (playCountDelta ?: 0) + incoming.playCountDelta)
    } else {
        copy(
            isFavorite = incoming.isFavorite ?: isFavorite,
            userRating = incoming.userRating ?: userRating,
            resumePointMs = incoming.resumePointMs ?: resumePointMs,
            lastPlayedAt = incoming.lastPlayedAt ?: lastPlayedAt,
        )
    }

    companion object {
        const val ACTION_PLAY_COUNT = "play_count"
        const val ACTION_FAVORITE = "favorite"
        const val ACTION_USER_RATING = "user_rating"
        const val ACTION_RESUME_POINT = "resume_point"
    }
}
