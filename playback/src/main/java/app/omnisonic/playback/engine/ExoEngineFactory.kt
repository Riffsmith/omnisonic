package app.omnisonic.playback.engine

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
import app.omnisonic.playback.api.player.DecoderPreference
import app.omnisonic.playback.api.player.EngineConfig
import app.omnisonic.playback.api.player.EngineInfo
import app.omnisonic.playback.api.player.OffloadMode

class ExoEngineFactory(
    private val context: Context,
    private val clock: Clock = Clock.DEFAULT,
    private val processors: () -> Array<AudioProcessor> = { emptyArray() },
) : AudioEngineFactory {

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
        val player = ExoPlayer.Builder(context, EngineRenderersFactory(context, config, processors))
            .setLoadControl(loadControl)
            .setClock(clock)
            .setAudioAttributes(ENGINE_AUDIO_ATTRIBUTES, false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        return ExoAudioEngine(config, player)
    }

    private class EngineRenderersFactory(
        context: Context,
        private val config: EngineConfig,
        private val processors: () -> Array<AudioProcessor>,
    ) : DefaultRenderersFactory(context) {

        init {
            setExtensionRendererMode(
                when (config.decoderPreference) {
                    DecoderPreference.MEDIA_FIRST -> EXTENSION_RENDERER_MODE_ON
                    DecoderPreference.EXTENSION_FIRST -> EXTENSION_RENDERER_MODE_PREFER
                },
            )
            setEnableDecoderFallback(true)
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            val sink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(processors())
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
            sink.setOffloadMode(config.offloadMode.toSinkOffloadMode())
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
    }
}

internal class ExoAudioEngine(
    private val config: EngineConfig,
    override val player: Player,
) : AudioEngine {

    override fun info(): EngineInfo = EngineInfo(config, loudnessCodecAttached = false)

    override fun release() {
        player.release()
    }
}
