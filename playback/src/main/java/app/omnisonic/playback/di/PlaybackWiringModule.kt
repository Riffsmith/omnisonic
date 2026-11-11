package app.omnisonic.playback.di

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import app.omnisonic.core.data.providers.ProviderClientRegistry
import app.omnisonic.core.data.repository.SongRepository
import app.omnisonic.playback.api.controller.PlaybackController
import app.omnisonic.playback.api.equalizer.PeqConfig
import app.omnisonic.playback.api.graph.BrowseTreeProvider
import app.omnisonic.playback.api.graph.EmptyBrowseTreeProvider
import app.omnisonic.playback.api.player.AudioEngineFactory
import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.OmniPlayer
import app.omnisonic.playback.api.player.OmniPlayerListener
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueCommandPort
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.rg.ReplayGainSource
import app.omnisonic.playback.api.settings.PlaybackSettings
import app.omnisonic.playback.api.source.QueueSources
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.api.stream.StreamResolver
import app.omnisonic.playback.cache.MediaDataSourceFactoryProvider
import app.omnisonic.playback.cache.PlaybackCacheManager
import app.omnisonic.playback.controller.PlaybackControllerImpl
import app.omnisonic.playback.engine.ExoEngineFactory
import app.omnisonic.playback.events.PlaybackEventBusImpl
import app.omnisonic.playback.focus.AudioFocusHelper
import app.omnisonic.playback.focus.FocusOwnerFactory
import app.omnisonic.playback.player.OmniPlayerImpl
import app.omnisonic.playback.player.SinkProcessorChainFactory
import app.omnisonic.playback.queue.QueuePersistence
import app.omnisonic.playback.queue.QueuePersistenceCoordinator
import app.omnisonic.playback.queue.QueuePlayerImpl
import app.omnisonic.playback.renderer.LocalPlaybackRenderer
import app.omnisonic.playback.settings.PlaybackSettingsStore
import app.omnisonic.playback.source.AutoplayQueueSource
import app.omnisonic.playback.source.RadioQueueSource
import app.omnisonic.playback.source.SearchPlayQueueSource
import app.omnisonic.playback.stream.MediaProviderLookup
import app.omnisonic.playback.stream.ProviderStreamResolver
import app.omnisonic.playback.stream.SongReplayGainSource
import app.omnisonic.playback.stream.SongRowLookup
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** Scope the engine-facing components (player, queue machine, controller) run on. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackEngineScope

/** The exposed player facade the media session binds to. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ExposedPlayer

/** One assembled stack: the controller and the session-facing player it owns. */
class PlaybackStack(
    val controller: PlaybackController,
    val sessionPlayer: Player,
)

/**
 * Production assembly of the playback stack. The three core classes reference
 * each other (player ↔ queue port ↔ controller), so the graph is built here in
 * dependency order through two bind-once indirections; construction is
 * single-threaded and both indirections are bound before anything can call in.
 * [PlaybackEngineScope] is the dedicated engine scope, dispatched on the
 * engine's application (main) thread; the queue machine's [QueuePlayer.release]
 * cancels it.
 */
@Module
@InstallIn(SingletonComponent::class)
@UnstableApi
object PlaybackWiringModule {

    @Provides
    @Singleton
    @PlaybackEngineScope
    fun engineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext context: Context): PlaybackSettingsStore =
        PlaybackSettingsStore.create(context)

    @Provides
    @Singleton
    fun playbackSettings(store: PlaybackSettingsStore): PlaybackSettings = store

    @Provides
    @Singleton
    fun mediaDataSourceFactoryProvider(clients: ProviderClientRegistry): MediaDataSourceFactoryProvider =
        MediaDataSourceFactoryProvider { providerId ->
            runBlocking { clients.httpClientFor(providerId) } ?: OkHttpClient()
        }

    @Provides
    @Singleton
    fun cacheManager(
        @ApplicationContext context: Context,
        dataSources: MediaDataSourceFactoryProvider,
    ): PlaybackCacheManager = PlaybackCacheManager(
        context = context,
        cacheDir = File(context.cacheDir, CACHE_DIR_NAME),
        databaseProvider = StandaloneDatabaseProvider(context),
        upstreamFactory = { stream -> dataSources.factoryFor(stream.entry.providerId) },
    )

    @Provides
    @Singleton
    fun chainFactory(): SinkProcessorChainFactory = SinkProcessorChainFactory()

    @Provides
    @Singleton
    fun engineFactory(
        @ApplicationContext context: Context,
        chainFactory: SinkProcessorChainFactory,
    ): AudioEngineFactory = ExoEngineFactory(context) { chainFactory.create().toArray() }

    @Provides
    @Singleton
    fun mediaProviderLookup(clients: ProviderClientRegistry): MediaProviderLookup =
        MediaProviderLookup { providerId -> clients.providerFor(providerId) }

    @Provides
    @Singleton
    fun songRowLookup(songs: SongRepository): SongRowLookup =
        SongRowLookup { providerId, externalId ->
            songs.selectByProviderAndExternal(providerId, externalId)
        }

    @Provides
    @Singleton
    fun replayGainSource(source: SongReplayGainSource): ReplayGainSource = source

    @Provides
    @Singleton
    fun streamResolver(resolver: ProviderStreamResolver): StreamResolver = resolver

    @Provides
    @Singleton
    fun queueSources(): QueueSources =
        QueueSources(listOf(RadioQueueSource, AutoplayQueueSource, SearchPlayQueueSource))

    @Provides
    @Singleton
    fun queuePersistenceCoordinator(
        persistence: QueuePersistence,
        @PlaybackEngineScope scope: CoroutineScope,
    ): QueuePersistenceCoordinator = QueuePersistenceCoordinator(persistence, scope)

    @Provides
    @Singleton
    fun playbackStack(
        @ApplicationContext context: Context,
        @PlaybackEngineScope scope: CoroutineScope,
        settings: PlaybackSettingsStore,
        chainFactory: SinkProcessorChainFactory,
        cacheManager: PlaybackCacheManager,
        engineFactory: AudioEngineFactory,
        replayGainSource: ReplayGainSource,
        streamResolver: StreamResolver,
        coordinator: QueuePersistenceCoordinator,
        queueSources: QueueSources,
    ): PlaybackStack {
        val bus = PlaybackEventBusImpl()
        val playerProxy = LateBoundOmniPlayer()
        val queue = QueuePlayerImpl(scope, playerProxy, streamResolver, bus)
        val port = LateBoundQueueCommandPort(queue.state)
        val renderer = LocalPlaybackRenderer {
            OmniPlayerImpl(engineFactory, chainFactory, cacheManager, settings, replayGainSource, scope, port)
        }
        val player = renderer.activateNow()
        val focusFactory = FocusOwnerFactory { callbacks ->
            AudioFocusHelper(context, callbacks, settings.transientLossAction, scope)
        }
        val controller = PlaybackControllerImpl(scope, renderer, player, queue, bus, coordinator, focusFactory, queueSources)
        playerProxy.bind(player)
        port.bind(controller)
        return PlaybackStack(controller, player.exposedPlayer())
    }

    @Provides
    @Singleton
    fun playbackController(stack: PlaybackStack): PlaybackController = stack.controller

    @Provides
    @Singleton
    @ExposedPlayer
    fun exposedPlayer(stack: PlaybackStack): Player = stack.sessionPlayer

    @Provides
    @Singleton
    fun browseTree(): BrowseTreeProvider = EmptyBrowseTreeProvider

    private const val CACHE_DIR_NAME = "stream_cache"
}

