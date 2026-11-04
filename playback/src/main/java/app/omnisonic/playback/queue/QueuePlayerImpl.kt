package app.omnisonic.playback.queue

import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.events.PlaybackEventBus
import app.omnisonic.playback.api.events.TrackChangeCause
import app.omnisonic.playback.api.player.OmniPlayer
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueuePlayer
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.queue.ShuffleOrders
import app.omnisonic.playback.api.queue.ShuffleState
import app.omnisonic.playback.api.stream.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

/**
 * Queue machine over one [OmniPlayer]. Commands must run on [engineScope] (the
 * engine's application thread, same contract as OmniPlayer's external scope);
 * [StreamResolver] owns its own IO dispatch. The play order is tracked by
 * queue item id so queue edits never lose the active playback position;
 * shuffle anchors the current entry at the head of a fresh deterministic
 * [ShuffleOrders] order, and the entries list keeps its original order for
 * edits and persistence.
 *
 * Skipped is emitted whenever playback leaves a track before the engine
 * reached its end (user next/prev/jump, queue replacement, current-entry
 * removal). Engine errors advance without a Skipped emission. Started/paused/
 * position/scrobble-point emissions belong to the controller layer.
 *
 * The engine holds a [current, next] playlist for gapless handoff: after every
 * committed start the machine resolves the expected next entry under the
 * repeat policy and hands it to [OmniPlayer.prepareNext] (a null next trims
 * the tail, including under repeat ONE so the engine can end naturally). An
 * engine auto-advance is validated in [onEngineAutoAdvanced] against the
 * expected next entry and adopted without replay; divergence resyncs to the
 * queue's current entry.
 */
