package app.omnisonic.core.data.db.queue

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.entity.OutboxItemEntity
import app.omnisonic.core.data.json.OmnisonicJson
import app.omnisonic.provider.api.OutboxActionPayload
import app.omnisonic.provider.api.OutboxCompletion
import app.omnisonic.provider.api.OutboxProgress
import app.omnisonic.provider.api.OutboxStore
import app.omnisonic.provider.api.OutboxTask
import com.squareup.moshi.Moshi

/**
 * OutboxStore backed by the Room outbox table; the persistence half of the
 * drain pipeline (see provider-api OutboxDrainer for the execution half).
 *
 * Completion and progress are keyed by the payload the drain submitted, so a
 * change enqueued while a submission is in flight is never deleted or
 * overwritten (see storage/outbox-contract.md).
 */
class RoomOutboxStore(
    private val db: OmnisonicDatabase,
    moshi: Moshi = OmnisonicJson.moshi,
) : OutboxStore {

    private val outboxDao = db.outboxDao()
    private val payloadAdapter = moshi.adapter(OutboxActionPayload::class.java)

    override suspend fun dueTasks(now: Long, providerId: Long?): List<OutboxTask> {
        val rows = if (providerId == null) {
            outboxDao.selectAllDue(now)
        } else {
            outboxDao.selectDue(providerId, now)
        }
        return rows.map(OutboxItemEntity::toTask)
    }

    override suspend fun complete(task: OutboxTask): OutboxCompletion = db.withTransaction {
        val deleted = outboxDao.deleteByIdAndPayload(task.id, task.payloadJson)
        when {
            deleted > 0 -> OutboxCompletion.Deleted
            outboxDao.selectById(task.id) == null -> OutboxCompletion.Missing
            else -> OutboxCompletion.Superseded
        }
    }

    override suspend fun fail(id: Long, attempts: Int, nextAttemptAt: Long, lastError: String) {
        outboxDao.recordFailure(id, attempts, nextAttemptAt, lastError)
    }

    override suspend fun progress(
        task: OutboxTask,
        submittedTimestamps: List<Long>,
        nextAttemptAt: Long,
    ): OutboxProgress = db.withTransaction {
        val row = outboxDao.selectById(task.id) ?: return@withTransaction OutboxProgress.Cleared
        val current = when {
            row.payloadJson == null -> OutboxActionPayload()
            else -> runCatching { payloadAdapter.fromJson(row.payloadJson) }.getOrNull()
                ?: return@withTransaction OutboxProgress.Unreadable
        }
        val remaining = current.afterSubmitting(submittedTimestamps)
        val remainingTimestamps = remaining.playTimestamps
        if (remainingTimestamps.isNullOrEmpty()) {
            outboxDao.deleteByIdAndPayload(task.id, row.payloadJson)
            OutboxProgress.Cleared
        } else {
            outboxDao.recordProgress(task.id, payloadAdapter.toJson(remaining), nextAttemptAt)
            OutboxProgress.Retained(remainingTimestamps.size)
        }
    }
}

private fun OutboxItemEntity.toTask(): OutboxTask = OutboxTask(
    id = id,
    providerId = providerId,
    mediaType = mediaType,
    externalId = externalId,
    action = action,
    attempts = attempts,
    payloadJson = payloadJson,
)
