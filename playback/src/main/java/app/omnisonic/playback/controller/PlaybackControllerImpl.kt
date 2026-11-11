package app.omnisonic.playback.controller

import app.omnisonic.playback.api.controller.PlaybackController
import app.omnisonic.playback.api.controller.PlayerCoreState
import app.omnisonic.playback.api.events.PauseReason
import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.events.PlaybackEventBus
import app.omnisonic.playback.api.player.OmniPlayer
import app.omnisonic.playback.api.player.OmniPlayerListener
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueuePlayer
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.renderer.PlaybackRenderer
import app.omnisonic.playback.api.settings.NotificationAction
import app.omnisonic.playback.api.source.NoOpQueueSources
import app.omnisonic.playback.api.source.QueueSources
import app.omnisonic.playback.focus.AudioFocusHelper
import app.omnisonic.playback.focus.AudioFocusOwner
import app.omnisonic.playback.focus.FocusOwnerFactory
import app.omnisonic.playback.queue.QueuePersistenceCoordinator
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The command hub the UI, session and notification talk to. Owns transport
 * (play/pause/resume/stop), focus, the position ticker, queue persistence
 * wiring and the engine-event bridge into the queue machine. Renderer output
 * stays behind [PlaybackRenderer] so a non-local renderer can replace the
 * local player later.
 */