class QueuePlayerImpl(
    private val engineScope: CoroutineScope,
    private val player: OmniPlayer,
    private val streamResolver: StreamResolver,
    private val bus: PlaybackEventBus,
    private val prevRestartThresholdMs: Long = DEFAULT_PREV_RESTART_THRESHOLD_MS,
    private val minResumePositionMs: Long = DEFAULT_MIN_RESUME_POSITION_MS,
) : QueuePlayer {

    private val _state = MutableStateFlow(QueueState())
    override val state: StateFlow<QueueState> = _state.asStateFlow()

    private var entries: List<PlaybackQueueEntry> = emptyList()
    private var playOrder: List<Long> = emptyList()
    private var playIndex = 0
    private var shuffleOn = false
    private var shuffleSeed = 0L
    private var repeat = RepeatMode.OFF
    private var speed = 1f
    private var pitch = 1f
    private var sourceType: String? = null
    private var sourceData: String? = null

    private var playing = false
    private var lastPositionMs = 0L
    private var pendingPositionMs = 0L
    private var pendingPositionId: Long? = null
    private var startGeneration = 0
    private var startFailures = 0
    private var startJob: Job? = null
    private var prepareJob: Job? = null
    private var released = false

    override fun setQueue(
        entries: List<PlaybackQueueEntry>,
        startEntriesIndex: Int,
        sourceType: String?,
        sourceData: String?,
    ) {
        if (released) return
        val previous = currentEntry()
        previous?.let { bus.emit(PlaybackEvent.Skipped(it, lastPositionMs, manual = true)) }
        this.entries = entries.toList()
        this.sourceType = sourceType
        this.sourceData = sourceData
        lastPositionMs = 0L
        if (this.entries.isEmpty()) {
            playOrder = emptyList()
            playIndex = 0
            resetPlayback()
            refreshState()
            bus.emit(PlaybackEvent.TrackChanged(previous, null, TrackChangeCause.SOURCE_START, _state.value))
            return
        }
        val startIndex = startEntriesIndex.coerceIn(this.entries.indices)
        val startId = this.entries[startIndex].queueItemId
        if (shuffleOn) {
            bumpSeed()
            regenerateOrder(startIndex)
            playIndex = orderPosFor(startId)?.takeIf { it >= 0 } ?: 0
        } else {
            playOrder = this.entries.map { it.queueItemId }
            playIndex = startIndex
        }
        playing = true
        refreshState()
        startCurrent(TrackChangeCause.SOURCE_START, previous)
    }

    override fun append(entries: List<PlaybackQueueEntry>, sourceType: String?, sourceData: String?) {
        if (released) return
        sourceType?.let { this.sourceType = it }
        sourceData?.let { this.sourceData = it }
        val known = this.entries.mapTo(mutableSetOf()) { it.queueItemId }
        val fresh = mutableListOf<PlaybackQueueEntry>()
        entries.forEach { candidate ->
            if (candidate.queueItemId !in known) {
                fresh += candidate
                known += candidate.queueItemId
            }
        }
        if (fresh.isEmpty()) return
        this.entries = this.entries + fresh
        playOrder = playOrder + fresh.map { it.queueItemId }
        refreshState()
        schedulePrepareFollowing()
    }

    override fun prependNext(entry: PlaybackQueueEntry) {
        if (released) return
        val current = currentEntry()
        playOrder = playOrder - entry.queueItemId
        if (entriesIndexFor(entry.queueItemId) < 0) {
            val insertAt = if (current == null) entries.size else entriesIndexFor(current.queueItemId) + 1
            entries = entries.toMutableList().apply { add(insertAt.coerceAtMost(entries.size), entry) }
        }
        if (playing && current != null) {
            playOrder = playOrder.toMutableList().apply { add(playIndex + 1, entry.queueItemId) }
            refreshState()
            schedulePrepareFollowing()
        } else {
            playOrder = playOrder + entry.queueItemId
            playIndex = playOrder.size - 1
            playing = true
            refreshState()
            startCurrent(TrackChangeCause.SOURCE_START, null)
        }
    }

    override fun removeEntry(queueItemId: Long) {
        if (released) return
        if (entriesIndexFor(queueItemId) < 0 || !playOrder.contains(queueItemId)) return
        val previous = currentEntry()
        val removingCurrent = previous != null && previous.queueItemId == queueItemId
        val orderPos = playOrder.indexOf(queueItemId)
        if (removingCurrent) {
            bus.emit(PlaybackEvent.Skipped(previous!!, lastPositionMs, manual = true))
        }
        playOrder = playOrder - queueItemId
        entries = entries.filterNot { it.queueItemId == queueItemId }
        if (orderPos < playIndex) playIndex -= 1
        refreshState()
        schedulePrepareFollowing()
        when {
            entries.isEmpty() -> {
                resetPlayback()
                bus.emit(PlaybackEvent.TrackChanged(previous, null, TrackChangeCause.QUEUE_EDIT, _state.value))
            }
            removingCurrent && playing -> {
                if (currentEntry() == null) {
                    endPlayback(previous, TrackChangeCause.QUEUE_EDIT)
                } else {
                    startCurrent(TrackChangeCause.QUEUE_EDIT, previous)
                }
            }
            removingCurrent -> bus.emit(PlaybackEvent.TrackChanged(previous, currentEntry(), TrackChangeCause.QUEUE_EDIT, _state.value))
        }
    }

    override fun moveEntry(queueItemId: Long, toEntriesIndex: Int) {
        if (released) return
        val from = entriesIndexFor(queueItemId)
        if (from < 0) return
        val currentId = currentEntry()?.queueItemId
        val list = entries.toMutableList()
        val moved = list.removeAt(from)
        val target = toEntriesIndex.coerceIn(0, list.size)
        list.add(target, moved)
        entries = list
        if (!shuffleOn) {
            playOrder = entries.map { it.queueItemId }
        }
        repositionPlayIndexTo(currentId)
        refreshState()
        schedulePrepareFollowing()
    }

    override fun jumpToEntry(orderIndex: Int) {
        jumpToOrderPos(orderIndex, TrackChangeCause.JUMP)
    }

    override fun clearQueue(keepCurrent: Boolean) {
        if (released) return
        val previous = currentEntry()
        if (keepCurrent && previous != null) {
            entries = listOf(previous)
            playOrder = listOf(previous.queueItemId)
            playIndex = 0
            refreshState()
            schedulePrepareFollowing()
            return
        }
        val oldPrevious = previous
        resetPlayback()
        entries = emptyList()
        playOrder = emptyList()
        playIndex = 0
        sourceType = null
        sourceData = null
        lastPositionMs = 0L
        refreshState()
        schedulePrepareFollowing()
        bus.emit(PlaybackEvent.TrackChanged(oldPrevious, null, TrackChangeCause.QUEUE_EDIT, _state.value))
    }

    override fun restack() {
        if (released) return
        if (entries.isEmpty()) return
        val currentId = currentEntry()?.queueItemId
        if (shuffleOn) {
            bumpSeed()
            regenerateOrder(currentEntriesIndex() ?: -1)
        } else {
            playOrder = entries.map { it.queueItemId }
        }
        repositionPlayIndexTo(currentId)
        refreshState()
        schedulePrepareFollowing()
    }

    override fun skipNext(manual: Boolean) {
        if (released) return
        val previous = currentEntry() ?: return
        bus.emit(PlaybackEvent.Skipped(previous, lastPositionMs, manual))
        val cause = if (manual) TrackChangeCause.USER_NEXT else TrackChangeCause.AUTO_ADVANCE
        val next = when (repeat) {
            RepeatMode.OFF -> (playIndex + 1).takeIf { it < playOrder.size }
            else -> (playIndex + 1) % playOrder.size
        }
        if (next == null) {
            endPlayback(previous, cause)
            return
        }
        if (next == 0 && shuffleOn) {
            bumpSeed()
            regenerateOrder(-1)
        }
        playIndex = next
        refreshState()
        startCurrent(cause, previous)
    }

    override fun skipPrevious() {
        if (released) return
        val current = currentEntry() ?: return
        if (lastPositionMs > prevRestartThresholdMs) {
            player.seekToStart()
            return
        }
        val previousPos = playIndex - 1
        if (previousPos < 0 && repeat != RepeatMode.ALL) {
            player.seekToStart()
            return
        }
        if (previousPos < 0) {
            // repeat ALL wraps; a shuffled order is regenerated for the new pass
            if (shuffleOn) {
                bumpSeed()
                regenerateOrder(-1)
            }
            playIndex = playOrder.size - 1
        } else {
            playIndex = previousPos
        }
        bus.emit(PlaybackEvent.Skipped(current, lastPositionMs, manual = true))
        refreshState()
        startCurrent(TrackChangeCause.USER_PREV, current)
    }

    override fun replayCurrent() {
        if (released) return
        if (currentEntry() == null) return
        startCurrent(TrackChangeCause.SOURCE_START, null)
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val target = positionMs.coerceAtLeast(0L)
        player.seekTo(target)
        lastPositionMs = target
        if (playing) {
            pendingPositionMs = 0L
            pendingPositionId = null
        } else {
            pendingPositionMs = target
            pendingPositionId = currentEntry()?.queueItemId
        }
        refreshState()
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        if (released) return
        if (shuffleOn == enabled) return
        val currentId = currentEntry()?.queueItemId
        shuffleOn = enabled
        if (enabled) {
            bumpSeed()
            regenerateOrder(currentEntriesIndex() ?: -1)
        } else {
            playOrder = entries.map { it.queueItemId }
        }
        repositionPlayIndexTo(currentId)
        refreshState()
        schedulePrepareFollowing()
    }

    override fun reshuffle(seed: Long) {
        shuffleSeed = seed
        if (!shuffleOn) return
        val currentId = currentEntry()?.queueItemId
        regenerateOrder(currentEntriesIndex() ?: -1)
        repositionPlayIndexTo(currentId)
        refreshState()
        schedulePrepareFollowing()
    }

    override fun setRepeatMode(mode: RepeatMode) {
        if (repeat == mode) return
        repeat = mode
        refreshState()
        schedulePrepareFollowing()
    }

    override fun cycleRepeatMode() {
        repeat = when (repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        refreshState()
        schedulePrepareFollowing()
    }

    override fun setPlaybackSpeed(scale: Float) {
        if (!scale.isFinite()) return
        val target = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (speed == target) return
        speed = target
        player.setPlaybackSpeed(target)
        refreshState()
    }

    override fun setPlaybackPitch(pitch: Float) {
        if (!pitch.isFinite()) return
        val target = pitch.coerceIn(MIN_SCALE, MAX_SCALE)
        if (this.pitch == target) return
        this.pitch = target
        player.setPlaybackPitch(target)
        refreshState()
    }

    override fun restore(snapshot: QueueState) {
        if (released) return
        entries = snapshot.entries
        val snapShuffle = snapshot.shuffle
        val orderValid = snapShuffle != null &&
            entries.size == snapShuffle.order.size &&
            snapShuffle.order.sorted() == entries.indices.toList()
        shuffleOn = orderValid
        playOrder = if (orderValid) {
            shuffleSeed = snapShuffle.seed
            snapShuffle.order.map { entries[it].queueItemId }
        } else {
            entries.map { it.queueItemId }
        }
        playIndex = if (playOrder.isEmpty()) 0 else snapshot.playIndex.coerceIn(0, playOrder.lastIndex)
        repeat = snapshot.repeatMode
        speed = sanitizeScale(snapshot.speed, speed)
        pitch = sanitizeScale(snapshot.pitch, pitch)
        player.setPlaybackSpeed(speed)
        player.setPlaybackPitch(pitch)
        sourceType = snapshot.sourceType
        sourceData = snapshot.sourceData
        val restoredPosition = restorePositionFor(snapshot.positionMs, currentEntry())
        lastPositionMs = restoredPosition
        pendingPositionMs = restoredPosition
        pendingPositionId = if (restoredPosition > 0L) currentEntry()?.queueItemId else null
        refreshState()
        schedulePrepareFollowing()
        bus.emit(PlaybackEvent.TrackChanged(null, currentEntry(), TrackChangeCause.RESTORE, _state.value))
    }

    override fun onEngineFinishedCurrent() {
        val entry = currentEntry() ?: return
        bus.emit(PlaybackEvent.Finished(entry, entry.durationMs ?: lastPositionMs))
        when (repeat) {
            RepeatMode.ONE -> startCurrent(TrackChangeCause.REPEAT_ONE, entry)
            else -> advanceNatural(entry, TrackChangeCause.AUTO_ADVANCE)
        }
    }

    override fun onEngineError() {
        val entry = currentEntry() ?: return
        advanceNatural(entry, TrackChangeCause.AUTO_ADVANCE)
    }

    override fun onEngineAutoAdvanced(entry: PlaybackQueueEntry) {
        if (released) return
        val expectedPos = nextOrderPos()
        val expected = expectedPos?.let { entryAtOrderPos(it) }
        if (expected == null || expected.queueItemId != entry.queueItemId) {
            resyncAfterDivergence()
            return
        }
        val previous = currentEntry()
        previous?.let { bus.emit(PlaybackEvent.Finished(it, it.durationMs ?: lastPositionMs)) }
        playIndex = expectedPos
        playing = true
        refreshState()
        bus.emit(PlaybackEvent.TrackChanged(previous, expected, TrackChangeCause.AUTO_ADVANCE, _state.value))
        schedulePrepareFollowing()
    }

    override fun onPositionTick(positionMs: Long) {
        if (positionMs < 0) return
        lastPositionMs = positionMs
        val snapshot = snapshotPositionMs()
        if (abs(snapshot - _state.value.positionMs) >= POSITION_SNAPSHOT_GRANULARITY_MS) refreshState()
    }

    override fun release() {
        if (released) return
        released = true
        startJob?.cancel()
        prepareJob?.cancel()
        startGeneration++
        resetPlayback()
        engineScope.cancel()
    }

    private fun startCurrent(cause: TrackChangeCause, previous: PlaybackQueueEntry?) {
        if (released) return
        val target = currentEntry()
        if (target == null) {
            endPlayback(previous, cause)
            return
        }
        val gen = ++startGeneration
        playing = true
        startJob?.cancel()
        prepareJob?.cancel()
        startJob = engineScope.launch {
            val resolved = streamResolver.resolve(target)
            if (gen != startGeneration) return@launch
            if (resolved == null) {
                startFailureBounce(gen, previous, cause)
                return@launch
            }
            startFailures = 0
            val restorePosition = pendingPositionMs
                .takeIf { pendingPositionId == target.queueItemId }
                ?: 0L
            pendingPositionMs = 0L
            pendingPositionId = null
            val stream = if (restorePosition > 0L) resolved.copy(startPositionMs = restorePosition) else resolved
            lastPositionMs = stream.startPositionMs
            player.play(stream, target)
            refreshState()
            bus.emit(PlaybackEvent.TrackChanged(previous, target, cause, _state.value))
            schedulePrepareFollowing()
        }
    }

    private fun startFailureBounce(gen: Int, previous: PlaybackQueueEntry?, cause: TrackChangeCause) {
        if (gen != startGeneration) return
        startFailures += 1
        if (startFailures > max(1, playOrder.size)) {
            endPlayback(previous, cause)
            return
        }
        val next = when (repeat) {
            RepeatMode.OFF -> (playIndex + 1).takeIf { it < playOrder.size }
            else -> (playIndex + 1) % playOrder.size
        }
        if (next == null) {
            endPlayback(previous, cause)
            return
        }
        playIndex = next
        refreshState()
        startCurrent(cause, previous)
    }

    private fun advanceNatural(previous: PlaybackQueueEntry, cause: TrackChangeCause) {
        val next = (playIndex + 1).takeIf { it < playOrder.size }
        if (next == null) {
            if (repeat == RepeatMode.ALL) {
                if (shuffleOn) {
                    bumpSeed()
                    regenerateOrder(-1)
                }
                playIndex = 0
            } else {
                endPlayback(previous, cause)
                return
            }
        } else {
            playIndex = next
        }
        refreshState()
        startCurrent(cause, previous)
    }

    private fun endPlayback(previous: PlaybackQueueEntry?, cause: TrackChangeCause) {
        resetPlayback()
        refreshState()
        bus.emit(PlaybackEvent.TrackChanged(previous, null, cause, _state.value))
    }

    private fun resetPlayback() {
        playing = false
        startGeneration++
        startJob?.cancel()
        prepareJob?.cancel()
        player.stop()
    }

    /**
     * Resolves the expected next entry under the repeat policy and hands it to
     * the player; a null next (queue end, repeat ONE) trims any prepared tail
     * so the engine can end naturally. Pending preparations are cancelled and
     * re-derived on every queue edit and start; an idle queue keeps nothing
     * prepared because the engine holds no playlist.
     */
    private fun schedulePrepareFollowing() {
        if (released || !playing) return
        val gen = startGeneration
        prepareJob?.cancel()
        prepareJob = engineScope.launch {
            val nextPos = nextOrderPos()
            val nextEntry = nextPos?.let { entryAtOrderPos(it) }
            val resolved = if (nextEntry != null && gen == startGeneration) {
                streamResolver.resolve(nextEntry)
            } else {
                null
            }
            if (gen != startGeneration) return@launch
            player.prepareNext(resolved)
        }
    }

    private fun resyncAfterDivergence() {
        val current = currentEntry() ?: return
        resetPlayback()
        refreshState()
        startCurrent(TrackChangeCause.AUTO_ADVANCE, current)
    }

    private fun nextOrderPos(): Int? {
        if (!playing) return null
        val size = playOrder.size
        if (size == 0) return null
        return when (repeat) {
            RepeatMode.ONE -> null
            RepeatMode.OFF -> (playIndex + 1).takeIf { it < size }
            RepeatMode.ALL -> (playIndex + 1) % size
        }
    }

    private fun jumpToOrderPos(orderPos: Int, cause: TrackChangeCause) {
        if (released) return
        val target = entryAtOrderPos(orderPos) ?: return
        val previous = currentEntry()
        if (previous != null && previous.queueItemId == target.queueItemId) return
        previous?.let { bus.emit(PlaybackEvent.Skipped(it, lastPositionMs, manual = true)) }
        playIndex = orderPos
        playing = true
        refreshState()
        startCurrent(cause, previous)
    }

    private fun regenerateOrder(anchorEntriesIndex: Int) {
        playOrder = ShuffleOrders.shuffled(entries.size, shuffleSeed, anchorEntriesIndex)
            .map { entries[it].queueItemId }
    }

    private fun repositionPlayIndexTo(currentId: Long?) {
        val at = currentId?.let { playOrder.indexOf(it) } ?: -1
        playIndex = if (at >= 0) at else playIndex.coerceIn(0, playOrder.size)
    }

    private fun bumpSeed() {
        shuffleSeed += 1
    }

    private fun entryAtOrderPos(orderPos: Int): PlaybackQueueEntry? {
        val id = playOrder.getOrNull(orderPos) ?: return null
        return entries.firstOrNull { it.queueItemId == id }
    }

    private fun currentEntry(): PlaybackQueueEntry? = entryAtOrderPos(playIndex)

    private fun orderPosFor(id: Long): Int? = playOrder.indexOf(id).takeIf { it >= 0 }

    private fun entriesIndexFor(id: Long): Int = entries.indexOfFirst { it.queueItemId == id }

    private fun currentEntriesIndex(): Int? {
        val id = currentEntry()?.queueItemId ?: return null
        return entriesIndexFor(id).takeIf { it >= 0 }
    }

    private fun refreshState() {
        _state.value = snapshotState()
    }

    private fun snapshotState(): QueueState {
        val order: List<Int> = if (shuffleOn) {
            playOrder.map { entriesIndexFor(it) }
        } else {
            entries.indices.toList()
        }
        return QueueState(
            entries = entries,
            playIndex = playIndex,
            shuffle = if (shuffleOn) ShuffleState(order, shuffleSeed) else null,
            repeatMode = repeat,
            speed = speed,
            pitch = pitch,
            sourceType = sourceType,
            sourceData = sourceData,
            positionMs = snapshotPositionMs(),
        )
    }

    /**
     * Persisted resume point. A pending restore position survives an idle
     * (restored but not started) queue; live positions are only kept once they
     * passed [minResumePositionMs] and stay strictly inside the track.
     */
    private fun snapshotPositionMs(): Long {
        val current = currentEntry()
        if (pendingPositionMs > 0L && pendingPositionId == current?.queueItemId) return pendingPositionMs
        if (!playing) return 0L
        val position = lastPositionMs
        if (position < minResumePositionMs) return 0L
        val duration = current?.durationMs
        if (duration != null && position >= duration) return 0L
        return position
    }

    private fun restorePositionFor(positionMs: Long, entry: PlaybackQueueEntry?): Long {
        if (entry == null || positionMs <= 0L) return 0L
        if (positionMs < minResumePositionMs) return 0L
        val duration = entry.durationMs
        if (duration != null && positionMs >= duration) return 0L
        return positionMs
    }

    private fun sanitizeScale(candidate: Float, fallback: Float): Float {
        if (!candidate.isFinite()) return fallback
        return candidate.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    companion object {
        const val DEFAULT_PREV_RESTART_THRESHOLD_MS = 3_000L
        const val DEFAULT_MIN_RESUME_POSITION_MS = 10_000L
        const val POSITION_SNAPSHOT_GRANULARITY_MS = 5_000L
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 3f
    }
}