/**
 * Construction-cycle breaker: forwards every command to the real player bound
 * immediately after assembly. Never receives calls while unbound because only
 * the assembly thread holds it.
 */
private class LateBoundOmniPlayer : OmniPlayer {

    @Volatile
    private var delegate: OmniPlayer? = null

    fun bind(delegate: OmniPlayer) {
        this.delegate = delegate
    }

    private val target: OmniPlayer get() = requireNotNull(delegate) { "player not bound" }

    override fun exposedPlayer(): Player = target.exposedPlayer()
    override fun play(stream: ResolvedStream, entry: PlaybackQueueEntry) = target.play(stream, entry)
    override fun prepareNext(stream: ResolvedStream?) = target.prepareNext(stream)
    override fun pause() = target.pause()
    override fun resume() = target.resume()
    override fun stop() = target.stop()
    override fun seekTo(positionMs: Long) = target.seekTo(positionMs)
    override fun seekToStart() = target.seekToStart()
    override fun setPlaybackSpeed(scale: Float) = target.setPlaybackSpeed(scale)
    override fun setPlaybackPitch(pitch: Float) = target.setPlaybackPitch(pitch)
    override fun setReplayGainLinear(multiplier: Float) = target.setReplayGainLinear(multiplier)
    override fun setDuckLinear(multiplier: Float) = target.setDuckLinear(multiplier)
    override fun setMono(enabled: Boolean) = target.setMono(enabled)
    override fun setPeq(config: PeqConfig?) = target.setPeq(config)
    override fun fadePause() = target.fadePause()
    override fun fadeResume() = target.fadeResume()
    override fun applyEngineConfig(config: EngineConfig) = target.applyEngineConfig(config)
    override fun setStreamResolver(resolver: StreamResolver) = target.setStreamResolver(resolver)
    override fun setListener(listener: OmniPlayerListener?) = target.setListener(listener)
    override fun release() = target.release()
}

/** Cycle breaker on the queue-command side; see [LateBoundOmniPlayer]. */
private class LateBoundQueueCommandPort(
    override val queueState: StateFlow<QueueState>,
) : QueueCommandPort {

    @Volatile
    private var delegate: QueueCommandPort? = null

    fun bind(delegate: QueueCommandPort) {
        this.delegate = delegate
    }

    override fun skipNext() = requireNotNull(delegate).skipNext()
    override fun skipPrevious() = requireNotNull(delegate).skipPrevious()
    override fun jumpToOrderIndex(orderIndex: Int) = requireNotNull(delegate).jumpToOrderIndex(orderIndex)
    override fun setRepeatMode(mode: RepeatMode) = requireNotNull(delegate).setRepeatMode(mode)
    override fun setShuffleEnabled(enabled: Boolean) = requireNotNull(delegate).setShuffleEnabled(enabled)
    override fun removeFromQueue(queueItemId: Long) = requireNotNull(delegate).removeFromQueue(queueItemId)
    override fun moveInQueue(queueItemId: Long, toEntriesIndex: Int) =
        requireNotNull(delegate).moveInQueue(queueItemId, toEntriesIndex)
    override fun clearQueueExceptCurrent() = requireNotNull(delegate).clearQueueExceptCurrent()
    override fun onPlayWhenReady(playWhenReady: Boolean): Boolean =
        delegate?.onPlayWhenReady(playWhenReady) ?: false
}
