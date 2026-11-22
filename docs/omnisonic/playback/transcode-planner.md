# Transcode planner — `transcode-planner.md`

Purpose: pin down the one pure, deterministic transcode decision function introduced in Phase 8 task 2: its inputs, its three-way output, and its refusal boundaries — plus the separate pure network→slot policy (TODO 5, D-60) that feeds the planner's slot input.

How derived: the Phase 8 plan's planner deliverable (one pure function over source format, device/renderer capabilities, settings, provider capabilities → direct play / server transcode with parameters / refusal), D-51 settings semantics, runtime `ProviderTraits` (never persisted), the existing `StreamKey` variant vocabulary and `NetworkState`/`NetworkMonitor` run-gate vocabulary, and the user-confirmed metered/VPN semantics (D-60); implemented and proven in `TranscodePlannerTest` and `TranscodeSlotResolverTest`.

Confidence: planner behavior including the device sample-rate limit rule (D-59) and the network slot resolution (D-60) is [VERIFIED JVM]. No request path, fallback, download or UI behavior is claimed, and device-reported capability values remain `[UNKNOWN]` until a hardware trace.

Date: 2026-11-22 (device-limit rule TODO 4 and network slot resolution TODO 5 added 2026-11-22)

Links: `decisions.md` D-51/D-52/D-53/D-59/D-60; `transcode-settings.md`; `stream-resolution.md`; `offline-resolution.md`; `../providers/provider-connections.md` (the `NetworkMonitor` vocabulary D-60 consumes); `../protocol/subsonic-transcode-requests.md` (task 3a request seam that consumes this planner's output); `.agents/plans/phase-8-tasks.md`.

---

## Inputs (all pure, all supplied by the caller)

- `TranscodeSource`: `format` (provider codec token, nullable), `bitrateKbps`, `sampleRateHz`. Every field may be absent; absence is a first-class input, not an error. `sampleRateHz` feeds the device-limit rule (D-59).
- `RendererCapabilities`: `supportedFormats` (nullable) and `maxSampleRateHz` (nullable device sample-rate limit, D-59). A null field means the capability is unknown, so no mismatch can be proven. An empty format set means the renderer plays nothing. A known format is matched case-insensitively after trimming.
- `TranscodeSettings`: the current per-renderer settings (D-51).
- `TranscodeRequestKind`: which bitrate slot the plan is computed for — `STREAM_WIFI`, `STREAM_CELLULAR`, or `DOWNLOAD`. Choosing the slot from the live network is `TranscodeSlotResolver` (D-60, below) and is deliberately not part of this function, so the three settings values can never leak into each other.
- `ProviderTraits`: runtime provider capabilities only; nothing is persisted.

## Decision (D-53)

A transcode is needed when any of: `mandatory` is on; the renderer cannot play the source; the slot's bitrate is explicit (non-null); or a `preferredFormat` is set. Then:

1. No transcode needed → `TranscodeDecision.DirectPlay`.
2. Transcode needed but `providerTraits.supportsServerTranscode == false` → `TranscodeDecision.Refused(PROVIDER_CANNOT_TRANSCODE)` when the renderer could play the source, otherwise `Refused(RENDERER_CANNOT_PLAY_SOURCE)`. Mandatory cannot be silently downgraded to direct play.
3. Otherwise → `TranscodeDecision.ServerTranscode(maxBitRateKbps = <slot bitrate or null>, format = <preferredFormat or null>)`. A null field means "server default"; the planner never invents a value (the 192 kbps download default belongs only to the explicitly planned server-transcode download in TODO 3b). The planner also never drops a requested bitrate because the source bitrate is lower — the server caps at the source, and no up-transcode avoidance policy was decided.

Renderer support: with known non-empty `supportedFormats`, the normalized source format must be a member; with unknown capabilities (`null`) or an absent source format, no mismatch can be proven and the source is treated as playable (an absent format against an empty set is not playable). Format preference alone forces a server transcode, because server transcoding is the only transcode path (K3 excludes on-device transcoding).

## Device sample-rate limit (TODO 4, D-59)

`RendererCapabilities.maxSampleRateHz` is the renderer/device sample-rate limit. A source is playable as-is only when its `sampleRateHz` is at or below the limit; strictly above it, the renderer cannot play the source and a server transcode/downsample is needed (with the usual settings parameters, or server default when nothing is set). The boundary is inclusive: at the limit direct-plays, above it transcodes. A null limit or a null source sample rate proves no exceedance, so the plan is unchanged — the same optimistic-unknown rule as for formats. The rule folds into the existing renderer-playability check, so an over-limit source without provider transcode support refuses `RENDERER_CANNOT_PLAY_SOURCE`, and it applies to every request kind including `DOWNLOAD`. The planner reads no device API, so it stays pure and deterministic (D-53); where the limit value comes from is a device-capability question that remains `[UNKNOWN]` until traced on hardware.

Deliberate seam decision: `PlannedStreamVariants` (the offline desired-variant supply, D-56) keeps planning over unknown source/renderer metadata — `PlaybackQueueEntry` carries no source metadata and the device-reported limit is not read at that seam — so the device-limit rule cannot force an offline variant there. A device-forced server-default plan therefore has no exact offline preference (D-57) and the newest-readable fallback stays authoritative; no song metadata is invented and no offline identity changes.

## Network slot resolution (TODO 5, D-60)

`TranscodeSlotResolver.streamSlot(network, settings)` is a separate pure function that maps the `NetworkState` snapshot from the existing `NetworkMonitor` run-gate vocabulary onto `STREAM_WIFI` or `STREAM_CELLULAR` (never `DOWNLOAD`). The planner stays pure and deterministic (D-53): slot selection is a caller decision feeding the existing `TranscodeRequestKind` input, not new planner policy.

- Unmetered Wi-Fi takes `STREAM_WIFI`; cellular takes `STREAM_CELLULAR`; an offline (not connected) snapshot takes `STREAM_CELLULAR`, so a non-Wi-Fi state never assumes Wi-Fi settings.
- User-confirmed semantics (2026-11-22), each a per-renderer toggle in `TranscodeSettings` defaulting to the confirmed behavior: `vpnAsCellular = true` — a VPN transport (reported as `NetworkState.vpn`, still not `wifi`, so run-gate behavior is unchanged) takes the cellular slot unless the renderer opts out; `meteredAsCellular = true` — a metered network (`NetworkState.metered`, from `NET_CAPABILITY_NOT_METERED`) takes the cellular slot unless the renderer opts out. A metered VPN takes the cellular slot when either applicable toggle is on; both toggles off are required for the Wi-Fi slot.
- The slot depends only on the network snapshot and the two toggles — never on the bitrate values themselves — so Wi-Fi/cellular/download values stay independent and one renderer's settings cannot leak into another's plan across VPN/metered transitions.

[VERIFIED, TODO 6 / D-61] Since TODO 6 the slot is no longer offline-only: `StreamPlanSource` (`:playback` `stream/PlannedProviderStreamResolver.kt`) computes the same slot + plan for online playback on every resolve, and `PlannedProviderStreamResolver` sends `ServerTranscode` plans through `MediaProvider.resolveTranscodeStream` while `DirectPlay`/`Refused` land on the plain `streamUrl`. `StreamProfileWatcher` recomputes the same plan over the local renderer's settings flow and the `NetworkStateObserver` connectivity stream and commands `QueuePlayer.replanCurrentStream()` only when the decision for the *same* queue entry changed while playing — so a Wi-Fi↔cellular/VPN/metered transition or a transcode-settings edit re-requests the current stream at its current position, while no-op network flaps do not.

## Renderer seam

`TranscodeRendererId(LocalPlaybackRenderer.RENDERER_ID) == TranscodeRendererId.Local` is pinned by an explicit test (`local playback renderer id maps to the local settings renderer id`) so the two separate `local` constants cannot drift silently.

## Determinism

`TranscodePlanner.plan` performs no I/O and reads no clock or randomness. The table test re-plans every case repeatedly and asserts identical results.

## Verification

[VERIFIED JVM 2026-11-22, task 2] Serial `./gradlew --no-parallel --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m :playback:compileDebugKotlin :playback:compileReleaseKotlin :playback:testDebugUnitTest :playback:testReleaseUnitTest :app:assembleDebug` exited 0 (`/tmp/opencode/phase-8-task-2-gate.log`). XML: `:playback` **365 tests / 0 failures / 0 errors / 0 skips in each variant**; `TranscodePlannerTest` contributed 51 = 48 named table cases + seam + determinism + count (`/tmp/opencode/phase-8-task-2-xml-totals.txt`).

[VERIFIED JVM 2026-11-22, TODO 4] The same serial gate exited 0 (`/tmp/opencode/phase-8-task-4-gate.log`). XML: `:playback` **392 tests / 0 failures / 0 errors / 0 skips in each variant** (previous 375); `TranscodePlannerTest` contributes 67 = 64 named table cases (16 new device-limit cases: below/at/above/very-high boundaries, bitrate/format/mandatory combinations, two provider-refusal cases, missing-limit, missing-source-rate, unknown-formats, download slot, two empty-set cases) + seam + determinism + count, and `PlannedStreamVariantsTest` contributes 8 (new fully-qualified variant case). All new case names are in the result XML (`/tmp/opencode/phase-8-task-4-xml-totals.txt`). Banned-name scan exit 0 over every touched file; the only new public name (`maxSampleRateHz`) has no `theme_keys.json` or `THEMING.md` §4 collision.

[VERIFIED JVM 2026-11-22, TODO 5] Serial `:core-data` + `:playback` compile + debug/release tests + `:app:assembleDebug` exited 0 (`/tmp/opencode/phase-8-task-5-gate.log`). XML: `:core-data` **814/0/0/0** (previous 808; `ConnectivityManagerNetworkMonitorTest` 9 with three new unmetered/VPN cases, `TranscodeSettingsTest` 7, `TranscodeSettingsStoreTest` 11 with the toggle round-trip and absent-key default-decode cases) and `:playback` **411/0/0/0** (previous 392; `TranscodeSlotResolverTest` 13, `PlannedStreamVariantsTest` 14 with real slot selection, network-transition and renderer-isolation cases) in each variant; all new case names are in `/tmp/opencode/phase-8-task-5-xml-totals.txt`. Banned-name scan exit 0 over every touched file; the new public names (`TranscodeSlotResolver`, `streamSlot`, `vpnAsCellular`, `meteredAsCellular`, `NetworkState.metered`/`vpn`) have no `theme_keys.json` or `THEMING.md` §4 collision. The planner itself is unchanged by TODO 5.

## Scope boundaries

[VERIFIED] Task 2 + TODO 4 + TODO 5 change only the pure planner, its `RendererCapabilities` input, the pure `TranscodeSlotResolver`, the additive `NetworkState.metered`/`vpn` vocabulary fields, the two additive `TranscodeSettings` toggles, the `PlannedStreamVariants` slot selection, and their tests. No HTTP/client/`stream.view`/`getTranscodeDecision`/`getTranscodeStream` edits, no fallback implementation, no 192 kbps default, no download/offline resolver change beyond real slot selection, no data-saver controls or mid-track application (TODO 6), no UI, no Cast, no on-device ffmpeg, no player change, no schema change. Offline-first resolution, variant-qualified identity, and public-export ownership are untouched.

[UNKNOWN] Device-reported capability values (including what `maxSampleRateHz` will be on real hardware), audible downsampling, live-server transcode negotiation, and actual transcoded bytes remain `[UNKNOWN]` until a hardware/live-server trace; JVM planner evidence is not promoted to device evidence.
