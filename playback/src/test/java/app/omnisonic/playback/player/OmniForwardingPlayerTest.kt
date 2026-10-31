package app.omnisonic.playback.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.playback.api.player.AudioEngineFactory
import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.OmniPlayerListener
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.rg.NoReplayGainSource
import app.omnisonic.playback.api.stream.NoStreamResolver
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.cache.MediaDataSourceFactoryProvider
import app.omnisonic.playback.cache.PlaybackCacheManager
import app.omnisonic.playback.engine.ExoEngineFactory
import app.omnisonic.playback.settings.PlaybackSettingsStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

private fun queueEntry(id: Long, title: String = "Track $id") = PlaybackQueueEntry(
    providerId = 1L,
    externalId = "ext-$id",
    queueItemId = id,
    title = title,
    artistName = "Artist $id",
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OmniForwardingPlayerTest {

    private lateinit var context: Context
    private lateinit var settings: PlaybackSettingsStore
    private lateinit var chainFactory: SinkProcessorChainFactory
    private lateinit var cacheManager: PlaybackCacheManager
    private lateinit var engineFactory: AudioEngineFactory
    private lateinit var scope: CoroutineScope
    private lateinit var fakeClock: FakeClock
    private lateinit var settingsDir: File
    private lateinit var cacheDir: File
    private lateinit var port: TestQueuePort
    private var player: OmniPlayerImpl? = null
    private var session: MediaSession? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsDir = java.nio.file.Files.createTempDirectory("t13-settings").toFile()
        settings = PlaybackSettingsStore.createAt(File(settingsDir, "playback.preferences_pb"))
        chainFactory = SinkProcessorChainFactory()
        cacheDir = File(context.cacheDir, "t13-${System.nanoTime()}")
        cacheManager = PlaybackCacheManager(
            context = context,
            cacheDir = cacheDir,
            databaseProvider = StandaloneDatabaseProvider(context),
            initialMaxBytes = 32L * 1024 * 1024,
            upstreamFactory = { throw IllegalStateException("no upstream in this suite") },
        )
        port = TestQueuePort()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fakeClock = FakeClock(false)
        engineFactory = ExoEngineFactory(context, fakeClock) { chainFactory.create().toArray() }
    }

    @After
    fun tearDown() {
        session?.release()
        session = null
        player?.release()
        player = null
        scope.cancel()
        runCatching { cacheManager.release() }
        runCatching { settingsDir.deleteRecursively() }
        runCatching { cacheDir.deleteRecursively() }
    }

    private val processors: SinkProcessorChain
        get() = checkNotNull(chainFactory.active)

    private fun newPlayer(): OmniPlayerImpl =
        OmniPlayerImpl(engineFactory, chainFactory, cacheManager, settings, NoReplayGainSource, scope, port)
            .also { player = it }

    private fun newSession(): MediaSession =
        MediaSession.Builder(context, checkNotNull(player).exposedPlayer())
            .build()
            .also { session = it }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        fakeClock.advanceTime(50)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(50, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun await(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) fail("timeout: $message")
            idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `session player identity survives an engine swap and stays controllable`() {
        val p = newPlayer()
        val session = newSession()
        val listenerEvents = mutableListOf<Boolean>()
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                listenerEvents += playWhenReady
            }
        }
        val before = p.exposedPlayer()
        assertThat(session.player).isSameInstanceAs(before)
        session.player.addListener(listener)
        p.setListener(silentListener())

        p.applyEngineConfig(EngineConfig(bufferForPlaybackMs = 2_000))

        assertThat(p.exposedPlayer()).isSameInstanceAs(before)
        assertThat(session.player).isSameInstanceAs(before)
        val controller = MediaController.Builder(context, session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        val mediaController = controller.get(5, TimeUnit.SECONDS)
        try {
            assertThat(mediaController.isConnected).isTrue()
            mediaController.play()
            await("play intent observed through the pre-swap listener") { listenerEvents.contains(true) }
            mediaController.pause()
            await("pause intent observed through the pre-swap listener") { listenerEvents.contains(false) }
            assertThat(mediaController.availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT)).isFalse()
        } finally {
            mediaController.release()
        }
    }

    @Test
    fun `seek next is available with a queue next and unavailable at the end with repeat off`() {
        val p = newPlayer()
        port.state.value = QueueState(entries = listOf(queueEntry(1), queueEntry(2)), playIndex = 0)
        await("commands published") {
            p.exposedPlayer().availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT)
        }
        assertThat(p.exposedPlayer().isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)).isTrue()

        port.state.value = QueueState(entries = listOf(queueEntry(1), queueEntry(2)), playIndex = 1)
        await("next withdrawn at the end") {
            !p.exposedPlayer().availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT)
        }

        port.state.value = QueueState(
            entries = listOf(queueEntry(1), queueEntry(2)),
            playIndex = 1,
            repeatMode = RepeatMode.ALL,
        )
        await("next returns under repeat all") {
            p.exposedPlayer().availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT)
        }
    }

    @Test
    fun `queue commands route to the command port`() {
        val p = newPlayer()
        port.state.value = QueueState(entries = listOf(queueEntry(1), queueEntry(2)), playIndex = 0)

        p.exposedPlayer().seekToNext()
        p.exposedPlayer().seekToNextMediaItem()
        p.exposedPlayer().seekToPrevious()
        p.exposedPlayer().seekToPreviousMediaItem()
        p.exposedPlayer().seekTo(1, 250L)
        p.exposedPlayer().setRepeatMode(Player.REPEAT_MODE_ALL)
        p.exposedPlayer().setShuffleModeEnabled(true)

        assertThat(port.skipNextCalls).hasSize(2)
        assertThat(port.skipPreviousCalls).hasSize(2)
        assertThat(port.jumpCalls).containsExactly(1)
        assertThat(port.repeatCalls).containsExactly(RepeatMode.ALL)
        assertThat(port.shuffleCalls).containsExactly(true)
    }

    @Test
    fun `exposed timeline is the full effective queue with metadata only items`() {
        val p = newPlayer()
        val entry2 = queueEntry(2, title = "Second")
        port.state.value = QueueState(entries = listOf(queueEntry(1), entry2, queueEntry(3)), playIndex = 1)
        await("timeline published") { p.exposedPlayer().currentTimeline.windowCount == 3 }

        val player = p.exposedPlayer()
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        assertThat(player.currentMediaItem?.mediaId).isEqualTo("2")
        assertThat(player.currentMediaItem?.mediaMetadata?.title).isEqualTo("Second")
        assertThat(player.hasNextMediaItem()).isTrue()
        assertThat(player.getNextMediaItemIndex()).isEqualTo(2)
        assertThat(player.hasPreviousMediaItem()).isTrue()
        assertThat(player.getPreviousMediaItemIndex()).isEqualTo(0)
        for (index in 0 until player.currentTimeline.windowCount) {
            val window = player.currentTimeline.getWindow(index, androidx.media3.common.Timeline.Window())
            assertThat(window.mediaItem.localConfiguration).isNull()
            assertThat(window.mediaItem.mediaId).isEqualTo("${index + 1}")
        }
    }

    @Test
    fun `repeat and shuffle derive from the queue state and route back to the port`() {
        val p = newPlayer()
        port.state.value = QueueState(
            entries = listOf(queueEntry(1), queueEntry(2)),
            repeatMode = RepeatMode.ONE,
        )
        await("repeat published") {
            p.exposedPlayer().repeatMode == Player.REPEAT_MODE_ONE &&
                !p.exposedPlayer().shuffleModeEnabled
        }
        assertThat(port.repeatCalls).isEmpty()
        assertThat(port.shuffleCalls).isEmpty()

        port.state.value = port.state.value.copy(repeatMode = RepeatMode.OFF)
        await("repeat cleared") { p.exposedPlayer().repeatMode == Player.REPEAT_MODE_OFF }
        assertThat(port.repeatCalls).isEmpty()

        p.exposedPlayer().setShuffleModeEnabled(true)
        assertThat(port.shuffleCalls).containsExactly(true)
        assertThat(p.exposedPlayer().shuffleModeEnabled).isFalse()
    }

    @Test
    fun `media list mutation commands are not advertised`() {
        val p = newPlayer()
        port.state.value = QueueState(entries = listOf(queueEntry(1), queueEntry(2)), playIndex = 0)
        await("commands published") { p.exposedPlayer().availableCommands.size() > 0 }

        val commands = p.exposedPlayer().availableCommands
        assertThat(commands.contains(Player.COMMAND_SET_MEDIA_ITEM)).isFalse()
        assertThat(commands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS)).isFalse()
    }

    private fun silentListener() = object : OmniPlayerListener {
        override fun onEngineFinished(entry: PlaybackQueueEntry) = Unit
        override fun onAutoAdvance(entry: PlaybackQueueEntry) = Unit
        override fun onError(entry: PlaybackQueueEntry?, error: androidx.media3.common.PlaybackException) = Unit
        override fun isPlayingChanged(isPlaying: Boolean) = Unit
        override fun onEngineReady(entry: PlaybackQueueEntry) = Unit
    }
}
