package app.omnisonic.core.data.db.queue

import app.omnisonic.core.data.db.dao.OutboxDao
import app.omnisonic.core.data.db.entity.OutboxItemEntity
import app.omnisonic.provider.api.OutboxStore
import app.omnisonic.provider.api.OutboxTask

/**
 * OutboxStore backed by the Room outbox table; the persistence half of the
 * drain pipeline (see provider-api OutboxDrainer for the execution half).
 */
class RoomOutboxStore(private val outboxDao: OutboxDao) : OutboxStore {

    override suspend fun dueTasks(now: Long, providerId: Long?): List<OutboxTask> {
        val rows = if (providerId == null) {
            outboxDao.selectAllDue(now)
        } else {
            outboxDao.selectDue(providerId, now)
        }
        return rows.map(OutboxItemEntity::toTask)
    }

    override suspend fun complete(id: Long) {
        outboxDao.deleteById(id)
    }

    override suspend fun fail(id: Long, attempts: Int, nextAttemptAt: Long, lastError: String) {
        outboxDao.recordFailure(id, attempts, nextAttemptAt, lastError)
    }

    override suspend fun progress(id: Long, payloadJson: String?, nextAttemptAt: Long) {
        outboxDao.recordProgress(id, payloadJson, nextAttemptAt)
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
