# Offload and decoder extensions — `offload-and-extensions.md`

Purpose: Pin what offload and the decoder-preference settings actually do in the current Media3 1.3.1 stack, and how `EngineInfo` reports them honestly: `extensionAvailable` and `offloadEffective` are measured facts, not echoes of the requested config.

How derived: Prompt.md T15; bytecode reading of Media3 1.3.1 `MediaCodecAudioRenderer`, `DefaultAudioSink`, `DefaultRenderersFactory`, and `TrackSelectionParameters.AudioOffloadPreferences` in the local Gradle cache; the repository's dependency catalog; behavioral Robolectric tests (`ExoEngineFactoryTest`).

Confidence:
- [VERIFIED] Media3 1.3.1 offload engagement requires the track-selection layer to prefer offload: `TrackSelectionParameters.AudioOffloadPreferences` defaults to `AUDIO_OFFLOAD_MODE_DISABLED`, and `MediaCodecAudioRenderer.onEnabled` overwrites the sink's offload mode on every enable — it calls `audioSink.setOffloadMode(rendererConfiguration.offloadModePreferred)` only when the renderer's configuration prefers offload, otherwise `setOffloadMode(OFFLOAD_MODE_DISABLED)`. A manual `sink.setOffloadMode(...)` at construction is a capability declaration only; it does not engage offload by itself.
- [VERIFIED] When the sink is configured for offload, `DefaultAudioSink.configure` builds an EMPTY `AudioProcessingPipeline` for the non-PCM output mode — replay-gain, mono, PEQ and fade would be silently bypassed by offloaded audio.
- [VERIFIED] Per the T14 always-active processor design, offload is now disabled automatically whenever any sink processor is wired: `ExoEngineFactory` applies `OFFLOAD_MODE_DISABLED` for the sink unless the processor array is empty, and `EngineInfo.offloadEffective` reports whether the requested mode survived. With production chains, offload is ineffective.
- [VERIFIED] No decoder extension module is on the classpath (`gradle/libs.versions.toml` has none). `DefaultRenderersFactory.buildAudioRenderers` probes `androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer` (and opus/flac/midi) via `Class.forName` and silently continues when absent, so `setExtensionRendererMode` and `DecoderPreference.EXTENSION_FIRST` currently do nothing. `EngineInfo.extensionAvailable` mirrors the factory's probe list and reports false.
- [UNKNOWN] Device offload behavior (offloaded AudioTrack, gapless requirements, battery/performance) — needs hardware.
- [VERIFIED] Decision (user, 2026-11-01): option (3) — the decoder-preference knob is removed until Phase 4 explicitly schedules a decoder extension. `EngineConfig` no longer carries `decoderPreference`, the `DecoderPreference` enum is gone, `PlaybackSettings.decoderPreference` and its persisted DataStore key are removed (nothing is released; no migration needed), and `ExoEngineFactory` builds with the default extension mode plus decoder fallback only. `EngineInfo.extensionAvailable` stays as the honest classpath report; a reflection guard test pins the removal.

Date: 2026-11-01

Links: docs/omnisonic/playback/sink-processors.md, docs/omnisonic/playback/exposed-player.md, Prompt.md T15.
