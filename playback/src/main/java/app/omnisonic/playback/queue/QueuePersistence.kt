package app.omnisonic.playback.queue

import app.omnisonic.core.data.repository.QueueSnapshotRepository
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.queue.ShuffleState
import javax.inject.Inject
import javax.inject.Singleton

/** Maps the authoritative [QueueState] to snapshot rows and back. */
@Singleton
class QueuePersistence @Inject constructor(
    private val snapshots: QueueSnapshotRepository,
) {

    suspend fun save(state: QueueState) {
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
            ),
        )
    }

    /** Null means there was no persisted queue; an empty queue is also discarded. */
    suspend fun restore(): QueueState? {
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
        return QueueState(
            entries = entries,
            playIndex = state?.playIndex ?: 0,
            shuffle = state?.shuffleOrder?.let { order ->
                validateOrder(order, entries.size)?.let { valid ->
                    ShuffleState(valid, state.shuffleSeed ?: 0)
                }
            },
            repeatMode = state?.repeatMode?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() }
                ?: RepeatMode.OFF,
            speed = state?.speed ?: 1f,
            pitch = state?.pitch ?: 1f,
            sourceType = state?.sourceType,
            sourceData = state?.sourceData,
        )
    }

    private fun validateOrder(order: List<Int>, size: Int): List<Int>? =
        order.takeIf { it.size == size && it.toSet().size == size && it.all { index -> index in 0 until size } }
}
