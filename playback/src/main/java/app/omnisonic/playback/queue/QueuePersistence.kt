package app.omnisonic.playback.queue

import app.omnisonic.core.data.repository.QueueSnapshotRepository
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.queue.ShuffleState
import javax.inject.Inject
import javax.inject.Singleton

/** Persistence seam so [QueuePersistenceCoordinator] can be tested without Room. */
interface QueueSnapshotStore {
    suspend fun save(state: QueueState)
    suspend fun restore(): QueueState?
    suspend fun clear()
}

/**
 * Maps the authoritative [QueueState] to snapshot rows and back. The queue
 * snapshot carries the resume position of the current entry ([QueueState.positionMs]);
 * the song-level resume point (`songs.resumePointMs`) remains the fallback used
 * by stream resolution when no queue snapshot exists.
 */
@Singleton
class QueuePersistence @Inject constructor(
    private val snapshots: QueueSnapshotRepository,
) : QueueSnapshotStore {

    override suspend fun save(state: QueueState) {
        if (state.entries.isEmpty()) {
            snapshots.clear()
            return
        }
        snapshots.save(
            entries = state.entries.mapIndexed { index, entry ->
                QueueSnapshotRepository.EntrySnapshot(
                    queueItemId = entry.queueItemId,
                    position = index,
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
            state = QueueSnapshotRepository.StateSnapshot(
                playIndex = state.playIndex,
                shuffleOrder = state.shuffle?.order,
                shuffleSeed = state.shuffle?.seed,
                repeatMode = state.repeatMode.name,
                speed = state.speed,
                pitch = state.pitch,
                sourceType = state.sourceType,
                sourceData = state.sourceData,
                positionMs = state.positionMs.takeIf { it > 0 },
            ),
        )
    }

    /** Null means there was no persisted queue; an empty queue is also discarded. */
    override suspend fun restore(): QueueState? {
        val restored = snapshots.restore()
        if (restored.entries.isEmpty()) return null
        val entries = restored.entries.map { row ->
            PlaybackQueueEntry(
                providerId = row.providerId,
                externalId = row.externalId,
                queueItemId = row.queueItemId,
                songRowId = row.songRowId,
                title = row.title,
                artistName = row.artistName,
                albumExternalId = row.albumExternalId,
                coverImageId = row.coverImageId,
                durationMs = row.durationMs,
                isRadioStation = row.isRadioStation,
            )
        }
        val state = restored.state
        val order = state?.shuffleOrder?.let { validateOrder(it, entries.size) }
        val orderSize = order?.size ?: entries.size
        PlaybackQueueEntry.resetIdCounterTo(entries.maxOf { it.queueItemId })
        return QueueState(
            entries = entries,
            playIndex = (state?.playIndex ?: 0).coerceIn(0, orderSize - 1),
            shuffle = order?.let { ShuffleState(it, state?.shuffleSeed ?: 0) },
            repeatMode = state?.repeatMode?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() }
                ?: RepeatMode.OFF,
            speed = state?.speed ?: 1f,
            pitch = state?.pitch ?: 1f,
            sourceType = state?.sourceType,
            sourceData = state?.sourceData,
            positionMs = state?.positionMs ?: 0L,
        )
    }

    override suspend fun clear() = snapshots.clear()

    private fun validateOrder(order: List<Int>, size: Int): List<Int>? =
        order.takeIf { it.size == size && it.toSet().size == size && it.all { index -> index in 0 until size } }
}
