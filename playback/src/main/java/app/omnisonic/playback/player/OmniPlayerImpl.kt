package app.omnisonic.playback.player

import android.net.Uri
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import app.omnisonic.playback.api.equalizer.PeqConfig
import app.omnisonic.playback.api.player.AudioEngine
import app.omnisonic.playback.api.player.AudioEngineFactory
import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.OmniPlayer
import app.omnisonic.playback.api.player.OmniPlayerListener
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueCommandPort
import app.omnisonic.playback.api.rg.ReplayGainSelector
import app.omnisonic.playback.api.rg.ReplayGainSource
import app.omnisonic.playback.api.settings.PlaybackSettings
import app.omnisonic.playback.api.stream.NoStreamResolver
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.api.stream.StreamResolver
import app.omnisonic.playback.cache.PlaybackCacheManager
import app.omnisonic.playback.settings.PlaybackSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Source building and sink configuration over swappable engines behind one
 * stable exposed player. The injected engine factory must be wired to the same
 * [SinkProcessorChainFactory] so every engine receives a fresh processor chain
 * carrying the current [ProcessorConfig], and [externalScope] must dispatch
 * onto the engine's application thread — the player is only ever touched from
 * that dispatcher.
 *
 * [exposedPlayer] is an [OmniForwardingPlayer] around a stable
 * [QueueAwareBridge]; engine swaps never change the exposed identity and a new
 * engine must run on the bridge's application looper or the config is
 * rejected. The engine keeps a [current, next] playlist through [prepareNext]
 * for gapless handoff; an automatic transition onto the prepared item is
 * reported through [OmniPlayerListener.onAutoAdvance] and never replayed
 * here, while an unexpected transition surfaces through
 * [OmniPlayerListener.onError] so the queue machine resyncs.
 *
 * Replay-gain values ([ReplayGainSource]) and the RG settings are read per
 * track start inside [play]; a later [setReplayGainLinear] overrides the
 * factor until the next [play]. Radio and locally-routed streams bypass the
 * cache. [setStreamResolver] holds the upstream resolver for future
 * re-resolution glue; engine swaps replay the remembered streams.
 */
