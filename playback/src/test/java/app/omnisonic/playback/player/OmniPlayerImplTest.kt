package app.omnisonic.playback.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.playback.api.equalizer.PeqConfig
import app.omnisonic.playback.api.player.AudioEngineFactory
import app.omnisonic.playback.api.player.DecoderPreference
import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.OmniPlayerListener
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.rg.NoReplayGainSource
import app.omnisonic.playback.api.rg.ReplayGainMode
import app.omnisonic.playback.api.rg.ReplayGainSource
import app.omnisonic.playback.api.rg.ReplayGainValues
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.cache.PlaybackCacheManager
import app.omnisonic.playback.engine.ExoEngineFactory
import app.omnisonic.playback.settings.PlaybackSettingsStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OmniPlayerImplTest {

    private lateinit var context: Context
    private lateinit var settings: PlaybackSettingsStore
    private lateinit var processors: SinkProcessorChain
    private lateinit var cacheManager: PlaybackCacheManager
    private lateinit var engineFactory: AudioEngineFactory
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var fakeClock: FakeClock
    private lateinit var settingsDir: File
    private lateinit var cacheDir: File
    private var player: OmniPlayerImpl? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        settingsDir = java.nio.file.Files.createTempDirectory("t6-settings").toFile()
        settings = PlaybackSettingsStore.createAt(File(settingsDir, "playback.preferences_pb"))
        processors = SinkProcessorChain()
        cacheDir = File(context.cacheDir, "t6-${System.nanoTime()}")
        cacheManager = PlaybackCacheManager(
            context = context,
            cacheDir = cacheDir,
            databaseProvider = StandaloneDatabaseProvider(context),
            initialMaxBytes = 32L * 1024 * 1024,
            upstreamFactory = DefaultHttpDataSource.Factory(),
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fakeClock = FakeClock(false)
        engineFactory = ExoEngineFactory(context, fakeClock) { processors.toArray() }
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

        assertThat(processors.fade.isActive()).isFalse()
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
    fun `engine swap keeps media position and play intent`() {
        val player = newPlayer()
        repeat(3) { serveWav(durationMs = 5_000) }
        player.play(httpStream(), entry())
        await("ready") { player.exposedPlayer().playbackState == Player.STATE_READY }
        val firstPlayer = player.exposedPlayer()
        player.seekTo(120)

        player.applyEngineConfig(EngineConfig(decoderPreference = DecoderPreference.EXTENSION_FIRST))

        await("swapped engine prepared") {
            player.exposedPlayer() !== firstPlayer &&
                (player.exposedPlayer().playbackState == Player.STATE_BUFFERING ||
                    player.exposedPlayer().playbackState == Player.STATE_READY) &&
                player.exposedPlayer().currentPosition >= 100L
        }
        assertThat(player.exposedPlayer().playWhenReady).isTrue()
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

    private fun newPlayer(rgSource: ReplayGainSource = NoReplayGainSource): OmniPlayerImpl =
        OmniPlayerImpl(engineFactory, processors, cacheManager, settings, rgSource, scope)
            .also { player = it }

    private fun entry(providerId: Long = 1L, externalId: String = "song-1") = PlaybackQueueEntry(
        providerId = providerId,
        externalId = externalId,
        title = "Track",
    )

    private fun httpStream(path: String = "/track.wav"): ResolvedStream = ResolvedStream(
        entry = entry(),
        uri = server.url(path).toString(),
        requiresNetwork = true,
    )

    private fun serveWav(durationMs: Int = 300) {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(wavBytes(durationMs))))
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

    private class ListenerSpy : OmniPlayerListener {
        val ready = mutableListOf<PlaybackQueueEntry>()
        val finished = mutableListOf<PlaybackQueueEntry>()
        val errors = mutableListOf<Pair<PlaybackQueueEntry?, androidx.media3.common.PlaybackException>>()
        val playing = mutableListOf<Boolean>()

        override fun onEngineFinished(entry: PlaybackQueueEntry) {
            finished.add(entry)
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