class PlaybackControllerImpl(
    private val scope: CoroutineScope,
    override val renderer: PlaybackRenderer,
    private val player: OmniPlayer,
    private val queue: QueuePlayer,
    override val events: PlaybackEventBus,
    private val persistence: QueuePersistenceCoordinator,
    focusOwnerFactory: FocusOwnerFactory,
    override val queueSources: QueueSources = NoOpQueueSources(),
    private val tickIntervalMs: Long = DEFAULT_TICK_INTERVAL_MS,
    private val positionReader: () -> Long = { player.exposedPlayer().currentPosition },
) : PlaybackController {

    private val _playerState = MutableStateFlow(PlayerCoreState())
    override val playerState: StateFlow<PlayerCoreState> = _playerState.asStateFlow()

    override val queueState: StateFlow<QueueState> = queue.state

    @Volatile
    private var playIntent = false

    @Volatile
    private var enginePlaying = false

    @Volatile
    private var ducked = false

    private var engineLoaded = false
    private var idle = true
    private var positionMs = 0L
    private var ticker: Job? = null
    private var queueObserver: Job? = null
    private var eventObserver: Job? = null
    private var released = false

    private val scrobblePoints = ScrobblePointTracker(events)

    private val focus: AudioFocusOwner = focusOwnerFactory.create(
        object : AudioFocusHelper.Callbacks {
            override fun isPlaying(): Boolean = enginePlaying

            override fun onDuck(factor: Float) {
                ducked = true
                player.setDuckLinear(factor)
                publishState()
            }

            override fun onUnduck() {
                ducked = false
                player.setDuckLinear(1f)
                publishState()
            }

            override fun onPauseForFocus() {
                pauseInternal(PauseReason.FOCUS, flushSnapshot = false, fade = false)
            }

            override fun onResumeAfterFocus() {
                resumeInternal()
            }
        },
    )

    private val engineListener = object : OmniPlayerListener {
        override fun onEngineFinished(entry: PlaybackQueueEntry) {
            queue.onEngineFinishedCurrent()
        }

        override fun onError(entry: PlaybackQueueEntry?, error: PlaybackException) {
            queue.onEngineError()
        }

        override fun isPlayingChanged(isPlaying: Boolean) {
            enginePlaying = isPlaying
            if (isPlaying) engineLoaded = true
            publishState()
        }

        override fun onEngineReady(entry: PlaybackQueueEntry) {
            engineLoaded = true
        }

        override fun onAutoAdvance(entry: PlaybackQueueEntry) {
            queue.onEngineAutoAdvanced(entry)
        }
    }

    init {
        player.setListener(engineListener)
        persistence.observe(queue.state)
        queueObserver = scope.launch {
            queue.state.collect { state ->
                if (state.currentEntry == null && state.entries.isEmpty()) {
                    positionMs = 0
                }
                publishState()
            }
        }
        eventObserver = scope.launch {
            events.events.collect { event ->
                when (event) {
                    is PlaybackEvent.Started -> scrobblePoints.onPlaybackStarted()
                    is PlaybackEvent.TrackChanged ->
                        if (event.current != null) scrobblePoints.onPlaybackStarted()
                    is PlaybackEvent.Position ->
                        scrobblePoints.onPosition(event.entry, event.positionMs, event.isPlaying)
                    is PlaybackEvent.Finished ->
                        scrobblePoints.onFinished(event.entry, event.playedAtMs)
                    else -> Unit
                }
                if (event is PlaybackEvent.TrackChanged && event.current == null) goIdle()
            }
        }
    }

    override suspend fun play(
        entries: List<PlaybackQueueEntry>,
        startEntriesIndex: Int,
        sourceType: String?,
        sourceData: String?,
    ) {
        if (released) return
        if (!focus.requestFocus()) return
        playIntent = true
        idle = false
        engineLoaded = true
        positionMs = 0
        queue.setQueue(entries, startEntriesIndex, sourceType, sourceData)
        val state = queue.state.value
        val entry = state.currentEntry ?: return
        events.emit(PlaybackEvent.Started(entry, state.positionMs))
        startTicker()
        publishState()
    }

    override fun playOrPause() {
        if (playIntent) pause() else resume()
    }

    override fun pause() {
        pauseInternal(PauseReason.USER, flushSnapshot = true, fade = true)
    }

    override fun resume() {
        resumeInternal()
    }

    override fun stop() {
        if (released) return
        playIntent = false
        queue.clearQueue(keepCurrent = false)
        goIdle()
        scope.launch { persistence.flush() }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        if (queue.state.value.currentEntry == null) return
        val duration = queue.state.value.currentEntry?.durationMs
        val target = if (duration != null) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        queue.seekTo(target)
        this.positionMs = target
        events.emit(PlaybackEvent.Position(queue.state.value.currentEntry, target, enginePlaying))
        publishState()
    }

    override fun setPlaybackSpeed(scale: Float) {
        if (released) return
        queue.setPlaybackSpeed(scale)
    }

    override fun setPlaybackPitch(pitch: Float) {
        if (released) return
        queue.setPlaybackPitch(pitch)
    }

    override fun skipNext() {
        if (startQueueCommand()) queue.skipNext(manual = true)
    }

    override fun skipPrevious() {
        if (startQueueCommand()) queue.skipPrevious()
    }

    override fun jumpToOrderIndex(orderIndex: Int) {
        if (startQueueCommand()) queue.jumpToEntry(orderIndex)
    }

    override fun setRepeatMode(mode: RepeatMode) {
        if (released) return
        queue.setRepeatMode(mode)
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        if (released) return
        queue.setShuffleEnabled(enabled)
    }

    override fun removeFromQueue(queueItemId: Long) {
        if (released) return
        queue.removeEntry(queueItemId)
    }

    override fun moveInQueue(queueItemId: Long, toEntriesIndex: Int) {
        if (released) return
        queue.moveEntry(queueItemId, toEntriesIndex)
    }

    override fun clearQueueExceptCurrent() {
        if (released) return
        queue.clearQueue(keepCurrent = true)
    }

    override fun onPlayWhenReady(playWhenReady: Boolean): Boolean {
        if (released) return true
        if (playWhenReady) resume() else pause()
        return true
    }

    override fun applyAction(action: NotificationAction) {
        if (released) return
        when (action) {
            NotificationAction.NONE -> Unit
            NotificationAction.REPEAT -> queue.cycleRepeatMode()
            NotificationAction.SHUFFLE -> queue.setShuffleEnabled(queue.state.value.shuffle == null)
            NotificationAction.STOP -> stop()
            NotificationAction.FAVORITE -> Unit
            NotificationAction.RADIO -> Unit
            NotificationAction.CHAPTER_NEXT -> Unit
            NotificationAction.CHAPTER_PREVIOUS -> Unit
            NotificationAction.SEEK_FORWARD_10 -> seekBy(10_000L)
            NotificationAction.SEEK_FORWARD_30 -> seekBy(30_000L)
            NotificationAction.SEEK_BACK_10 -> seekBy(-10_000L)
            NotificationAction.SEEK_BACK_30 -> seekBy(-30_000L)
        }
    }

    override suspend fun restoreQueue(): Boolean {
        if (released) return false
        val restored = persistence.restoreInto(queue)
        if (restored) {
            engineLoaded = false
            playIntent = false
            idle = false
            positionMs = queue.state.value.positionMs
            publishState()
        }
        return restored
    }

    override fun release() {
        if (released) return
        released = true
        stopTicker()
        queueObserver?.cancel()
        queueObserver = null
        eventObserver?.cancel()
        eventObserver = null
        persistence.stop()
        player.setListener(null)
        focus.release()
        queue.release()
        renderer.deactivate()
    }

    private fun resumeInternal() {
        if (released || playIntent) return
        val entry = queue.state.value.currentEntry ?: return
        if (!focus.requestFocus()) return
        playIntent = true
        idle = false
        if (engineLoaded) {
            player.fadeResume()
        } else {
            queue.replayCurrent()
        }
        engineLoaded = true
        positionMs = queue.state.value.positionMs
        startTicker()
        events.emit(PlaybackEvent.Resumed(entry, positionMs))
        publishState()
    }

    private fun pauseInternal(reason: PauseReason, flushSnapshot: Boolean, fade: Boolean) {
        if (released || !playIntent) return
        val entry = queue.state.value.currentEntry
        playIntent = false
        stopTicker()
        if (fade) player.fadePause() else player.pause()
        if (entry != null) events.emit(PlaybackEvent.Paused(entry, positionMs, reason))
        publishState()
        if (flushSnapshot) scope.launch { persistence.flush() }
    }

    /**
     * Skip/jump from an idle-but-restored queue must start a session first so
     * the ticker, focus and state follow the queue machine's new track.
     */
    private fun startQueueCommand(): Boolean {
        if (released) return false
        if (queue.state.value.currentEntry == null) return false
        if (!playIntent) {
            if (!focus.requestFocus()) return false
            playIntent = true
            idle = false
            engineLoaded = true
            startTicker()
        }
        return true
    }

    private fun seekBy(deltaMs: Long) {
        val current = positionReader().coerceAtLeast(0L)
        val duration = queue.state.value.currentEntry?.durationMs
        val raw = (current + deltaMs).coerceAtLeast(0L)
        seekTo(if (duration != null) min(raw, duration) else raw)
    }

    private fun goIdle() {
        if (released || idle) return
        playIntent = false
        idle = true
        engineLoaded = false
        positionMs = 0
        stopTicker()
        focus.abandonFocus()
        publishState()
    }

    private fun startTicker() {
        if (released || ticker != null || !playIntent) return
        ticker = scope.launch {
            while (isActive && playIntent) {
                delay(tickIntervalMs)
                if (!playIntent) break
                val position = positionReader().coerceAtLeast(0L)
                positionMs = position
                queue.onPositionTick(position)
                events.emit(PlaybackEvent.Position(queue.state.value.currentEntry, position, enginePlaying))
                publishState()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun publishState() {
        val state = queue.state.value
        _playerState.value = PlayerCoreState(
            current = state.currentEntry,
            isPlaying = enginePlaying,
            isIdle = idle,
            ducked = ducked,
            positionMs = positionMs,
            durationMs = state.currentEntry?.durationMs,
            speed = state.speed,
            pitch = state.pitch,
        )
    }

    companion object {
        const val DEFAULT_TICK_INTERVAL_MS = 500L
    }
}
