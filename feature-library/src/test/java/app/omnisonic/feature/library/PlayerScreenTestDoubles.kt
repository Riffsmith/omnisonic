package app.omnisonic.feature.library

import app.omnisonic.playback.api.controller.PlaybackController
import app.omnisonic.playback.api.controller.PlayerCoreState
import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.events.PlaybackEventBus
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.renderer.PlaybackRenderer
import app.omnisonic.playback.api.settings.NotificationAction
import app.omnisonic.playback.api.source.QueueSources
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Records controller dispatch for the now-playing/queue view-model suites. */
internal class FakePlaybackController : PlaybackController {

    val player = MutableStateFlow(PlayerCoreState())
    val queue = MutableStateFlow(QueueState())

    override val playerState: StateFlow<PlayerCoreState> = player
    override val queueState: StateFlow<QueueState> = queue

    override val events: PlaybackEventBus = object : PlaybackEventBus {
        private val flow = MutableSharedFlow<PlaybackEvent>()
        override val events: SharedFlow<PlaybackEvent> = flow
        override fun emit(event: PlaybackEvent) = Unit
    }

    override val queueSources: QueueSources = QueueSources(emptyList())

    val plays = mutableListOf<Pair<List<PlaybackQueueEntry>, Int>>()
    val playOrPauseCalls = mutableListOf<Unit>()
    val nextCalls = mutableListOf<Unit>()
    val previousCalls = mutableListOf<Unit>()
    val seeks = mutableListOf<Long>()
    val shuffleCalls = mutableListOf<Boolean>()
    val repeatCalls = mutableListOf<RepeatMode>()
    val jumps = mutableListOf<Int>()
    val removed = mutableListOf<Long>()
    val moves = mutableListOf<Pair<Long, Int>>()
    val clearCalls = mutableListOf<Unit>()

    override suspend fun play(
        entries: List<PlaybackQueueEntry>,
        startEntriesIndex: Int,
        sourceType: String?,
        sourceData: String?,
    ) {
        plays += entries to startEntriesIndex
    }

    override fun playOrPause() {
        playOrPauseCalls += Unit
    }

    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() = Unit
    override fun seekTo(positionMs: Long) {
        seeks += positionMs
    }

    override fun setPlaybackSpeed(scale: Float) = Unit
    override fun setPlaybackPitch(pitch: Float) = Unit
    override fun applyAction(action: NotificationAction) = Unit

    override val renderer: PlaybackRenderer = object : PlaybackRenderer {
        override val rendererId: String = "fake"
        override val isLocal: Boolean = true
        override suspend fun activate(): Nothing = error("not used")
        override fun deactivate() = Unit
        override fun isActive(): Boolean = false
    }

    override suspend fun restoreQueue(): Boolean = false
    override fun release() = Unit

    override fun skipNext() {
        nextCalls += Unit
    }

    override fun skipPrevious() {
        previousCalls += Unit
    }

    override fun jumpToOrderIndex(orderIndex: Int) {
        jumps += orderIndex
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeatCalls += mode
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        shuffleCalls += enabled
    }

    override fun removeFromQueue(queueItemId: Long) {
        removed += queueItemId
    }

    override fun moveInQueue(queueItemId: Long, toEntriesIndex: Int) {
        moves += queueItemId to toEntriesIndex
    }

    override fun clearQueueExceptCurrent() {
        clearCalls += Unit
    }

    override fun onPlayWhenReady(playWhenReady: Boolean): Boolean = false
}

internal fun entry(id: Long, title: String = "Track $id", durationMs: Long? = 200_000L) =
    PlaybackQueueEntry(
        providerId = 1,
        externalId = "ext-$id",
        queueItemId = id,
        songRowId = id,
        title = title,
        artistName = "Artist $id",
        coverImageId = "cover-$id",
        durationMs = durationMs,
    )