class OmniPlayerImpl(
    private val engineFactory: AudioEngineFactory,
    private val chainFactory: SinkProcessorChainFactory,
    private val cacheManager: PlaybackCacheManager,
    private val settings: PlaybackSettings,
    private val replayGainSource: ReplayGainSource,
    private val externalScope: CoroutineScope,
    private val queuePort: QueueCommandPort,
) : OmniPlayer, QueueAwareBridge.Actions {

    private var engine: AudioEngine
    private var engineConfig = EngineConfig()
    private lateinit var processors: SinkProcessorChain
    private val bridge: QueueAwareBridge
    private val exposed: OmniForwardingPlayer
    private val mainHandler: Handler
    private val fadePauseAction: Runnable
    private val settingsJobs = mutableListOf<Job>()
    private var startJob: Job? = null
    private var listener: OmniPlayerListener? = null
    private var currentEntry: PlaybackQueueEntry? = null
    private var currentStream: ResolvedStream? = null
    private var preparedEntry: PlaybackQueueEntry? = null
    private var preparedStream: ResolvedStream? = null
    private var resolver: StreamResolver = NoStreamResolver
    private var speed = 1f
    private var pitch = 1f
    private var shortFadeMs = PlaybackSettingsStore.DEFAULT_SHORT_FADE_MS
    private var userPreampDb = 0f
    private var peqConfig: PeqConfig? = null
    private var replayGainLinear = 1f
    private var monoEnabled = false
    private var released = false

    private val engineListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val entry = currentEntry ?: return
            when (playbackState) {
                Player.STATE_READY -> listener?.onEngineReady(entry)
                Player.STATE_ENDED -> listener?.onEngineFinished(entry)
            }
            bridge.republish()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                adoptAutoAdvance(mediaItem)
            }
            bridge.republish()
        }

        override fun onPlayerError(error: PlaybackException) {
            bridge.publishError(error)
            listener?.onError(currentEntry, error)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener?.isPlayingChanged(isPlaying)
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            bridge.republish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            bridge.republish()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            bridge.republish()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            bridge.republish()
        }

        override fun onPositionDiscontinuity(reason: Int) {
            bridge.republish()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            bridge.republish()
        }
    }

    init {
        engine = engineFactory.create(EngineConfig())
        processors = checkNotNull(chainFactory.active) {
            "engine factory must be wired to the same sink processor chain factory"
        }
        bridge = QueueAwareBridge(engine.player.applicationLooper, queuePort.queueState, this)
        exposed = OmniForwardingPlayer(queuePort.queueState, queuePort, bridge) { release() }
        mainHandler = Handler(engine.player.applicationLooper)
        fadePauseAction = Runnable { pause() }
        engine.player.addListener(engineListener)
        observeSettings()
        observeQueue()
    }

    override fun exposedPlayer(): Player = exposed

    override fun play(stream: ResolvedStream, entry: PlaybackQueueEntry) {
        if (released) return
        val hadCurrent = currentEntry != null
        currentEntry = entry
        currentStream = stream
        if (hadCurrent) clearPrepared()
        startJob?.cancel()
        mainHandler.removeCallbacks(fadePauseAction)
        bridge.clearPublishedError()
        startJob = externalScope.launch {
            applyReplayGainFor(entry)
            startCurrentStream(stream.startPositionMs, playWhenReady = true)
        }
    }

    override fun prepareNext(stream: ResolvedStream?) {
        if (released) return
        if (stream == null) {
            clearPrepared()
            trimEngineTail()
            bridge.republish()
            return
        }
        if (preparedEntry?.queueItemId == stream.entry.queueItemId) return
        preparedEntry = stream.entry
        preparedStream = stream
        if (currentEntry != null) attachPreparedToEngine()
        bridge.republish()
    }

    override fun pause() {
        if (released) return
        engine.player.pause()
    }

    override fun resume() {
        if (released) return
        engine.player.play()
    }

    override fun stop() {
        if (released) return
        startJob?.cancel()
        startJob = null
        mainHandler.removeCallbacks(fadePauseAction)
        engine.player.pause()
        engine.player.clearMediaItems()
        currentEntry = null
        currentStream = null
        clearPrepared()
        bridge.republish()
    }

    override fun seekTo(positionMs: Long) {
        if (released || currentStream == null) return
        engine.player.seekTo(positionMs)
    }

    override fun seekToStart() = seekTo(0L)

    override fun setPlaybackSpeed(scale: Float) {
        if (released) return
        speed = scale
        engine.player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    override fun setPlaybackPitch(pitch: Float) {
        if (released) return
        this.pitch = pitch
        engine.player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    override fun setReplayGainLinear(multiplier: Float) {
        replayGainLinear = multiplier
        pushConfig()
    }

    override fun setMono(enabled: Boolean) {
        monoEnabled = enabled
        pushConfig()
    }

    override fun setPeq(config: PeqConfig?) {
        peqConfig = config
        pushConfig()
    }

    override fun fadePause() {
        if (released) return
        if (shortFadeMs <= 0L || !engine.player.isPlaying) {
            pause()
            return
        }
        processors.fade.beginFadeOut(shortFadeMs)
        mainHandler.postDelayed(fadePauseAction, shortFadeMs)
    }

    override fun fadeResume() {
        if (released) return
        mainHandler.removeCallbacks(fadePauseAction)
        if (shortFadeMs <= 0L) {
            resume()
            return
        }
        processors.fade.beginFadeIn(shortFadeMs)
        resume()
    }

    override fun applyEngineConfig(config: EngineConfig) {
        if (released || config == engineConfig) return
        startJob?.cancel()
        startJob = null
        mainHandler.removeCallbacks(fadePauseAction)

        val oldEngine = engine
        val oldPlayer = oldEngine.player
        val restorePosition =
            if (currentStream != null && oldPlayer.playbackState != Player.STATE_IDLE) oldPlayer.currentPosition else 0L
        val wasPlaying = oldPlayer.playWhenReady
        oldPlayer.removeListener(engineListener)

        engineConfig = config
        val newEngine = engineFactory.create(config)
        if (newEngine.player.applicationLooper != bridge.applicationLooper) {
            newEngine.release()
            throw IllegalStateException(
                "engine application looper ${newEngine.player.applicationLooper} " +
                    "does not match the exposed player looper ${bridge.applicationLooper}",
            )
        }
        engine = newEngine
        processors = checkNotNull(chainFactory.active) {
            "engine factory must be wired to the same sink processor chain factory"
        }
        newEngine.player.addListener(engineListener)
        newEngine.player.playbackParameters = PlaybackParameters(speed, pitch)
        if (currentStream != null) startCurrentStream(restorePosition, playWhenReady = wasPlaying)
        oldEngine.release()
        bridge.republish()
    }

    override fun setStreamResolver(resolver: StreamResolver) {
        this.resolver = resolver
    }

    override fun setListener(listener: OmniPlayerListener?) {
        this.listener = listener
    }

    override fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        startJob?.cancel()
        startJob = null
        settingsJobs.forEach(Job::cancel)
        settingsJobs.clear()
        listener = null
        engine.player.removeListener(engineListener)
        bridge.markReleased()
        engine.release()
    }

    override fun seekInCurrentRequested(positionMs: Long) {
        if (released) return
        if (positionMs == C.TIME_UNSET) {
            engine.player.seekToDefaultPosition()
        } else {
            engine.player.seekTo(positionMs)
        }
    }

    override fun queueJumpRequested(orderIndex: Int) {
        if (released) return
        queuePort.jumpToOrderIndex(orderIndex)
    }

    override fun repeatModeRequested(mode: Int) {
        if (released) return
        queuePort.setRepeatMode(QueueCommandPolicy.fromPlayerRepeatMode(mode))
    }

    override fun shuffleRequested(enabled: Boolean) {
        if (released) return
        queuePort.setShuffleEnabled(enabled)
    }

    override fun playWhenReadyRequested(playWhenReady: Boolean) {
        if (released) return
        if (playWhenReady) engine.player.play() else engine.player.pause()
    }

    override fun prepareRequested() {
        if (released) return
        engine.player.prepare()
    }

    override fun stopRequested() {
        stop()
    }

    override fun releaseRequested() {
        release()
    }

    override fun playbackParametersRequested(parameters: PlaybackParameters) {
        if (released) return
        speed = parameters.speed
        pitch = parameters.pitch
        engine.player.playbackParameters = parameters
    }

    override fun volumeRequested(volume: Float) {
        if (released) return
        engine.player.volume = volume
    }

    override fun trackSelectionParametersRequested(parameters: TrackSelectionParameters) {
        if (released) return
        engine.player.trackSelectionParameters = parameters
    }

    override fun activeEngine(): Player? = if (released) null else engine.player

    override fun currentEntries(): Pair<PlaybackQueueEntry?, PlaybackQueueEntry?> =
        currentEntry to preparedEntry

    internal fun dataSourceFactoryFor(stream: ResolvedStream): DataSource.Factory =
        if (stream.routedDirect || stream.isRadio) cacheManager.directFor(stream)
        else cacheManager.factoryFor(stream)

    private fun observeQueue() {
        settingsJobs += externalScope.launch {
            queuePort.queueState.collect { bridge.republish() }
        }
    }

    private fun observeSettings() {
        settingsJobs += externalScope.launch {
            combine(
                settings.cacheFirst,
                settings.mono,
                settings.preampDb,
                settings.shortFadeMs,
            ) { cacheFirst, mono, preampDb, fadeMs ->
                SettingsSnapshot(cacheFirst, mono, preampDb, fadeMs)
            }.collect { snapshot ->
                cacheManager.defaultCacheFirst = snapshot.cacheFirst
                monoEnabled = snapshot.mono
                userPreampDb = snapshot.preampDb
                shortFadeMs = snapshot.shortFadeMs
                pushConfig()
            }
        }
    }

    private suspend fun applyReplayGainFor(entry: PlaybackQueueEntry) {
        val values = runCatching { replayGainSource.gainFor(entry.providerId, entry.externalId) }.getOrNull()
        val gainDb = ReplayGainSelector.gainDb(
            values = values,
            mode = settings.replayGainMode.first(),
            normalisation = settings.replayGainNormalisation.first(),
            targetLoudnessLufs = settings.replayGainTargetLoudnessLufs.first(),
        )
        replayGainLinear = ReplayGainSelector.linearMultiplier(gainDb)
        pushConfig()
    }

    private fun adoptAutoAdvance(mediaItem: MediaItem?) {
        val nextEntry = preparedEntry
        val nextStream = preparedStream
        if (nextEntry != null && nextStream != null &&
            mediaItem?.mediaId == nextEntry.queueItemId.toString()
        ) {
            currentEntry = nextEntry
            currentStream = nextStream
            clearPrepared()
            listener?.onAutoAdvance(nextEntry)
            return
        }
        val entry = currentEntry ?: return
        listener?.onError(
            entry,
            PlaybackException(
                "engine advanced to an unexpected media item: ${mediaItem?.mediaId}",
                null,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
            ),
        )
    }

    private fun startCurrentStream(fromPositionMs: Long, playWhenReady: Boolean) {
        val stream = currentStream ?: return
        processors.fade.flush()
        val player = engine.player as ExoPlayer
        val sources = buildList {
            add(mediaSourceFor(stream))
            preparedStream?.let { add(mediaSourceFor(it)) }
        }
        player.setMediaSources(sources, 0, fromPositionMs)
        player.prepare()
        if (playWhenReady) player.play()
    }

    private fun attachPreparedToEngine() {
        val stream = preparedStream ?: return
        val player = engine.player as ExoPlayer
        if (player.currentMediaItem == null) return
        player.addMediaSource(mediaSourceFor(stream))
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex > 0) player.removeMediaItems(0, currentIndex)
        if (player.mediaItemCount > 2) player.removeMediaItems(1, player.mediaItemCount - 1)
    }

    private fun trimEngineTail() {
        val player = engine.player as ExoPlayer
        if (player.mediaItemCount > 1) player.removeMediaItems(1, player.mediaItemCount)
    }

    private fun clearPrepared() {
        preparedEntry = null
        preparedStream = null
    }

    internal fun mediaSourceFor(stream: ResolvedStream): MediaSource =
        DefaultMediaSourceFactory(dataSourceFactoryFor(stream))
            .createMediaSource(
                MediaItem.Builder()
                    .setMediaId(stream.entry.queueItemId.toString())
                    .setUri(Uri.parse(stream.uri))
                    .setCustomCacheKey(stream.cacheKey)
                    .build(),
            )

    private fun pushConfig() {
        val configured = peqConfig
        val effective = configured?.copy(preampDb = configured.preampDb + userPreampDb)
            ?: PeqConfig(preampDb = userPreampDb)
        chainFactory.updateConfig(
            ProcessorConfig(
                replayGainLinear = replayGainLinear,
                monoEnabled = monoEnabled,
                peq = effective,
            ),
        )
    }

    private data class SettingsSnapshot(
        val cacheFirst: Boolean,
        val mono: Boolean,
        val preampDb: Float,
        val shortFadeMs: Long,
    )
}
