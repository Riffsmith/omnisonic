package app.omnisonic.playback.player

import android.net.Uri
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
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
 * Source building and sink configuration over one wrapped engine. The injected
 * factory must be built from the same [processors] chain so sink configuration
 * survives engine swaps, and [externalScope] must dispatch onto the engine's
 * application thread — the player is only ever touched from that dispatcher.
 *
 * Replay-gain values ([ReplayGainSource]) and the RG settings are read per
 * track start inside [play]; a later [setReplayGainLinear] overrides the
 * factor until the next [play]. Radio and locally-routed streams bypass the
 * cache. [setStreamResolver] holds the upstream resolver for future
 * re-resolution glue; engine swaps currently replay the remembered stream.
 */
class OmniPlayerImpl(
    private val engineFactory: AudioEngineFactory,
    private val processors: SinkProcessorChain,
    private val cacheManager: PlaybackCacheManager,
    private val settings: PlaybackSettings,
    private val replayGainSource: ReplayGainSource,
    private val externalScope: CoroutineScope,
) : OmniPlayer {

    private var engine: AudioEngine = engineFactory.create(EngineConfig())
    private var engineConfig = EngineConfig()
    private val mainHandler = Handler(engine.player.applicationLooper)
    private val fadePauseAction = Runnable { pause() }
    private val settingsJobs = mutableListOf<Job>()
    private var startJob: Job? = null
    private var listener: OmniPlayerListener? = null
    private var currentEntry: PlaybackQueueEntry? = null
    private var currentStream: ResolvedStream? = null
    private var resolver: StreamResolver = NoStreamResolver
    private var speed = 1f
    private var pitch = 1f
    private var shortFadeMs = PlaybackSettingsStore.DEFAULT_SHORT_FADE_MS
    private var userPreampDb = 0f
    private var peqConfig: PeqConfig? = null
    private var released = false

    private val engineListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val entry = currentEntry ?: return
            when (playbackState) {
                Player.STATE_READY -> listener?.onEngineReady(entry)
                Player.STATE_ENDED -> listener?.onEngineFinished(entry)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onError(currentEntry, error)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener?.isPlayingChanged(isPlaying)
        }
    }

    init {
        engine.player.addListener(engineListener)
        observeSettings()
    }

    override fun exposedPlayer(): Player = engine.player

    override fun play(stream: ResolvedStream, entry: PlaybackQueueEntry) {
        if (released) return
        currentEntry = entry
        currentStream = stream
        startJob?.cancel()
        mainHandler.removeCallbacks(fadePauseAction)
        startJob = externalScope.launch {
            applyReplayGainFor(entry)
            startCurrentStream(stream.startPositionMs, playWhenReady = true)
        }
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
        processors.replayGain.setLinearFactor(multiplier)
    }

    override fun setMono(enabled: Boolean) {
        processors.mono.setEnabled(enabled)
    }

    override fun setPeq(config: PeqConfig?) {
        peqConfig = config
        applyPeq()
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

        val oldPlayer = engine.player
        val restorePosition =
            if (currentStream != null && oldPlayer.playbackState != Player.STATE_IDLE) oldPlayer.currentPosition else 0L
        val wasPlaying = oldPlayer.playWhenReady
        oldPlayer.removeListener(engineListener)
        engine.release()

        engineConfig = config
        engine = engineFactory.create(config)
        engine.player.addListener(engineListener)
        engine.player.playbackParameters = PlaybackParameters(speed, pitch)

        if (currentStream != null) startCurrentStream(restorePosition, playWhenReady = wasPlaying)
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
        engine.release()
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
                processors.mono.setEnabled(snapshot.mono)
                userPreampDb = snapshot.preampDb
                shortFadeMs = snapshot.shortFadeMs
                applyPeq()
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
        processors.replayGain.setLinearFactor(ReplayGainSelector.linearMultiplier(gainDb))
    }

    private fun startCurrentStream(fromPositionMs: Long, playWhenReady: Boolean) {
        val stream = currentStream ?: return
        processors.fade.flush()
        val player = engine.player as ExoPlayer
        player.setMediaSource(mediaSourceFor(stream), fromPositionMs)
        player.prepare()
        if (playWhenReady) player.play()
    }

    internal fun dataSourceFactoryFor(stream: ResolvedStream): DataSource.Factory =
        if (stream.routedDirect || stream.isRadio) cacheManager.directFor(stream)
        else cacheManager.factoryFor(stream)

    private fun mediaSourceFor(stream: ResolvedStream): MediaSource =
        DefaultMediaSourceFactory(dataSourceFactoryFor(stream))
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(Uri.parse(stream.uri))
                    .setCustomCacheKey(stream.cacheKey)
                    .build(),
            )

    private fun applyPeq() {
        val configured = peqConfig
        val effective = configured?.copy(preampDb = configured.preampDb + userPreampDb)
            ?: PeqConfig(preampDb = userPreampDb)
        processors.peq.setPeqConfig(effective)
    }

    private data class SettingsSnapshot(
        val cacheFirst: Boolean,
        val mono: Boolean,
        val preampDb: Float,
        val shortFadeMs: Long,
    )
}
