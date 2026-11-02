package app.omnisonic.core.data.repository

import androidx.room.withTransaction
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.entity.QueueEntryEntity
import app.omnisonic.core.data.db.entity.QueueStateEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queue persistence is snapshot-only: restoring reads [queue_entries] and
 * never joins `songs`, so an entry that was never synced keeps its metadata.
 */
@Singleton
class QueueSnapshotRepository @Inject constructor(
    private val db: OmnisonicDatabase,
) {

    data class EntrySnapshot(
        val queueItemId: Long,
        val position: Int,
        val providerId: Long,
        val externalId: String,
        val songRowId: Long? = null,
        val title: String,
        val artistName: String? = null,
        val albumExternalId: String? = null,
        val coverImageId: String? = null,
        val durationMs: Long? = null,
        val isRadioStation: Boolean = false,
    )

    data class StateSnapshot(
        val playIndex: Int = 0,
        val shuffleOrder: List<Int>? = null,
        val shuffleSeed: Long? = null,
        val repeatMode: String = "OFF",
        val speed: Float = 1f,
        val pitch: Float = 1f,
        val sourceType: String? = null,
        val sourceData: String? = null,
    )

    data class Restored(
        val entries: List<EntrySnapshot>,
        val state: StateSnapshot?,
    )

    suspend fun save(entries: List<EntrySnapshot>, state: StateSnapshot?): Unit = db.withTransaction {
        val queueDao = db.queueDao()
        queueDao.deleteEntries()
        queueDao.deleteState()
        if (entries.isNotEmpty()) {
            queueDao.insertEntries(
                entries.map { entry ->
                    QueueEntryEntity(
                        queueItemId = entry.queueItemId,
                        position = entry.position,
                        providerId = entry.providerId,
                        externalId = entry.externalId,
                        songRowId = entry.songRowId,
                        title = entry.title,
                        artistName = entry.artistName,
                        albumExternalId = entry.albumExternalId,
                        coverImageId = entry.coverImageId,
                        durationMs = entry.durationMs,
                        isRadioStation = entry.isRadioStation,
                    )
                },
            )
        }
        state?.let {
            queueDao.upsertState(
                QueueStateEntity(
                    playIndex = it.playIndex,
                    shuffleOrderJson = it.shuffleOrder?.joinToString(SEPARATOR),
                    shuffleSeed = it.shuffleSeed,
                    repeatMode = it.repeatMode,
                    speed = it.speed,
                    pitch = it.pitch,
                    sourceType = it.sourceType,
                    sourceData = it.sourceData,
                ),
            )
        }
    }

    suspend fun restore(): Restored = db.withTransaction {
        val queueDao = db.queueDao()
        Restored(
            entries = queueDao.selectEntries().map { row ->
                EntrySnapshot(
                    queueItemId = row.queueItemId,
                    position = row.position,
                    providerId = row.providerId,
                    externalId = row.externalId,
                    songRowId = row.songRowId,
                    title = row.title,
                    artistName = row.artistName,
                    albumExternalId = row.albumExternalId,
                    coverImageId = row.coverImageId,
                    durationMs = row.durationMs,
                    isRadioStation = row.isRadioStation,
                )
            },
            state = queueDao.selectState(QueueStateEntity.SINGLETON_ID)?.let { row ->
                StateSnapshot(
                    playIndex = row.playIndex,
                    shuffleOrder = row.shuffleOrderJson?.let(::decodeOrder),
                    shuffleSeed = row.shuffleSeed,
                    repeatMode = row.repeatMode,
                    speed = row.speed,
                    pitch = row.pitch,
                    sourceType = row.sourceType,
                    sourceData = row.sourceData,
                )
            },
        )
    }

    suspend fun clear(): Unit = db.withTransaction {
        db.queueDao().deleteEntries()
        db.queueDao().deleteState()
    }

    private fun decodeOrder(encoded: String): List<Int> =
        encoded.split(SEPARATOR).mapNotNull(String::toIntOrNull)

    private companion object {
        const val SEPARATOR = ","
    }
}
