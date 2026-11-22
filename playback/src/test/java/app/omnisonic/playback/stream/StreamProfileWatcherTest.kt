package app.omnisonic.playback.stream

import app.omnisonic.core.data.network.NetworkMonitor
import app.omnisonic.core.data.network.NetworkState
import app.omnisonic.core.data.network.NetworkStateObserver
import app.omnisonic.core.data.transcode.TranscodeRendererId
import app.omnisonic.core.data.transcode.TranscodeSettings
import app.omnisonic.core.data.transcode.TranscodeSettingsStore
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueuePlayer
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.provider.api.ProviderTraits
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class RecordingQueuePlayer(initial: QueueState = QueueState()) : QueuePlayer {
    val stateFlow = MutableStateFlow(initial)
    override val state: StateFlow<QueueState> = stateFlow
    val replans = mutableListOf<Unit>()
    override fun replanCurrentStream() {
        replans += Unit
    }

    override fun setQueue(
        entries: List<PlaybackQueueEntry>,
        startEntriesIndex: Int,
        sourceType: String?,
        sourceData: String?,
    ) = Unit

    override fun append(entries: List<PlaybackQueueEntry>, sourceType: String?, sourceData: String?) = Unit
    override fun prependNext(entry: PlaybackQueueEntry) = Unit
    override fun removeEntry(queueItemId: Long) = Unit
    override fun moveEntry(queueItemId: Long, toEntriesIndex: Int) = Unit
    override fun insertEntry(entry: PlaybackQueueEntry, entriesIndex: Int, orderIndex: Int) = Unit
    override fun jumpToEntry(orderIndex: Int) = Unit
    override fun clearQueue(keepCurrent: Boolean) = Unit
    override fun restack() = Unit
    override fun skipNext(manual: Boolean) = Unit
    override fun skipPrevious() = Unit
    override fun replayCurrent() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setShuffleEnabled(enabled: Boolean) = Unit
    override fun reshuffle(seed: Long) = Unit
    override fun setRepeatMode(mode: RepeatMode) = Unit
    override fun cycleRepeatMode() = Unit
    override fun setPlaybackSpeed(scale: Float) = Unit
    override fun setPlaybackPitch(pitch: Float) = Unit
    override fun restore(snapshot: QueueState) = Unit
    override fun onEngineFinishedCurrent() = Unit
    override fun onEngineError() = Unit
    override fun onEngineAutoAdvanced(entry: PlaybackQueueEntry) = Unit
    override fun onPositionTick(positionMs: Long) = Unit
    override fun release() = Unit
}

private fun watcherEntry(id: Long) = PlaybackQueueEntry(
    providerId = 1,
    externalId = "ext-$id",
    queueItemId = id,
    title = "T$id",
    durationMs = 200_000L,
)

private class WatcherHarness(
    val store: TranscodeSettingsStore,
    val networkFlow: MutableStateFlow<NetworkState>,
    val queue: RecordingQueuePlayer,
    val playing: () -> Boolean,
) {
    val watcher: StreamProfileWatcher

    init {
        val plans = StreamPlanSource(
            settings = store,
            network = NetworkMonitor { networkFlow.value },
            traits = ProviderTraitsLookup { ProviderTraits() },
        )
        watcher = StreamProfileWatcher(
            settings = store,
            networkStates = NetworkStateObserver { networkFlow },
            plans = plans,
            queue = queue,
            isPlaying = playing,
        )
    }
}

private val WIFI = NetworkState(connected = true, wifi = true, metered = false)
private val CELLULAR = NetworkState(connected = true, wifi = false, metered = true)
private val UNMETERED_VPN = NetworkState(connected = true, wifi = false, metered = false, vpn = true)

private fun playingQueue(id: Long = 1) = RecordingQueuePlayer(
    QueueState(entries = listOf(watcherEntry(id)), playIndex = 0, positionMs = 30_000L),
)

class StreamProfileWatcherTest {

