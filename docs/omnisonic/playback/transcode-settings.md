# Transcode settings — `transcode-settings.md`

Purpose: define the persisted per-renderer transcode preferences introduced in Phase 8 task 1, their validation boundaries, the network-classification toggles added in TODO 5, and the evidence limit of the model/policy-only tasks.

How derived: user-confirmed D-51 and the transcoded-download continuity decision D-52, the user-confirmed metered/VPN semantics D-60 (TODO 5, 2026-11-22), the Phase 8 dependency plan, and direct inspection of the Omnisonic model, DataStore store, and named JVM tests.

Confidence: model, persistence, validation, renderer isolation, and the D-60 toggle defaults/decode are [VERIFIED JVM]. No planner, request, device, live-server, or UI behavior is claimed.

Date: 2026-11-22 (D-60 toggles added 2026-11-22, TODO 5)

Links: `decisions.md` D-51/D-52/D-60; `offline-resolution.md`; `transcode-planner.md` §Network slot resolution; `../providers/provider-connections.md`; `../storage/download-acceptance.md`; `.agents/plans/phase-8-tasks.md`.

---

## Model and defaults

[VERIFIED] `TranscodeSettings` contains nullable `wifiBitrateKbps`, `cellularBitrateKbps`, and `downloadBitrateKbps`, plus `mandatory` and nullable `preferredFormat`. `null` bitrate means off; every explicit bitrate is positive, with 1 and `Int.MAX_VALUE` accepted as model boundaries. The default object has all three bitrates off, `mandatory = false`, and no preferred format. This task does not choose a 192 kbps default; that belongs to the later explicitly planned server-transcode download path.

[VERIFIED] D-51 defines `preferredFormat` as an optional lowercase provider-neutral token. A token is 1–32 characters and consists of alphanumeric segments separated by single `.`, `_`, or `-` characters. Examples accepted by the tests are `mp3`, `opus`, `flac`, `aac`, `vorbis`, `mp4.audio`, `audio_1`, and `format-2`. Empty, uppercase, whitespace-padded, slash-containing, leading/trailing/doubled-separator, and over-length values are rejected. D-57 additionally rejects the reserved `auto` sentinel because it already encodes no format in media keys and cannot represent a distinct explicit preference. `null` means no client-selected format override; this is not a fixed output-format enum and does not imply provider support.

[VERIFIED] `TranscodeRendererId` uses the same token validation. `TranscodeRendererId.Local` is the only production renderer constant. The store accepts a renderer id on every read/write, so another renderer can be added without merging its preferences into local settings. A test-only renderer id proves independence; it is not a Cast implementation.

## Persistence and invalid data

[VERIFIED] `TranscodeSettingsStore` persists each renderer's values in the `omnisonic_transcode` preferences DataStore under keys namespaced by `transcode.<renderer-id>` (same store, same per-renderer namespacing, same atomic whole-object edit — TODO 5 adds keys additively, no store reshape or migration). Explicit bitrates and formats use integer/string preferences; off bitrates and absent format preference remove their keys. Mandatory and the two D-60 classification toggles are always stored as booleans. `settingsFor` exposes a flow and `current` awaits its first persisted emission. File, injected-DataStore, and in-memory factories follow the established settings-store seam.

[VERIFIED, TODO 5 / D-60] `TranscodeSettings` also carries `vpnAsCellular` and `meteredAsCellular`, both defaulting to `true` — the user-confirmed semantics that a VPN transport and a metered network take the cellular slot — persisted per renderer as `transcode.<renderer-id>.vpn_as_cellular` / `.metered_as_cellular`. Persisted data written before these keys existed decodes them to `true` (the confirmed defaults), and each toggle round-trips independently per renderer without touching other renderers. TODO 6 (D-61) ships the settings rows: `TranscodeSettingsSection` in the shell settings' playback group renders both toggles as Switch rows (button twin + accessibility description per the Phase 5 pattern), driven by `TranscodeSettingsViewModel`, which rewrites the whole renderer object atomically through a mutex so rapid toggles lose no update; the playback `StreamProfileWatcher` reads the same store, so a toggle changes the effective profile mid-track without any coupling to the UI.

[VERIFIED] Constructor writes reject non-positive bitrates and invalid format/renderer tokens. When persisted data bypasses the model, a zero/negative bitrate decodes independently to off and an invalid format decodes independently to no preference; other valid fields, including mandatory, survive. DataStore read failures are not converted into fresh defaults. No Room entity, migration, schema export, request parameter, or credential-bearing value is involved.

## Scope boundaries

[VERIFIED] Task 1 + TODO 5 change only the settings model/store (including the additive D-60 toggles), the `NetworkState` snapshot fields, the pure slot resolver, the `PlannedStreamVariants` slot selection, their Hilt wiring, tests, and records. They do not implement server decision/stream endpoints, device sample-rate policy (D-59 planner rule is separate), data-saver UI, transcoded HTTP downloads, Cast, on-device ffmpeg, or an embedded web server. TODO 6 (D-61) adds only the two D-60 toggle rows in settings (no data-saver UI — data saver is deferred to a dedicated post-Phase-12 phase per D-61).

[VERIFIED] Phase 7 variant identity remains unchanged: optional bitrate/format continue through rule and queue rows, media keys, variant-qualified offline paths, and `offline_media.sourceKey`. Offline-first resolution and public MediaStore export ownership are untouched.

[INFERRED — policy for TODO 3b] D-52 requires transcoded downloads to restart rather than `Range`-resume until live evidence establishes trustworthy validators and lengths. Task 3b must test a chunked response without `Content-Length`, indeterminate progress, and restart-only retry. No such transfer behavior is implemented or claimed in task 1; raw-download resume remains governed by the existing transfer contract.

## Verification

[VERIFIED JVM 2026-11-21] The serial command `./gradlew --no-parallel --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m :core-data:compileDebugKotlin :core-data:testDebugUnitTest :core-data:testReleaseUnitTest :app:assembleDebug` exited 0 with `BUILD SUCCESSFUL`.

[VERIFIED] Result XML records `:core-data` **795 tests / 0 failures / 0 errors / 0 skipped in each variant**. `TranscodeSettingsTest` contributes 6 and `TranscodeSettingsStoreTest` 8 in each variant. The previous module total was 781 per variant. App debug assembly verifies the new Hilt binding. Schema remains v3 with no schema-file change.

[VERIFIED JVM 2026-11-22 — audit D-57] The follow-up serial gate `/tmp/opencode/phase-8-audit-fix-gate.log` exited 0; `/tmp/opencode/phase-8-audit-fix-xml-totals.txt` records `:core-data` **808/0/0/0** per variant, with model 6 and store 9. It covers reserved `auto` rejection, independent valid/invalid decode and deterministic suspended concurrent updates through the mutex-serialized in-memory DataStore.

[VERIFIED JVM 2026-11-22 — TODO 5 / D-60] The serial gate `/tmp/opencode/phase-8-task-5-gate.log` exited 0; `/tmp/opencode/phase-8-task-5-xml-totals.txt` records `:core-data` **814/0/0/0** per variant, with model 7 and store 11 — covering the toggle defaults, per-renderer toggle round-trips, and absent-key decode to the confirmed as-cellular defaults, plus the three new `ConnectivityManagerNetworkMonitorTest` metered/VPN cases.

[UNKNOWN] No device or live server was exercised. Actual transcoding, device-reported network/capability values, renderer behavior beyond persistence isolation, transcoded response lengths/validators, playback seeking, and UI remain with their later tasks and evidence gates.
