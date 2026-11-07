package app.omnisonic.playback.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.playback.api.equalizer.PeqConfig
import app.omnisonic.playback.api.player.AudioEngineFactory
import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.OmniPlayerListener
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueCommandPort
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.rg.NoReplayGainSource
import app.omnisonic.playback.api.rg.ReplayGainMode
import app.omnisonic.playback.api.rg.ReplayGainSource
import app.omnisonic.playback.api.rg.ReplayGainValues
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.api.stream.StreamResolver
import app.omnisonic.playback.api.stream.StreamTransport
import app.omnisonic.core.model.log.LogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.network.ClientProfile
import app.omnisonic.core.network.ConnectionSpec
import app.omnisonic.core.network.HttpClientFactory
import app.omnisonic.playback.cache.DataSourceRegistry
import app.omnisonic.playback.cache.MediaDataSourceFactoryProvider
import app.omnisonic.playback.cache.PlaybackCacheManager
import app.omnisonic.playback.engine.ExoEngineFactory
import app.omnisonic.playback.settings.PlaybackSettingsStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OmniPlayerImplTest {

    private lateinit var context: Context
    private lateinit var settings: PlaybackSettingsStore
    private lateinit var chainFactory: SinkProcessorChainFactory
    private lateinit var cacheManager: PlaybackCacheManager
    private lateinit var dataSourceRegistry: DataSourceRegistry
    private lateinit var mediaDataSources: MediaDataSourceFactoryProvider
    private lateinit var providerClients: MutableMap<Long, OkHttpClient>
    private lateinit var engineFactory: AudioEngineFactory
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var fakeClock: FakeClock
    private lateinit var settingsDir: File
    private lateinit var cacheDir: File
    private lateinit var port: TestQueuePort
    private var player: OmniPlayerImpl? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        settingsDir = java.nio.file.Files.createTempDirectory("t6-settings").toFile()
        settings = PlaybackSettingsStore.createAt(File(settingsDir, "playback.preferences_pb"))
        chainFactory = SinkProcessorChainFactory()
        cacheDir = File(context.cacheDir, "t6-${System.nanoTime()}")
        providerClients = mutableMapOf(1L to OkHttpClient())
        mediaDataSources = MediaDataSourceFactoryProvider { id -> providerClients.getValue(id) }
        dataSourceRegistry = DataSourceRegistry()
        cacheManager = PlaybackCacheManager(
            context = context,
            cacheDir = cacheDir,
            databaseProvider = StandaloneDatabaseProvider(context),
            initialMaxBytes = 32L * 1024 * 1024,
            upstreamFactory = { stream -> mediaDataSources.factoryFor(stream.entry.providerId) },
            dataSourceRegistry = dataSourceRegistry,
        )
        port = TestQueuePort()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fakeClock = FakeClock(false)
        engineFactory = ExoEngineFactory(context, fakeClock) { chainFactory.create().toArray() }
    }

    @After
    fun tearDown() {
        player?.release()
        player = null
        scope.cancel()
        runCatching { cacheManager.release() }
        runCatching { server.shutdown() }
        runCatching { settingsDir.deleteRecursively() }
        runCatching { cacheDir.deleteRecursively() }
    }

    @Test
    fun `http streams route through the cache when cache-first is set`() = runBlocking<Unit> {
        settings.setCacheFirst(true)
        val player = newPlayer()
        val stream = httpStream()

        await("cache-first default applied") { cacheManager.defaultCacheFirst }
        assertThat(player.dataSourceFactoryFor(stream).createDataSource())
            .isInstanceOf(CacheDataSource::class.java)

        serveWav()
        player.play(stream, entry())
        await("cache populated") { cacheManager.cachedBytesFor(stream.cacheKey) > 0 }
    }

    @Test
    fun `persisted cache size replaces the initial budget`() = runBlocking<Unit> {
        settings.setCacheSizeBytes(2L * 1024 * 1024)
        newPlayer()

        await("cache budget applied") { cacheManager.maxBytes == 2L * 1024 * 1024 }
    }

    @Test
    fun `content and radio streams route direct even with cache-first`() = runBlocking<Unit> {
        settings.setCacheFirst(true)
        val player = newPlayer()
        val content = ResolvedStream(entry(), "content://media/external/audio/1", requiresNetwork = false)
        val radio = ResolvedStream(entry(externalId = "radio-1"), server.url("/radio").toString(), isRadio = true)

        await("cache-first default applied") { cacheManager.defaultCacheFirst }

        assertThat(player.dataSourceFactoryFor(content).createDataSource())
            .isNotInstanceOf(CacheDataSource::class.java)
        assertThat(player.dataSourceFactoryFor(radio).createDataSource())
            .isNotInstanceOf(CacheDataSource::class.java)
    }

    @Test
    fun `provider media client custom headers reach the data source path`() = runBlocking<Unit> {
        requireHeaderServer()
        providerClients[2L] = HttpClientFactory(OmnisonicLogger(LogSink { _, _, _, _ -> })).create(
            ConnectionSpec(profile = ClientProfile.MEDIA, headers = mapOf("X-Test" to "1")),
        )
        val player = newPlayer()

        player.play(httpStream(providerId = 2L), entry(providerId = 2L))

        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }
        assertThat(server.takeRequest().getHeader("X-Test")).isEqualTo("1")
    }

    @Test
    fun `provider media client without a required header fails`() = runBlocking<Unit> {
        requireHeaderServer()
        val player = newPlayer()
        val spy = ListenerSpy()
        player.setListener(spy)

        player.play(httpStream(), entry())

        await("error surfaced") { spy.errors.isNotEmpty() }
        assertThat(server.takeRequest().getHeader("X-Test")).isNull()
    }

    @Test
    fun `per-uri override wins over the cache-first default`() = runBlocking<Unit> {
        val player = newPlayer()
        val uri = Uri.parse(server.url("/track.wav").toString())
        val stream = ResolvedStream(entry(), uri.toString())

        cacheManager.setPerUriCacheFirst(stream.cacheKey, true)
        assertThat(player.dataSourceFactoryFor(stream).createDataSource())
            .isInstanceOf(CacheDataSource::class.java)

        settings.setCacheFirst(true)
        await("cache-first default applied") { cacheManager.defaultCacheFirst }
        cacheManager.setPerUriCacheFirst(stream.cacheKey, false)
        assertThat(player.dataSourceFactoryFor(stream).createDataSource())
            .isNotInstanceOf(CacheDataSource::class.java)
    }

    @Test
    fun `playback reaches ready then ends with engine events`() {
        val player = newPlayer()
        val spy = ListenerSpy()
        player.setListener(spy)
        serveWav()
        val track = entry()

        player.play(httpStream(), track)

        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }
        assertThat(spy.ready).containsExactly(track)
        await("finished") { spy.finished.isNotEmpty() }
        assertThat(spy.finished).containsExactly(track)
        assertThat(spy.playing).contains(true)
        assertThat(spy.playing.last()).isFalse()
        assertThat(spy.errors).isEmpty()
    }

    @Test
    fun `replay gain applies at track start from source values`() = runBlocking<Unit> {
        settings.setReplayGainMode(ReplayGainMode.TRACK)
        val requested = mutableListOf<Pair<Long, String>>()
        val rgSource = object : ReplayGainSource {
            override suspend fun gainFor(providerId: Long, externalId: String): ReplayGainValues {
                requested += providerId to externalId
                return ReplayGainValues(trackGainDb = -6.0)
            }
        }
        val player = newPlayer(rgSource)
        serveWav()
        val track = entry(providerId = 7L, externalId = "song-7")

        player.play(httpStream(), track)

        val expected = Math.pow(10.0, -6.0 / 20.0).toFloat()
        await("rg factor applied") { processors.replayGain.currentLinearFactor == expected }
        assertThat(requested).containsExactly(7L to "song-7")
    }

    @Test
    fun `replay gain stays neutral when the source reports nothing`() = runBlocking<Unit> {
        settings.setReplayGainMode(ReplayGainMode.TRACK)
        val player = newPlayer(NoReplayGainSource)
        serveWav()

        player.play(httpStream(), entry())

        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }
        assertThat(processors.replayGain.isNeutral()).isTrue()
    }

    @Test
    fun `mono and peq reconfig reach the processors`() = runBlocking<Unit> {
        val player = newPlayer()

        player.setMono(true)
        assertThat(processors.mono.isEnabled).isTrue()
        player.setMono(false)
        assertThat(processors.mono.isEnabled).isFalse()

        player.setPeq(PeqConfig(preampDb = -3f))
        assertThat(processors.peq.currentConfig).isEqualTo(PeqConfig(preampDb = -3f))

        settings.setMono(true)
        settings.setPreampDb(-6f)
        await("settings-driven reconfig applied") {
            processors.mono.isEnabled && processors.peq.currentConfig == PeqConfig(preampDb = -9f)
        }
    }

    @Test
    fun `duck multiplier reaches the sink and survives a settings reconfig`() = runBlocking<Unit> {
        val player = newPlayer()

        player.setDuckLinear(0.2f)
        assertThat(processors.replayGain.currentDuckFactor).isEqualTo(0.2f)

        settings.setMono(true)
        await("config push keeps the duck factor") {
            processors.mono.isEnabled && processors.replayGain.currentDuckFactor == 0.2f
        }

        player.setDuckLinear(1f)
        assertThat(processors.replayGain.currentDuckFactor).isEqualTo(1f)
    }

    @Test
    fun `fade pause and resume arm the sink fade`() {
        val player = newPlayer()
        serveWav()
        player.play(httpStream(), entry())
        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }

        player.fadePause()
        assertThat(processors.fade.isActive()).isTrue()
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertThat(player.exposedPlayer().playWhenReady).isFalse()

        player.fadeResume()
        assertThat(processors.fade.isActive()).isTrue()
        assertThat(player.exposedPlayer().playWhenReady).isTrue()
    }

    @Test
    fun `zero fade falls back to an immediate pause`() = runBlocking<Unit> {
        settings.setShortFadeMs(0)
        settings.setMono(true)
        val player = newPlayer()
        serveWav()
        player.play(httpStream(), entry())
        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }
        await("settings applied") { processors.mono.isEnabled }

        player.fadePause()

        assertThat(processors.fade.isIdle()).isTrue()
        assertThat(player.exposedPlayer().playWhenReady).isFalse()
    }

    @Test
    fun `playback parameters apply to the engine player`() {
        val player = newPlayer()

        player.setPlaybackSpeed(1.5f)
        player.setPlaybackPitch(0.5f)

        val params = player.exposedPlayer().playbackParameters
        assertThat(params.speed).isEqualTo(1.5f)
        assertThat(params.pitch).isEqualTo(0.5f)
    }

    @Test
    fun `engine swap keeps the exposed player identity, media position and play intent`() {
        val player = newPlayer()
        repeat(3) { serveWav(durationMs = 5_000) }
        player.play(httpStream(), entry())
        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }
        val exposedPlayer = player.exposedPlayer()
        val listenerEvents = mutableListOf<Int>()
        exposedPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                listenerEvents += playbackState
            }
        })
        player.seekTo(120)

        player.applyEngineConfig(EngineConfig(minBufferMs = 45_000))

        await("swapped engine prepared") {
            player.exposedPlayer() === exposedPlayer &&
                (player.exposedPlayer().playbackState == Player.STATE_BUFFERING ||
                    player.exposedPlayer().playbackState == Player.STATE_READY) &&
                player.exposedPlayer().currentPosition >= 100L
        }
        assertThat(player.exposedPlayer().playWhenReady).isTrue()
        await("post-swap event reaches the pre-swap listener") { listenerEvents.isNotEmpty() }
    }

    @Test
    fun `engine swap preserves position speed pitch and play intent with a prepared next`() {
        val player = newPlayer()
        repeat(4) { serveWav(durationMs = 5_000) }
        player.play(httpStream(), entry(externalId = "song-1", queueItemId = 1))
        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }
        player.prepareNext(
            httpStream(path = "/track-2.wav", externalId = "song-2", queueItemId = 2),
        )
        await("prepared next published") { player.exposedPlayer().currentTimeline.windowCount == 2 }
        player.setPlaybackSpeed(1.25f)
        player.setPlaybackPitch(0.8f)
        player.seekTo(200)

        val exposed = player.exposedPlayer()
        player.applyEngineConfig(EngineConfig(minBufferMs = 45_000))

        await("swapped engine ready with the prepared next") {
            exposed.playbackState == Player.STATE_READY &&
                exposed.currentMediaItem?.mediaId == "1" &&
                exposed.currentPosition >= 180L &&
                exposed.currentTimeline.windowCount == 2
        }
        assertThat(exposed.playbackParameters.speed).isEqualTo(1.25f)
        assertThat(exposed.playbackParameters.pitch).isEqualTo(0.8f)
        assertThat(exposed.playWhenReady).isTrue()
    }

    @Test
    fun `engine swap reattaches exactly one listener without losing engine events`() {
        val player = newPlayer()
        val spy = ListenerSpy()
        player.setListener(spy)
        repeat(4) { serveWav(durationMs = 1_200) }

        player.play(httpStream(), entry())
        await("first engine ready") { spy.ready.size == 1 }

        player.applyEngineConfig(EngineConfig(minBufferMs = 45_000))

        await("new engine ready") { spy.ready.size == 2 }
        await("finished once after the swap") { spy.finished.size == 1 }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(spy.ready).hasSize(2)
        assertThat(spy.finished).hasSize(1)
        assertThat(spy.errors).isEmpty()
    }

    @Test
    fun `stale stream is re-resolved before an engine swap restart`() {
        var now = 1_000_000L
        val requested = mutableListOf<PlaybackQueueEntry>()
        val resolver = object : StreamResolver {
            override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream {
                requested += entry
                return ResolvedStream(
                    entry = entry,
                    uri = server.url("/fresh.wav").toString(),
                    expiresAtMs = now + 600_000,
                )
            }
        }
        val paths = recordWavServer(durationMs = 5_000)
        val player = newPlayer(clock = { now })
        player.setStreamResolver(resolver)
        val entry = entry()
        val initial = ResolvedStream(
            entry = entry,
            uri = server.url("/initial.wav").toString(),
            expiresAtMs = now + 600_000,
        )

        player.play(initial, entry)
        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }
        assertThat(requested).isEmpty()

        now += 1_000_000
        player.applyEngineConfig(EngineConfig(minBufferMs = 45_000))

        await("fresh stream ready") {
            requested.size == 1 && player.exposedPlayer().playbackState == Player.STATE_READY
        }
        assertThat(player.exposedPlayer().currentMediaItem?.mediaId).isEqualTo(entry.queueItemId.toString())
        assertThat(paths).contains("/initial.wav")
        assertThat(paths).contains("/fresh.wav")
    }

    @Test
    fun `expired stream recovers from one unauthorized response with a single re-resolve`() {
        val requested = mutableListOf<PlaybackQueueEntry>()
        val resolver = object : StreamResolver {
            override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream =
                ResolvedStream(entry, server.url("/ok.wav").toString()).also { requested += entry }
        }
        val paths = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return if (request.path == "/bad.wav") {
                    MockResponse().setResponseCode(403)
                } else {
                    MockResponse().setBody(okio.Buffer().write(wavBytes(300)))
                }
            }
        }
        val player = newPlayer()
        val spy = ListenerSpy()
        player.setListener(spy)
        player.setStreamResolver(resolver)
        val entry = entry()

        player.play(ResolvedStream(entry, server.url("/bad.wav").toString()), entry)

        await("recovered") { player.exposedPlayer().playbackState == Player.STATE_READY }
        assertThat(requested).hasSize(1)
        assertThat(spy.errors).isEmpty()
        assertThat(paths).contains("/ok.wav")
        assertThat(player.exposedPlayer().currentPosition).isAtMost(1_000L)
    }

    @Test
    fun `repeated unauthorized response surfaces one error without looping`() {
        val requested = mutableListOf<PlaybackQueueEntry>()
        val resolver = object : StreamResolver {
            override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream =
                ResolvedStream(entry, server.url("/bad2.wav").toString()).also { requested += entry }
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(403)
        }
        val player = newPlayer()
        val spy = ListenerSpy()
        player.setListener(spy)
        player.setStreamResolver(resolver)
        val entry = entry()

        player.play(ResolvedStream(entry, server.url("/bad.wav").toString()), entry)

        await("error surfaced") { spy.errors.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(requested).hasSize(1)
        assertThat(spy.errors).hasSize(1)
    }

    @Test
    fun `stream without an expiry is never re-resolved`() {
        val requested = mutableListOf<PlaybackQueueEntry>()
        val resolver = object : StreamResolver {
            override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream {
                requested += entry
                return ResolvedStream(entry, server.url("/fresh.wav").toString())
            }
        }
        serveWav(durationMs = 300)
        val player = newPlayer()
        player.setStreamResolver(resolver)
        val entry = entry()

        player.play(
            ResolvedStream(entry, server.url("/track.wav").toString(), expiresAtMs = null),
            entry,
        )

        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }
        assertThat(requested).isEmpty()
    }

    @Test
    fun `custom transport is served by the registered data source factory`() {
        dataSourceRegistry.register(
            "fake",
            object : DataSource.Factory {
                override fun createDataSource(): DataSource = ByteArrayDataSource(wavBytes(400))
            },
        )
        val player = newPlayer()
        val entry = entry()

        player.play(
            ResolvedStream(
                entry = entry,
                uri = "custom://track.wav",
                transport = StreamTransport.CUSTOM,
                customDataSourceFactory = "fake",
            ),
            entry,
        )

        await("custom stream ready") {
            player.exposedPlayer().playbackState == Player.STATE_READY
        }
    }

    @Test
    fun `unregistered custom data source surfaces an error`() {
        val player = newPlayer()
        val spy = ListenerSpy()
        player.setListener(spy)
        val entry = entry()

        player.play(
            ResolvedStream(
                entry = entry,
                uri = "custom://track.wav",
                transport = StreamTransport.CUSTOM,
                customDataSourceFactory = "missing",
            ),
            entry,
        )

        await("error surfaced") { spy.errors.isNotEmpty() }
        assertThat(spy.errors.single().second.message.orEmpty()).contains("missing")
    }

    @Test
    fun `stale prepared next is re-resolved at promotion`() {
        var now = 1_000_000L
        val requested = CopyOnWriteArrayList<PlaybackQueueEntry>()
        val freshNext = server.url("/next-fresh.wav").toString()
        val resolver = object : StreamResolver {
            override suspend fun resolve(entry: PlaybackQueueEntry): ResolvedStream {
                requested += entry
                return ResolvedStream(entry, freshNext, expiresAtMs = now + 600_000)
            }
        }
        val paths = recordWavServer(durationMs = 5_000)
        val player = newPlayer(clock = { now })
        val spy = ListenerSpy()
        player.setListener(spy)
        player.setStreamResolver(resolver)
        val first = entry(externalId = "song-1", queueItemId = 1)
        val second = entry(externalId = "song-2", queueItemId = 2)

        player.play(ResolvedStream(first, server.url("/current.wav").toString()), first)
        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }

        player.prepareNext(
            ResolvedStream(
                entry = second,
                uri = server.url("/next-old.wav").toString(),
                expiresAtMs = now + 120_000,
            ),
        )
        assertThat(requested).isEmpty()

        now += 300_000

        await("auto advance") { spy.autoAdvances.isNotEmpty() }
        await("fresh next resolved and used") {
            requested.size == 1 && paths.contains("/next-fresh.wav")
        }
        assertThat(requested.single().queueItemId).isEqualTo(2L)
    }

    @Test
    fun `two queued wavs advance without idle or buffering between tracks`() {
        val player = newPlayer()
        val spy = ListenerSpy()
        player.setListener(spy)
        val states = mutableListOf<Int>()
        player.exposedPlayer().addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                states += playbackState
            }
        })
        serveWav(durationMs = 5_000)
        serveWav(durationMs = 5_000)
        val first = entry(externalId = "song-1", queueItemId = 1)

        player.play(httpStream(), first)
        player.prepareNext(
            httpStream(path = "/track-2.wav", externalId = "song-2", queueItemId = 2),
        )

        await("auto advance") { spy.autoAdvances.map { it.queueItemId }.contains(2L) }
        val afterFirstReady = states.dropWhile { it != Player.STATE_READY }
        assertThat(afterFirstReady).isNotEmpty()
        assertThat(afterFirstReady)
            .containsNoneOf(Player.STATE_IDLE, Player.STATE_ENDED, Player.STATE_BUFFERING)
        assertThat(spy.finished).isEmpty()
    }

    @Test
    fun `prepare next before play joins the engine playlist at start`() {
        val player = newPlayer()
        serveWav(durationMs = 5_000)
        serveWav(durationMs = 5_000)

        player.prepareNext(httpStream(path = "/track-2.wav", externalId = "song-2", queueItemId = 2))
        player.play(httpStream(), entry(queueItemId = 1))

        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }
        assertThat(player.exposedPlayer().currentMediaItem?.mediaId).isEqualTo("1")
    }

    @Test
    fun `errors surface to the player listener`() {
        val player = newPlayer()
        val spy = ListenerSpy()
        player.setListener(spy)
        repeat(4) { server.enqueue(MockResponse().setResponseCode(404)) }
        val track = entry()

        player.play(httpStream(), track)

        await("error surfaced") { spy.errors.isNotEmpty() }
        assertThat(spy.errors.first().first).isEqualTo(track)
        assertThat(spy.errors.first().second).isInstanceOf(androidx.media3.common.PlaybackException::class.java)
    }

    @Test
    fun `stop clears the current stream`() {
        val player = newPlayer()
        serveWav(durationMs = 5_000)
        player.play(httpStream(), entry())
        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }

        player.stop()

        assertThat(player.exposedPlayer().currentMediaItem).isNull()
        assertThat(player.exposedPlayer().playbackState).isAnyOf(Player.STATE_IDLE, Player.STATE_ENDED)
    }

    @Test
    fun `release is idempotent and ignores later commands`() {
        val player = newPlayer()

        player.release()
        player.release()
        player.play(httpStream(), entry())
        player.pause()
        player.resume()
        player.seekTo(10)
        player.fadePause()
        player.applyEngineConfig(EngineConfig())

        assertThat(server.requestCount).isEqualTo(0)
    }

    private val processors: SinkProcessorChain
        get() = checkNotNull(chainFactory.active)

    private fun newPlayer(
        rgSource: ReplayGainSource = NoReplayGainSource,
        clock: () -> Long = System::currentTimeMillis,
    ): OmniPlayerImpl =
        OmniPlayerImpl(engineFactory, chainFactory, cacheManager, settings, rgSource, scope, port, clock)
            .also { player = it }

    private fun entry(providerId: Long = 1L, externalId: String = "song-1", queueItemId: Long? = null) = PlaybackQueueEntry(
        providerId = providerId,
        externalId = externalId,
        queueItemId = queueItemId ?: PlaybackQueueEntry.nextQueueItemId(),
        title = "Track",
    )

    private fun httpStream(
        path: String = "/track.wav",
        providerId: Long = 1L,
        externalId: String = "song-1",
        queueItemId: Long? = null,
    ): ResolvedStream = ResolvedStream(
        entry = entry(providerId = providerId, externalId = externalId, queueItemId = queueItemId),
        uri = server.url(path).toString(),
        requiresNetwork = true,
    )

    private fun requireHeaderServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("X-Test") == "1") {
                    MockResponse().setBody(okio.Buffer().write(wavBytes(300)))
                } else {
                    MockResponse().setResponseCode(403)
                }
        }
    }

    private fun serveWav(durationMs: Int = 300) {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(wavBytes(durationMs))))
    }

    private fun recordWavServer(durationMs: Int = 300): MutableList<String> {
        val paths = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return MockResponse().setBody(okio.Buffer().write(wavBytes(durationMs)))
            }
        }
        return paths
    }

    private fun await(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) fail("timeout: $message")
            shadowOf(Looper.getMainLooper()).idle()
            fakeClock.advanceTime(25)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(25, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(25)
        }
    }

    private fun wavBytes(durationMs: Int = 300, sampleRate: Int = 8_000): ByteArray {
        val sampleCount = durationMs * sampleRate / 1000
        val dataSize = sampleCount * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun le16(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        fun le32(value: Int) {
            le16(value and 0xFFFF)
            le16((value shr 16) and 0xFFFF)
        }
        out.write("RIFF".toByteArray())
        le32(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1)
        le16(1)
        le32(sampleRate)
        le32(sampleRate * 2)
        le16(2)
        le16(16)
        out.write("data".toByteArray())
        le32(dataSize)
        for (i in 0 until sampleCount) {
            le16((sin(2.0 * PI * 440.0 * i / sampleRate) * 8_000).toInt())
        }
        return out.toByteArray()
    }

    private class ByteArrayDataSource(private val bytes: ByteArray) : BaseDataSource(false) {
        private var position = 0
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            transferInitializing(dataSpec)
            transferStarted(dataSpec)
            return (bytes.size - position).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return androidx.media3.common.C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - position)
            System.arraycopy(bytes, position, buffer, offset, count)
            position += count
            bytesTransferred(count)
            return count
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            transferEnded()
        }
    }

    private class ListenerSpy : OmniPlayerListener {
        val ready = mutableListOf<PlaybackQueueEntry>()
        val finished = mutableListOf<PlaybackQueueEntry>()
        val autoAdvances = mutableListOf<PlaybackQueueEntry>()
        val errors = mutableListOf<Pair<PlaybackQueueEntry?, androidx.media3.common.PlaybackException>>()
        val playing = mutableListOf<Boolean>()

        override fun onEngineFinished(entry: PlaybackQueueEntry) {
            finished.add(entry)
        }

        override fun onAutoAdvance(entry: PlaybackQueueEntry) {
            autoAdvances.add(entry)
        }

        override fun onError(entry: PlaybackQueueEntry?, error: androidx.media3.common.PlaybackException) {
            errors.add(entry to error)
        }

        override fun isPlayingChanged(isPlaying: Boolean) {
            playing.add(isPlaying)
        }

        override fun onEngineReady(entry: PlaybackQueueEntry) {
            ready.add(entry)
        }
    }
}
