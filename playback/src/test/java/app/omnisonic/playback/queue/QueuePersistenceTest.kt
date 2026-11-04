package app.omnisonic.playback.queue

import androidx.test.core.app.ApplicationProvider
import app.omnisonic.core.data.db.OmnisonicDatabaseFactory
import app.omnisonic.core.data.db.entity.QueueStateEntity
import app.omnisonic.core.data.repository.QueueSnapshotRepository
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.queue.ShuffleState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueuePersistenceTest {

    private val entry = PlaybackQueueEntry(
        providerId = 1,
        externalId = "song-1",
        queueItemId = 10,
        title = "Queue title",
        artistName = "Queue artist",
        albumExternalId = "alb-1",
        coverImageId = "cover-1",
        durationMs = 200_000,
    )

    @Test
    fun roundTripsQueueStateWithoutASongsRow() = runTest {
        val db = OmnisonicDatabaseFactory(ApplicationProvider.getApplicationContext()).openInMemory()
        try {
            val persistence = QueuePersistence(QueueSnapshotRepository(db))
            val state = QueueState(
                entries = listOf(entry),
                playIndex = 0,
                shuffle = ShuffleState(order = listOf(0), seed = 42),
                repeatMode = RepeatMode.ALL,
                speed = 1.25f,
                pitch = 0.75f,
                sourceType = "album",
                sourceData = "alb-1",
                positionMs = 123_456L,
            )

            persistence.save(state)
            val restored = persistence.restore()!!

            assertThat(restored.entries).containsExactly(entry)
            assertThat(restored.shuffle).isEqualTo(ShuffleState(order = listOf(0), seed = 42))
            assertThat(restored.repeatMode).isEqualTo(RepeatMode.ALL)
            assertThat(restored.speed).isEqualTo(1.25f)
            assertThat(restored.pitch).isEqualTo(0.75f)
            assertThat(restored.sourceData).isEqualTo("alb-1")
            assertThat(restored.positionMs).isEqualTo(123_456L)
        } finally {
            db.close()
        }
    }

    @Test
    fun aZeroPositionIsStoredAsNoRestorePoint() = runTest {
        val db = OmnisonicDatabaseFactory(ApplicationProvider.getApplicationContext()).openInMemory()
        try {
            val persistence = QueuePersistence(QueueSnapshotRepository(db))
            persistence.save(QueueState(entries = listOf(entry), positionMs = 0L))

            val stored = db.queueDao().selectState(QueueStateEntity.SINGLETON_ID)
            assertThat(stored?.positionMs).isNull()
            assertThat(persistence.restore()!!.positionMs).isEqualTo(0L)
        } finally {
            db.close()
        }
    }

    @Test
    fun emptyQueueClearsTheStoredSnapshot() = runTest {
        val db = OmnisonicDatabaseFactory(ApplicationProvider.getApplicationContext()).openInMemory()
        try {
            val persistence = QueuePersistence(QueueSnapshotRepository(db))
            persistence.save(QueueState(entries = listOf(entry)))

            persistence.save(QueueState())

            assertThat(persistence.restore()).isNull()
            assertThat(db.queueDao().countEntries()).isEqualTo(0)
            assertThat(db.queueDao().selectState(QueueStateEntity.SINGLETON_ID)).isNull()
        } finally {
            db.close()
        }
    }

    @Test
    fun restoreClampsAnOutOfRangePlayIndex() = runTest {
        val db = OmnisonicDatabaseFactory(ApplicationProvider.getApplicationContext()).openInMemory()
        try {
            val snapshots = QueueSnapshotRepository(db)
            snapshots.save(
                entries = listOf(
                    QueueSnapshotRepository.EntrySnapshot(10, 0, 1, "song-1", title = "One"),
                    QueueSnapshotRepository.EntrySnapshot(11, 1, 1, "song-2", title = "Two"),
                ),
                state = QueueSnapshotRepository.StateSnapshot(playIndex = 99),
            )

            val restored = QueuePersistence(snapshots).restore()!!

            assertThat(restored.playIndex).isEqualTo(1)
            assertThat(restored.currentEntry?.queueItemId).isEqualTo(11L)
        } finally {
            db.close()
        }
    }

    @Test
    fun invalidShuffleOrderClampsPlayIndexToTheEntryRange() = runTest {
        val db = OmnisonicDatabaseFactory(ApplicationProvider.getApplicationContext()).openInMemory()
        try {
            val snapshots = QueueSnapshotRepository(db)
            snapshots.save(
                entries = listOf(
                    QueueSnapshotRepository.EntrySnapshot(10, 0, 1, "song-1", title = "One"),
                    QueueSnapshotRepository.EntrySnapshot(11, 1, 1, "song-2", title = "Two"),
                ),
                state = QueueSnapshotRepository.StateSnapshot(
                    playIndex = 1,
                    shuffleOrder = listOf(1, 1),
                    shuffleSeed = 1,
                ),
            )

            val restored = QueuePersistence(snapshots).restore()!!

            assertThat(restored.shuffle).isNull()
            assertThat(restored.playIndex).isEqualTo(1)
            assertThat(restored.currentEntry?.queueItemId).isEqualTo(11L)
        } finally {
            db.close()
        }
    }

    @Test
    fun restoreAdvancesTheQueueItemIdCounterPastTheSnapshot() = runTest {
        val db = OmnisonicDatabaseFactory(ApplicationProvider.getApplicationContext()).openInMemory()
        try {
            PlaybackQueueEntry.resetIdCounterTo(0)
            val snapshots = QueueSnapshotRepository(db)
            snapshots.save(
                entries = listOf(
                    QueueSnapshotRepository.EntrySnapshot(10, 0, 1, "song-1", title = "One"),
                    QueueSnapshotRepository.EntrySnapshot(11, 1, 1, "song-2", title = "Two"),
                ),
                state = null,
            )

            QueuePersistence(snapshots).restore()

            assertThat(PlaybackQueueEntry.nextQueueItemId()).isEqualTo(12L)
        } finally {
            db.close()
        }
    }

    @Test
    fun invalidPersistedShuffleOrderFallsBackToEntriesOrder() = runTest {
        val db = OmnisonicDatabaseFactory(ApplicationProvider.getApplicationContext()).openInMemory()
        try {
            val snapshots = QueueSnapshotRepository(db)
            snapshots.save(
                entries = listOf(
                    QueueSnapshotRepository.EntrySnapshot(10, 0, 1, "song-1", title = "One"),
                    QueueSnapshotRepository.EntrySnapshot(11, 1, 1, "song-2", title = "Two"),
                ),
                state = QueueSnapshotRepository.StateSnapshot(
                    shuffleOrder = listOf(0, 0),
                    shuffleSeed = 1,
                ),
            )

            val restored = QueuePersistence(snapshots).restore()!!

            assertThat(restored.shuffle).isNull()
            assertThat(restored.effectiveOrder()).containsExactly(0, 1).inOrder()
        } finally {
            db.close()
        }
    }
}
