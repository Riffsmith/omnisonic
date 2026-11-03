# Sink processors and per-engine chains — `sink-processors.md`

Purpose: Pin how the sink processor chain is built and updated: every processor stays active after its first configure and applies a neutral transform when its feature is off, each engine receives its own chain from a factory that re-applies the latest `ProcessorConfig`, and runtime changes are audible on subsequent samples without a sink reconfigure.

How derived: a measured experiment feeding PCM through the real Media3 1.3.1 `AudioProcessingPipeline` (the exact wrapper `DefaultAudioSink` constructs internally, verified from bytecode) before and after the fix (`AudioPipelineMidStreamTest`); bytecode reading of `AudioProcessingPipeline.configure/flush/processData` and `BaseAudioProcessor.replaceOutputBuffer`; behavioral Robolectric tests. The always-active contract is a recorded decision: see `../decisions.md`.

Confidence:
- [VERIFIED] Media3 1.3.1 `AudioProcessingPipeline.configure()` snapshots `isActive()` after calling `processor.configure()`, and `flush()` (re)builds `activeAudioProcessors`; `queueInput` never re-evaluates `isActive()`. A processor that becomes active mid-stream is invisible to the pipeline until the next configure/flush.
- [VERIFIED] Pre-fix measurement through the real pipeline: with the old active-toggling processors, after mid-stream `beginFadeOut(1000ms)` + mono on + replay-gain 0.5, the pipeline reported 0 output bytes (`expected 960 but was 0`) — the change was inaudible. The prompt's claim was confirmed and the fix applied.
- [VERIFIED] Post-fix measurement: the same mid-stream changes produce 125/125 (replay-gain then mono, first frame) ramping to ~110 by the end of a 120 ms block (fade), with no reconfigure.
- [VERIFIED] With always-active processors the pipeline feeds processors empty input buffers; `BaseAudioProcessor.replaceOutputBuffer(0)` returns the shared static `EMPTY_BUFFER`, so `out.put(inputBuffer)` crashed with `IllegalArgumentException: The source buffer is this buffer` (`ERROR_CODE_FAILED_RUNTIME_CHECK`). All processors now return early on empty input, matching the pattern of Media3's own processors.
- [VERIFIED] `FadeAudioProcessor.isActive()` is now "configured", with `isIdle()` reporting fade completion; the same always-active contract applies to replay-gain, mono (passthrough when disabled or non-stereo) and PEQ (neutral transform for an empty config).
- [VERIFIED] `SinkProcessorChainFactory.create()` produces a NEW chain per engine (two engines never share stateful processors) and re-applies the latest `ProcessorConfig`; `updateConfig` also applies to the active chain so settings changes are immediate. An engine swap picks up the new chain and carries settings (replay-gain factor verified across `applyEngineConfig`).
- [UNKNOWN] On-device audible verification of fades and replay-gain (needs hardware; Robolectric measures the pipeline the sink delegates to).

Date: 2026-11-01

Links: docs/omnisonic/playback/exposed-player.md, docs/omnisonic/playback/stream-cache-identity.md, ../decisions.md.
