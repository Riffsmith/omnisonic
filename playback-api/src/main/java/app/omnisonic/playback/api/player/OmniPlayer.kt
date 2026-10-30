package app.omnisonic.playback.api.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.omnisonic.playback.api.equalizer.PeqConfig
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.stream.ResolvedStream
import app.omnisonic.playback.api.stream.StreamResolver

/**
 * Playing surface for one renderer output. QueuePlayer commands it; the
 * media session and UI bind to [exposedPlayer]. One instance per renderer.
 */
interface OmniPlayer {
    /** The facade this layer exposes upward (session/UI). Queue semantics own nothing inside it. */
    fun exposedPlayer(): Player

    /** Starts (or restarts) playback of a resolved stream; resumes from [ResolvedStream] positions. */
    fun play(stream: ResolvedStream, entry: PlaybackQueueEntry)

    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun seekToStart()

    fun setPlaybackSpeed(scale: Float)
    fun setPlaybackPitch(pitch: Float)

    /** Scaled by ReplayGain selection before it reaches the sink processor. */
    fun setReplayGainLinear(multiplier: Float)
    fun setMono(enabled: Boolean)
    fun setPeq(config: PeqConfig?)

    /** Short sink fade out (fails over to an immediate pause when fading is unavailable). */
    fun fadePause()
    /** Short sink fade in before playback resumes. */
    fun fadeResume()

    /** Applies settings that only matter at engine construction; triggers an engine swap. */
    fun applyEngineConfig(config: EngineConfig)

    // Cache seam: rendered src switch feels engine-local.
    fun setStreamResolver(resolver: StreamResolver)

    fun setListener(listener: OmniPlayerListener?)
    fun release()
}

interface OmniPlayerListener {
    /** The engine completed the active stream on its own (natural end / auto advance). */
    fun onEngineFinished(entry: PlaybackQueueEntry)
    fun onError(entry: PlaybackQueueEntry?, error: PlaybackException)
    fun isPlayingChanged(isPlaying: Boolean)
    /** Reaction point the queue player uses to drive restore and seek fuzz. */
    fun onEngineReady(entry: PlaybackQueueEntry)
}
