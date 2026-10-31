# SESSION 5 — T13–T15 (Gate C: player architecture)

Date: 2026-11-01

Scope: T13, T14, T15 from Prompt.md, stopping at the T13–T15 checkpoint. T15's FFmpeg
decoder decision is presented at the end of this report and is **awaiting the user's
answer**; the decision-dependent part is not implemented. T16+ not started.

Decision applied (from `SESSION-4-DECISION.md`, approved by the user as "I agree with
the recommended option made in the last session"):
- **T13 = Option A** with a stable `ForwardingPlayer` exterior + stable inner
  `SimpleBasePlayer`-backed bridge, **full effective-order queue timeline**,
  `prepareNext` + explicit auto-advance handoff, and the roadmap §0.3 table edit
  **deferred to T24** (kept out of this session's commits per the decision brief).

---

## T13 — Stable exposed Player (ForwardingPlayer) and queue-aware commands

- Problem confirmed? **Yes.** [VERIFIED by reading the pre-change code]
  `OmniPlayerImpl.exposedPlayer()` returned `engine.player` (raw ExoPlayer) and
  `applyEngineConfig()` released and replaced that instance, so any session bound to
  the old player held a released object. The exposed timeline was always exactly one
  item (`setMediaSource(single, position)`), so `COMMAND_SEEK_TO_NEXT/PREVIOUS` were
  unavailable to session surfaces and there was no gapless handoff.
- Files changed:
  - `playback-api`: new `queue/QueueCommandPort.kt` (queueState + skipNext/
    skipPrevious/jumpToOrderIndex/setRepeatMode/setShuffleEnabled);
    `PlaybackController` now extends `QueueCommandPort`; `OmniPlayer.prepareNext(
    stream: ResolvedStream?)`; `OmniPlayerListener.onAutoAdvance(entry)`;
    `QueuePlayer.onEngineAutoAdvanced(entry)`.
  - `playback`: new `player/OmniForwardingPlayer.kt` (stable exterior; next/prev/
    jump/repeat/shuffle routes to the port; has/next-index getters derive from
    `QueueCommandPolicy`), `player/QueueAwareBridge.kt` (stable `SimpleBasePlayer`
    publishing the full effective-order queue as a metadata-only timeline plus
    engine-derived transport state; falls back to the player's own current/prepared
    entries when the queue does not know the engine item), `player/
    QueueCommandPolicy.kt` (pure skip policy mirroring the queue machine);
    `player/OmniPlayerImpl.kt` reworked (stable exposed identity, `prepareNext` with
    `[current, next]` engine window, window sliding after auto-advance, engine swap
    sequence that prepares the new engine before releasing the old and rejects
    looper mismatches, auto-advance verification by media id with divergence
    surfaced through the existing error path); `queue/QueuePlayerImpl.kt` gained
    `onEngineAutoAdvanced` (validate-adopt-without-replay), `schedulePrepareFollowing`
    (resolve next under the repeat policy; null trims the tail, incl. repeat ONE) and
    preparation cancellation on every queue edit.
  - The pre-existing uncommitted queue machine (`QueuePlayerImpl.kt`, its test, and
    the `setQueue(entries, start, sourceType, sourceData)` signature) from before
    Session 3 was included in this commit because T13 builds directly on it.
  - Docs: `docs/omnisonic/playback/exposed-player.md` (new).
- Test added (failing state first):
  - Reproduction output (compile-level, `:playback:compileDebugUnitTestKotlin`):
    `Unresolved reference 'QueueCommandPort'` (x2), `'queueState' overrides nothing`,
    `Too many arguments for public constructor` (no port wiring),
    `'onAutoAdvance' overrides nothing`.
  - New `OmniForwardingPlayerTest` (6 tests): session player identity survives an
    engine swap and stays controllable (Robolectric `MediaSession` + in-process
    `MediaController`: `session.player === exposedPlayer()` before/after
    `applyEngineConfig`, play/pause observed through the pre-swap listener);
    SEEK_TO_NEXT available with a queue next / withdrawn at the end with repeat OFF /
    restored under repeat ALL; queue commands route to the port; exposed timeline is
    the full queue with metadata-only items (no `localConfiguration`); repeat derives
    from queue state and routes back to the port; media-list mutation commands never
    advertised.
  - `QueuePlayerImplTest` +9 tests: start prepares the expected next; auto-advance
    adopts the prepared next without replaying (Finished + AUTO_ADVANCE
    TrackChanged, no new `play`); unexpected auto-advance resyncs to the queue
    current; wrap under repeat ALL; repeat ONE prepares no next; queue end prepares
    no next and natural finish still stops; removing the prepared next refreshes the
    preparation; clearing the queue stops; pending preparation is dropped when the
    current entry changes.
  - `OmniPlayerImplTest`: engine-swap assertion changed from `!==` to `===`
    (deliberate behavior change per the decision, plus post-swap listener
    continuity), gapless smoke test (two queued WAVs advance with no
    IDLE/ENDED/BUFFERING state change between, `onAutoAdvance` observed), and
    prepareNext-before-play joins the engine playlist at start.
- Result: `./gradlew test :app:assembleDebug` → BUILD SUCCESSFUL; module aggregate at
  commit time: 318 tests, 0 failures, 0 errors, 4 skipped. All six prompt proofs
  pass (MediaSession identity, SEEK_TO_NEXT availability incl. the repeat-OFF-end
  exclusion, gapless smoke test).
- Commit: **4ffcce6** `fix(T13): expose a stable queue-aware player with gapless handoff`
- Unresolved: on-device session surfaces (Bluetooth/lock-screen) unverified (no
  device); very large queue binder cost not measured; roadmap §0.3 table wording
  change is recorded in the decision brief and lands with T24.

## T14 — Sink processors: per-engine chains and audible-effect verification

- VERIFY FIRST result: **defect confirmed.** Feeding PCM through the real Media3
  1.3.1 `AudioProcessingPipeline` (the exact wrapper `DefaultAudioSink` constructs
  internally — verified from bytecode) with the old active-toggling processors:
  baseline block passed through unchanged (240 frames/960 bytes, samples 1000/−500);
  after mid-stream `beginFadeOut(1000ms)` + mono-on + replay-gain 0.5 the next block
  produced **0 output bytes** (`expected 960 but was 0`) — the change is inaudible
  because `AudioProcessingPipeline.configure()` snapshots `isActive()` and
  `flush()` (re)builds the active chain; `queueInput` never re-evaluates. Bytecode
  also shows the pipeline is only re-flushed on sink configure/flush events.
- Second defect found while implementing (measured, then fixed): with always-active
  processors the pipeline feeds empty input buffers; `BaseAudioProcessor
  .replaceOutputBuffer(0)` returns the shared static `EMPTY_BUFFER`, so
  `out.put(inputBuffer)` crashed with `IllegalArgumentException: The source buffer is
  this buffer` (`ERROR_CODE_FAILED_RUNTIME_CHECK`, caught in the probe as a
  PlaybackException). All processors now return early on empty input — the same
  guard Media3's own processors use.
- Files changed:
  - `playback`: all four processors (`FadeAudioProcessor`, `ReplayGainAudioProcessor`,
    `MonoAudioProcessor`, `PeqAudioProcessor`) stay active after first configure and
    apply neutral transforms when off (`FadeAudioProcessor.isIdle()` now reports fade
    completion; mono passes non-stereo/disabled input through; PEQ is identity for an
    empty config); empty-input guard in every `queueInput`;
    `player/SinkProcessorChain.kt` rewritten as `ProcessorConfig` +
    `SinkProcessorChain.apply/snapshotConfig` + `SinkProcessorChainFactory` (NEW chain
    per engine, latest config re-applied to each new chain, `updateConfig` also
    pushes to the active chain); `player/OmniPlayerImpl.kt` rewired to the factory
    (RG/mono/PEQ changes flow through `pushConfig`).
  - Tests: `AudioPipelineMidStreamTest` (the VERIFY FIRST measurement, now the
    acceptance test: mid-stream fade/mono/RG audible without reconfigure — first
    frame 125/125, end of a 120 ms block ~110; two chains independent; factory
    re-applies the latest config), processor tests updated to the always-active
    contract with output-level neutrality assertions (contract change justified by
    T14; the old `isActive()` assertions described the retired behavior),
    `OmniPlayerImplTest`: two engines built from one factory hold independent
    processor chains; engine swap gets a NEW chain carrying the RG factor.
  - Docs: `docs/omnisonic/playback/sink-processors.md` (new).
- Result: `./gradlew test :app:assembleDebug` → BUILD SUCCESSFUL; 319 tests, 0
  failures, 0 errors, 4 skipped. Prompt proofs: real sample output measured after
  mid-stream fade/mono/RG changes; two-engine independence proven.
- Commit: **d9cee18** `fix(T14): keep sink processors active with per-engine chains`
- Unresolved: on-device audible verification (Robolectric measures the pipeline the
  sink delegates to, not hardware output); crossfade still out of scope (Phase 4).

## T15 — FFmpeg extension and offload honesty (decision pending)

- VERIFY FIRST result (Media3 1.3.1 bytecode, local Gradle cache):
  1. Offload engagement requires the track-selection layer:
     `TrackSelectionParameters.AudioOffloadPreferences` defaults to
     `AUDIO_OFFLOAD_MODE_DISABLED`, and `MediaCodecAudioRenderer.onEnabled`
     **overwrites** the sink's offload mode on every enable (sets
     `offloadModePreferred` only when the renderer config prefers offload, else
     `OFFLOAD_MODE_DISABLED`). Our manual `sink.setOffloadMode(...)` was therefore a
     capability declaration, never an engagement. [VERIFIED]
  2. If offload engaged, `DefaultAudioSink.configure` builds an EMPTY
     `AudioProcessingPipeline` for the non-PCM output mode — replay-gain/mono/PEQ/
     fade would be silently bypassed. [VERIFIED]
  3. `DefaultRenderersFactory.buildAudioRenderers` probes
     `androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer` (and opus/flac/midi) via
     `Class.forName` and silently continues when absent; with no decoder module in
     `gradle/libs.versions.toml`, `setExtensionRendererMode` and
     `DecoderPreference.EXTENSION_FIRST` do nothing. [VERIFIED]
- Fix applied (non-decision part):
  - `playback-api`: `EngineInfo` gains `extensionAvailable` and `offloadEffective`.
  - `playback`: `ExoEngineFactory` disables offload automatically whenever any sink
    processor is wired (always, under the T14 chains) and reports honestly:
    `offloadEffective` = the requested mode survived construction;
    `extensionAvailable` mirrors the factory's own extension probe list.
  - Tests (failing first: `Unresolved reference 'offloadEffective'` /
    `'extensionAvailable'`): chain wired + `OffloadMode.ENABLED` →
    `offloadEffective=false`, `extensionAvailable=false`; chain empty + ENABLED →
    true; disabled request → false.
  - Docs: `docs/omnisonic/playback/offload-and-extensions.md` (new).
- Result: `./gradlew test :app:assembleDebug` → BUILD SUCCESSFUL; 322 tests, 0
  failures, 0 errors, 4 skipped.
- Commit: **a62ec69** `fix(T15): report offload and extension availability honestly`
- **DECISION PENDING (blocks the remaining T15 work):** FFmpeg decoder module —
  options and recommendation in the section below. Nothing decision-dependent has
  been implemented; `DecoderPreference.EXTENSION_FIRST` remains inert until answered.

---

## Session-wide verification

- `./gradlew test :app:assembleDebug` after each task → BUILD SUCCESSFUL each time;
  final module aggregate: app 4, core-data 82, core-model 18, core-network 10,
  playback 114, playback-api 10, provider-api 44, provider-local 11,
  provider-navidrome 26 (4 skipped) = **322 tests, 0 failures, 0 errors, 4 skipped**.
- `scripts/check-banned-names.sh` over every changed non-docs file per task → clean
  each time. Docs files checked too.
- Commits (separate per task): 4ffcce6 (T13), d9cee18 (T14), a62ec69 (T15).

## Decisions received this session

- T13: approved `SESSION-4-DECISION.md` recommendation — Option A, full
  effective-order queue timeline, `prepareNext` + auto-advance handoff, roadmap
  table edit deferred to T24. Applied as specified.

## Environment not available (never fabricated)

- No adb device/emulator: on-device session behavior (Bluetooth/lock-screen), the
  gapless handoff on hardware, audible fade/replay-gain measurement on real output,
  and device offload behavior remain unverified. No live server needed this session.

## Awaited decision (T15 remainder)

FFmpeg decoder module: (1) build/vendor the Media3 FFmpeg extension (native build
cost, per-ABI `.so`), (2) depend on a maintained prebuilt Media3 FFmpeg decoder
artifact (license/ABI/size must be vetted before adoption), (3) keep the current
decoder preference inert until Phase 4 explicitly schedules it — **recommended (3)
now, (1) or (2) later**. Per the prompt this is a DECISION gate: waiting for the
user's answer before implementing the chosen option. Session ends here either way
(T13–T15 checkpoint).
