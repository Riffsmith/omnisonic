package app.omnisonic.playback.renderer

import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.OmniPlayer
import app.omnisonic.playback.api.player.OmniPlayerListener
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.api.stream.StreamResolver
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.omnisonic.playback.api.equalizer.PeqConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

private class CountingPlayer : OmniPlayer {
    var releases = 0

    override fun exposedPlayer(): Player = error("not used")
    override fun play(stream: ResolvedStream, entry: PlaybackQueueEntry) = Unit
    override fun prepareNext(stream: ResolvedStream?) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekToStart() = Unit
    override fun setPlaybackSpeed(scale: Float) = Unit
    override fun setPlaybackPitch(pitch: Float) = Unit
    override fun setReplayGainLinear(multiplier: Float) = Unit
    override fun setDuckLinear(multiplier: Float) = Unit
    override fun setMono(enabled: Boolean) = Unit
    override fun setPeq(config: PeqConfig?) = Unit
    override fun fadePause() = Unit
    override fun fadeResume() = Unit
    override fun applyEngineConfig(config: EngineConfig) = Unit
    override fun setStreamResolver(resolver: StreamResolver) = Unit
    override fun setListener(listener: OmniPlayerListener?) = Unit
    override fun release() {
        releases += 1
    }
}

class LocalPlaybackRendererTest {

    @Test
    fun `activate reuses one player instance`() = runTest {
        val players = mutableListOf<CountingPlayer>()
        val renderer = LocalPlaybackRenderer { CountingPlayer().also { players += it } }

        val first = renderer.activate()
        val second = renderer.activate()

        assertThat(second).isSameInstanceAs(first)
        assertThat(players).hasSize(1)
        assertThat(renderer.isActive()).isTrue()
        assertThat(renderer.isLocal).isTrue()
        assertThat(renderer.rendererId).isEqualTo(LocalPlaybackRenderer.RENDERER_ID)
    }

    @Test
    fun `deactivate releases the player and reports inactivity`() = runTest {
        val player = CountingPlayer()
        val renderer = LocalPlaybackRenderer { player }
        renderer.activate()

        renderer.deactivate()

        assertThat(player.releases).isEqualTo(1)
        assertThat(renderer.isActive()).isFalse()
    }

    @Test
    fun `deactivate without activation is a no-op`() = runTest {
        val renderer = LocalPlaybackRenderer { error("never activated") }

        renderer.deactivate()

        assertThat(renderer.isActive()).isFalse()
    }

    @Test
    fun `reactivation after deactivate creates a fresh player`() = runTest {
        val players = mutableListOf<CountingPlayer>()
        val renderer = LocalPlaybackRenderer { CountingPlayer().also { players += it } }
        val first = renderer.activate() as CountingPlayer

        renderer.deactivate()
        val second = renderer.activate() as CountingPlayer

        assertThat(second).isNotSameInstanceAs(first)
        assertThat(first.releases).isEqualTo(1)
        assertThat(second.releases).isEqualTo(0)
        assertThat(players).hasSize(2)
    }
}
