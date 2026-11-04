package app.omnisonic.playback.queue

import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class RecordingStore : QueueSnapshotStore {
    val saved = mutableListOf<QueueState>()
    var stored: QueueState? = null

    override suspend fun save(state: QueueState) {
        saved += state
    }

    override suspend fun restore(): QueueState? = stored

    override suspend fun clear() {
        saved.clear()
    }
}

private fun queueState(id: Long) = QueueState(
    entries = listOf(
        PlaybackQueueEntry(
            providerId = 1,
            externalId = "ext-$id",
            queueItemId = id,
            title = "T$id",
        ),
    ),
)

@OptIn(ExperimentalCoroutinesApi::class)
class QueuePersistenceCoordinatorTest {

    @Test
    fun startupEmptyStateIsNotWrittenBack() = runTest {
        val store = RecordingStore()
        val coordinator = QueuePersistenceCoordinator(store, this)
        val state = MutableStateFlow(QueueState())

        coordinator.observe(state)
        advanceTimeBy(1_000)

        assertThat(store.saved).isEmpty()
        coordinator.stop()
    }

    @Test
    fun rapidChangesCoalesceIntoOneDebouncedSave() = runTest {
        val store = RecordingStore()
        val coordinator = QueuePersistenceCoordinator(store, this)
        val state = MutableStateFlow(QueueState())
        coordinator.observe(state)

        state.value = queueState(2)
        runCurrent()
        advanceTimeBy(300)
        state.value = queueState(3)
        runCurrent()
        advanceTimeBy(300)

        assertThat(store.saved).isEmpty()

        advanceTimeBy(250)

        assertThat(store.saved.map { it.entries.single().queueItemId }).containsExactly(3L)
        coordinator.stop()
    }

    @Test
    fun flushWritesThePendingSnapshotImmediately() = runTest {
        val store = RecordingStore()
        val coordinator = QueuePersistenceCoordinator(store, this)
        val state = MutableStateFlow(QueueState())
        coordinator.observe(state)

        state.value = queueState(2)
        runCurrent()
        coordinator.flush()

        assertThat(store.saved).hasSize(1)
        assertThat(store.saved.single().entries.single().queueItemId).isEqualTo(2L)

        advanceTimeBy(1_000)
        assertThat(store.saved).hasSize(1)
        coordinator.stop()
    }

    @Test
    fun anEmptiedQueueIsPersistedAsAClearSignal() = runTest {
        val store = RecordingStore()
        val coordinator = QueuePersistenceCoordinator(store, this)
        val state = MutableStateFlow(QueueState())
        coordinator.observe(state)

        state.value = queueState(2)
        runCurrent()
        advanceTimeBy(600)
        state.value = QueueState()
        runCurrent()
        advanceTimeBy(600)

        assertThat(store.saved).hasSize(2)
        assertThat(store.saved.last().entries).isEmpty()
        coordinator.stop()
    }

    @Test
    fun stopCancelsAPendingSave() = runTest {
        val store = RecordingStore()
        val coordinator = QueuePersistenceCoordinator(store, this)
        val state = MutableStateFlow(QueueState())
        coordinator.observe(state)

        state.value = queueState(2)
        runCurrent()
        coordinator.stop()
        advanceTimeBy(1_000)

        assertThat(store.saved).isEmpty()
    }

    @Test
    fun restoreAppliesTheStoredSnapshotWithoutStartingPlayback() = runTest {
        val store = RecordingStore().apply { stored = queueState(7) }
        val coordinator = QueuePersistenceCoordinator(store, this)
        var applied: QueueState? = null

        assertThat(coordinator.restoreInto { applied = it }).isTrue()

        assertThat(applied).isEqualTo(queueState(7))
    }

    @Test
    fun restoreReportsNoSnapshotWhenTheStoreIsEmpty() = runTest {
        val coordinator = QueuePersistenceCoordinator(RecordingStore(), this)
        var applied: QueueState? = null

        assertThat(coordinator.restoreInto { applied = it }).isFalse()

        assertThat(applied).isNull()
    }

    @Test
    fun aRestoredSnapshotIsNotImmediatelyRewritten() = runTest {
        val store = RecordingStore().apply { stored = queueState(7) }
        val coordinator = QueuePersistenceCoordinator(store, this)
        val state = MutableStateFlow(QueueState())
        coordinator.observe(state)

        coordinator.restoreInto { state.value = it }
        advanceTimeBy(1_000)

        assertThat(store.saved).isEmpty()
        coordinator.stop()
    }
}
