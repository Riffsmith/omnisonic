
package app.omnisonic.playback.engine

import androidx.media3.common.util.UnstableApi
import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import app.omnisonic.playback.api.player.AudioEngine
import app.omnisonic.playback.api.player.AudioEngineFactory
import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.EngineInfo
import app.omnisonic.playback.api.player.OffloadMode
import app.omnisonic.playback.api.player.WakeMode

@UnstableApi
class ExoEngineFactory(
    private val context: Context,
    private val clock: Clock = Clock.DEFAULT,
    private val processors: () -> Array<AudioProcessor> = { emptyArray() },
) : AudioEngineFactory {

    // Test-clock injection is the documented Robolectric seam for playback tests; media3
    // annotates Builder.setClock test-only, but it is the intended production seam here.
    @SuppressLint("VisibleForTests")
    override fun create(config: EngineConfig): AudioEngine {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                config.minBufferMs,
                config.maxBufferMs,
                config.bufferForPlaybackMs,
                config.bufferForPlaybackAfterRebufferMs,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val renderersFactory = EngineRenderersFactory(context, config, processors)
        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setClock(clock)
            .setAudioAttributes(ENGINE_AUDIO_ATTRIBUTES, false)
            .setWakeMode(config.wakeMode.toPlayerWakeMode())
            .build()
        return ExoAudioEngine(
            config = config,
            player = player,
            extensionAvailable = audioExtensionRendererAvailable(),
            offloadEffective = renderersFactory.offloadModeSurvived,
        )
    }

    private class EngineRenderersFactory(
        context: Context,
        private val config: EngineConfig,
        private val processors: () -> Array<AudioProcessor>,
    ) : DefaultRenderersFactory(context) {

        var offloadModeSurvived: Boolean = false
            private set

        init {
            setEnableDecoderFallback(true)
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val wired = processors()
            offloadModeSurvived = config.offloadMode != OffloadMode.DISABLED && wired.isEmpty()
            val sink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(wired)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
            val effectiveOffload = if (wired.isEmpty()) config.offloadMode else OffloadMode.DISABLED
            sink.setOffloadMode(effectiveOffload.toSinkOffloadMode())
            return sink
        }

        private fun OffloadMode.toSinkOffloadMode(): Int = when (this) {
            OffloadMode.DISABLED -> AudioSink.OFFLOAD_MODE_DISABLED
            OffloadMode.AUTO -> AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED
            OffloadMode.ENABLED -> AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED
        }
    }

    private companion object {
        val ENGINE_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val EXTENSION_AUDIO_RENDERER_CLASSES = listOf(
            "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer",
            "androidx.media3.decoder.opus.LibopusAudioRenderer",
            "androidx.media3.decoder.flac.LibflacAudioRenderer",
            "androidx.media3.decoder.midi.MidiRenderer",
        )

        fun audioExtensionRendererAvailable(): Boolean =
            EXTENSION_AUDIO_RENDERER_CLASSES.any { runCatching { Class.forName(it) }.isSuccess }
    }
}

@UnstableApi
internal class ExoAudioEngine(
    config: EngineConfig,
    override val player: Player,
    extensionAvailable: Boolean,
    offloadEffective: Boolean,
) : AudioEngine {

    private val info = EngineInfo(
        config = config,
        loudnessCodecAttached = false,
        extensionAvailable = extensionAvailable,
        offloadEffective = offloadEffective,
    )

    override fun info(): EngineInfo = info

    override fun release() {
        player.release()
    }
}

@UnstableApi
internal fun WakeMode.toPlayerWakeMode(): Int = when (this) {
    WakeMode.LOCAL -> C.WAKE_MODE_LOCAL
    WakeMode.NETWORK -> C.WAKE_MODE_NETWORK
}