    @Test
    fun `a wifi to cellular transition while playing replans the current entry once`() = runTest {
        val store = TranscodeSettingsStore.inMemory()
        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96),
        )
        val networkFlow = MutableStateFlow(WIFI)
        val queue = playingQueue()
        val h = WatcherHarness(store, networkFlow, queue) { true }

        val job = h.watcher.start(this)
        advanceUntilIdle()
        assertThat(queue.replans).isEmpty()

        networkFlow.value = CELLULAR
        advanceUntilIdle()

        assertThat(queue.replans).hasSize(1)
        job.cancel()
    }

    @Test
    fun `a network change that keeps the same slot does not replan`() = runTest {
        val store = TranscodeSettingsStore.inMemory()
        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96, meteredAsCellular = false),
        )
        val networkFlow = MutableStateFlow(WIFI)
        val queue = playingQueue()
        val h = WatcherHarness(store, networkFlow, queue) { true }

        val job = h.watcher.start(this)
        advanceUntilIdle()

        networkFlow.value = NetworkState(connected = true, wifi = true, metered = true)
        advanceUntilIdle()
        networkFlow.value = WIFI
        advanceUntilIdle()

        assertThat(queue.replans).isEmpty()
        job.cancel()
    }

    @Test
    fun `a transcode settings edit that changes the decision replans`() = runTest {
        val store = TranscodeSettingsStore.inMemory()
        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96),
        )
        val networkFlow = MutableStateFlow(CELLULAR)
        val queue = playingQueue()
        val h = WatcherHarness(store, networkFlow, queue) { true }

        val job = h.watcher.start(this)
        advanceUntilIdle()
        assertThat(queue.replans).isEmpty()

        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 64),
        )
        advanceUntilIdle()

        assertThat(queue.replans).hasSize(1)
        job.cancel()
    }

    @Test
    fun `a vpnAsCellular toggle flips the slot on a vpn network`() = runTest {
        val store = TranscodeSettingsStore.inMemory()
        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96),
        )
        val vpn = UNMETERED_VPN
        val networkFlow = MutableStateFlow(vpn)
        val queue = playingQueue()
        val h = WatcherHarness(store, networkFlow, queue) { true }

        val job = h.watcher.start(this)
        advanceUntilIdle()

        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96, vpnAsCellular = false),
        )
        advanceUntilIdle()

        assertThat(queue.replans).hasSize(1)
        job.cancel()
    }

    @Test
    fun `a queue entry change resets the baseline and does not replan`() = runTest {
        val store = TranscodeSettingsStore.inMemory()
        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96),
        )
        val networkFlow = MutableStateFlow(WIFI)
        val queue = playingQueue(id = 1)
        val h = WatcherHarness(store, networkFlow, queue) { true }

        val job = h.watcher.start(this)
        advanceUntilIdle()

        queue.stateFlow.value = QueueState(entries = listOf(watcherEntry(2)), playIndex = 0)
        advanceUntilIdle()
        networkFlow.value = CELLULAR
        advanceUntilIdle()

        // The baseline moved to the new entry; the first change on it replans.
        assertThat(queue.replans).hasSize(1)

        networkFlow.value = WIFI
        advanceUntilIdle()
        assertThat(queue.replans).hasSize(2)
        job.cancel()
    }

    @Test
    fun `no replan while not playing`() = runTest {
        val store = TranscodeSettingsStore.inMemory()
        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96),
        )
        val networkFlow = MutableStateFlow(WIFI)
        val queue = playingQueue()
        val h = WatcherHarness(store, networkFlow, queue) { false }

        val job = h.watcher.start(this)
        advanceUntilIdle()

        networkFlow.value = CELLULAR
        advanceUntilIdle()

        assertThat(queue.replans).isEmpty()
        job.cancel()
    }

    @Test
    fun `an idle queue never replans`() = runTest {
        val store = TranscodeSettingsStore.inMemory()
        store.setSettings(
            TranscodeRendererId.Local,
            TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96),
        )
        val networkFlow = MutableStateFlow(WIFI)
        val queue = RecordingQueuePlayer()
        val h = WatcherHarness(store, networkFlow, queue) { true }

        val job = h.watcher.start(this)
        advanceUntilIdle()

        networkFlow.value = CELLULAR
        advanceUntilIdle()

        assertThat(queue.replans).isEmpty()
        job.cancel()
    }

    @Test
    fun `rapid toggles replan once per actual decision change`() = runTest {
        val store = TranscodeSettingsStore.inMemory()
        val base = TranscodeSettings(wifiBitrateKbps = 320, cellularBitrateKbps = 96)
        store.setSettings(TranscodeRendererId.Local, base)
        val vpn = UNMETERED_VPN
        val networkFlow = MutableStateFlow(vpn)
        val queue = playingQueue()
        val h = WatcherHarness(store, networkFlow, queue) { true }

        val job = h.watcher.start(this)
        advanceUntilIdle()

        store.setSettings(TranscodeRendererId.Local, base.copy(vpnAsCellular = false))
        advanceUntilIdle()
        store.setSettings(TranscodeRendererId.Local, base.copy(vpnAsCellular = true))
        advanceUntilIdle()
        store.setSettings(TranscodeRendererId.Local, base.copy(vpnAsCellular = false))
        advanceUntilIdle()

        // cellular -> wifi -> cellular -> wifi: three decision changes.
        assertThat(queue.replans).hasSize(3)
        job.cancel()
    }

}
