# Transcode planner — `transcode-planner.md`

Purpose: pin down the one pure, deterministic transcode decision function introduced in Phase 8 task 2: its inputs, its three-way output, and its refusal boundaries.

How derived: the Phase 8 plan's planner deliverable (one pure function over source format, device/renderer capabilities, settings, provider capabilities → direct play / server transcode with parameters / refusal), D-51 settings semantics, runtime `ProviderTraits` (never persisted), and the existing `StreamKey` variant vocabulary; implemented and proven in `TranscodePlannerTest`.

Confidence: planner behavior is [VERIFIED JVM]. No request path, fallback, network classification, device-limit, download or UI behavior is claimed.

Date: 2026-11-22

Links: `decisions.md` D-51/D-52/D-53; `transcode-settings.md`; `stream-resolution.md`; `offline-resolution.md`; `../protocol/subsonic-transcode-requests.md` (task 3a request seam that consumes this planner's output); `.agents/plans/phase-8-tasks.md`.

---

## Inputs (all pure, all supplied by the caller)

- `TranscodeSource`: `format` (provider codec token, nullable), `bitrateKbps`, `sampleRateHz`. Every field may be absent; absence is a first-class input, not an error. `sampleRateHz` is carried for the later device-limit policy (TODO 4) and is not consumed by this planner.
- `RendererCapabilities`: `supportedFormats` (nullable). `null` means the capability is unknown, so no mismatch can be proven. An empty set means the renderer plays nothing. A known format is matched case-insensitively after trimming.
- `TranscodeSettings`: the current per-renderer settings (D-51).
- `TranscodeRequestKind`: which bitrate slot the plan is computed for — `STREAM_WIFI`, `STREAM_CELLULAR`, or `DOWNLOAD`. Choosing the slot from the live network is TODO 5 and is deliberately not part of this function, so the three settings values can never leak into each other.
- `ProviderTraits`: runtime provider capabilities only; nothing is persisted.

## Decision (D-53)

A transcode is needed when any of: `mandatory` is on; the renderer cannot play the source; the slot's bitrate is explicit (non-null); or a `preferredFormat` is set. Then:

1. No transcode needed → `TranscodeDecision.DirectPlay`.
2. Transcode needed but `providerTraits.supportsServerTranscode == false` → `TranscodeDecision.Refused(PROVIDER_CANNOT_TRANSCODE)` when the renderer could play the source, otherwise `Refused(RENDERER_CANNOT_PLAY_SOURCE)`. Mandatory cannot be silently downgraded to direct play.
3. Otherwise → `TranscodeDecision.ServerTranscode(maxBitRateKbps = <slot bitrate or null>, format = <preferredFormat or null>)`. A null field means "server default"; the planner never invents a value (the 192 kbps download default belongs only to the explicitly planned server-transcode download in TODO 3b). The planner also never drops a requested bitrate because the source bitrate is lower — the server caps at the source, and no up-transcode avoidance policy was decided.

Renderer support: with known non-empty `supportedFormats`, the normalized source format must be a member; with unknown capabilities (`null`) or an absent source format, no mismatch can be proven and the source is treated as playable (an absent format against an empty set is not playable). Format preference alone forces a server transcode, because server transcoding is the only transcode path (K3 excludes on-device transcoding).

## Renderer seam

`TranscodeRendererId(LocalPlaybackRenderer.RENDERER_ID) == TranscodeRendererId.Local` is pinned by an explicit test (`local playback renderer id maps to the local settings renderer id`) so the two separate `local` constants cannot drift silently.

## Determinism

`TranscodePlanner.plan` performs no I/O and reads no clock or randomness. The table test re-plans every case repeatedly and asserts identical results.

## Verification

[VERIFIED JVM 2026-11-22] Serial `./gradlew --no-parallel --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m :playback:compileDebugKotlin :playback:compileReleaseKotlin :playback:testDebugUnitTest :playback:testReleaseUnitTest :app:assembleDebug` exited 0 (`/tmp/opencode/phase-8-task-2-gate.log`). XML: `:playback` **365 tests / 0 failures / 0 errors / 0 skips in each variant** (previous 314; `TranscodePlannerTest` contributes 51 = 48 named table cases + seam + determinism + count). All 51 case names are in the result XML (`/tmp/opencode/phase-8-task-2-xml-totals.txt`). Banned-name scan exit 0; new public names have no `theme_keys.json` or `THEMING.md` §4 collision.

## Scope boundaries

[VERIFIED] Task 2 changes only the pure planner and its tests. No HTTP/client/`stream.view`/`getTranscodeDecision`/`getTranscodeStream` edits, no fallback implementation, no 192 kbps default, no download/offline resolver change, no network detection, no metered/VPN/data-saver behavior, no UI, no Cast, no on-device ffmpeg, no player change, no schema change. Offline-first resolution, variant-qualified identity, and public-export ownership are untouched.

[UNKNOWN] Device-reported capability values, live-server transcode negotiation, and actual transcoded bytes remain with their later tasks and evidence gates.
