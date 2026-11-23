# Phase 8 transcode acceptance and close — `transcode-acceptance.md`

Purpose: Consolidate the Phase 8 TODO 7 serial gate, the assertion-level evidence for every Phase 8 acceptance item, the schema and clean-room audits, and the device/live checks that remain open. This document closes Phase 8; it does not start Phase 9.

How derived: One exact serial `./gradlew --no-parallel --no-daemon --max-workers=1 :app:assembleDebug test lint` invocation, per-class JUnit XML parsing, direct reading of the named test assertions and their production paths, inspection of the Room schema exports and migration list, the banned-name scanner, and comparison of new declaration names with `reference/extracted/theme/theme_keys.json` plus `reference/docs/THEMING.md` §4. Device availability was checked with `adb devices`; live-test environment variable presence was checked without printing values.

Confidence:
- [VERIFIED] The build, lint, XML totals, schema/harness and clean-room checks below are green.
- [VERIFIED] The acceptance evidence names concrete assertions against production paths; it is not a claim that Robolectric, MockWebServer or temporary files emulate a device or a live server.
- [UNKNOWN] Every item under Device and live acceptance remains open for the stated reason.

Date: 2026-11-22

Links: `transcode-settings.md`, `transcode-planner.md`, `stream-resolution.md`, `stream-cache-identity.md`, `offline-resolution.md`, `../protocol/subsonic-transcode-requests.md`, `../providers/provider-connections.md`, `../storage/download-transfer.md`, `../decisions.md` (D-51…D-62), `.agents/plans/phase-8-tasks.md` TODO 7.

---

## Starting state and exact gate

[VERIFIED] All Phase 8 work is committed on branch `main` at `3392e04` (53 files: modified tracked paths plus new implementation, test and documentation paths); nothing is left uncommitted in the working tree. Committed: yes. Pushed: no.

[VERIFIED] The exact invocation exited 0 with `BUILD SUCCESSFUL`. Its complete output is `/tmp/opencode/phase-8-task-7-gate.log`; parsed totals are `/tmp/opencode/phase-8-task-7-xml-totals.txt`. Numbers are `tests / skipped / failures / errors` from the result XML, not task-success inference.

| Module | Debug | Release |
|---|---:|---:|
| `:app` | 171 / 0 / 0 / 0 | 41 / 0 / 0 / 0 |
| `:core-data` | 817 / 0 / 0 / 0 | 817 / 0 / 0 / 0 |
| `:core-model` | 26 / 0 / 0 / 0 | 26 / 0 / 0 / 0 |
| `:core-network` | 25 / 0 / 0 / 0 | 25 / 0 / 0 / 0 |
| `:feature-downloads` | 31 / 0 / 0 / 0 | 31 / 0 / 0 / 0 |
| `:feature-library` | 145 / 0 / 0 / 0 | 145 / 0 / 0 / 0 |
| `:feature-search` | 26 / 0 / 0 / 0 | 26 / 0 / 0 / 0 |
| `:feature-settings` | 76 / 0 / 0 / 0 | 76 / 0 / 0 / 0 |
| `:playback` | 452 / 0 / 0 / 0 | 452 / 0 / 0 / 0 |
| `:playback-api` | 13 / 0 / 0 / 0 | 13 / 0 / 0 / 0 |
| `:provider-api` | 68 / 0 / 0 / 0 | 68 / 0 / 0 / 0 |
| `:provider-local` | 12 / 0 / 0 / 0 | 12 / 0 / 0 / 0 |
| `:provider-navidrome` | 54 / 4 / 0 / 0 | 54 / 4 / 0 / 0 |
| `:style` | 55 / 0 / 0 / 0 | 55 / 0 / 0 / 0 |
| **Total** | **1,971 / 4 / 0 / 0** | **1,841 / 4 / 0 / 0** |

The grand total is **3,812 results, 8 skips, 0 failures and 0 errors**. Phase 8 suites (debug, all duplicated in release unless noted) are `TranscodeSettingsTest` 7, `TranscodeSettingsStoreTest` 11, `DownloadTranscodeRulesTest` 4, `DownloadTransferEngineTest` 56, `DownloadExportTransferTest` 7, `ConnectivityManagerNetworkMonitorTest` 9, `ConnectivityManagerNetworkStateObserverTest` 3, `TranscodePlannerTest` 67, `PlannedStreamVariantsTest` 14, `TranscodeSlotResolverTest` 13, `PlannedProviderStreamResolverTest` 18, `StreamProfileWatcherTest` 8, `OfflineFirstStreamResolverTest` 16, `QueuePlayerImplTest` (replan cases included), `PlayHistoryRecorderTest` (REPLAN case included), `PlaybackControllerImplTest` (REPLAN case included), `TranscodeStreamDefaultTest` 1, `SubsonicTranscodeStreamTest` 11, `TranscodeSettingsViewModelTest` 6 and `TranscodeSettingsRowsTest` 2 (debug only, documented shell exclusion). `:feature-downloads` was additionally re-executed for both variants with `--rerun-tasks --no-build-cache` (exit 0, debug and release 31/0/0/0) to put the corrected runtime test on a real run rather than an up-to-date result; log `/tmp/opencode/phase-8-task-7-gate-feature-downloads.log`.

The `:app` debug/release delta is the documented release `**/shell/**` exclusion in `app/build.gradle.kts`; all other module totals match across variants. The four skips per `:provider-navidrome` variant are the pre-existing gated live-server acceptance cases, unchanged by Phase 8.

## Schema, migration harness and clean-room audit

[VERIFIED] `OmnisonicDatabase.SCHEMA_VERSION = 3`; `Migrations.ALL = listOf(MIGRATION_1_2, MIGRATION_2_3)` in that order and no other migration. The exported `1.json`, `2.json` and `3.json` are unchanged from `HEAD` (`git status --short core-data/schemas` is empty), and Phase 8 added no entity, DAO or column. Every Phase 8 preference (`TranscodeSettings` bitrates, `mandatory`, `preferredFormat`, `vpnAsCellular`, `meteredAsCellular`, renderer namespacing) lives in the `omnisonic_transcode` Preferences DataStore, so no schema migration was required. `MigrationHarnessTest` remains part of the `:core-data` gate totals above.

[VERIFIED] `scripts/check-banned-names.sh` exited 0 over every Phase 8 touched path: every modified tracked file plus every untracked implementation, test and documentation path, including this acceptance document. The final scan log is `/tmp/opencode/phase-8-task-7-banned.log`.

[VERIFIED] The declaration diff from the pre-Phase-8 baseline `7a8bfaedc16f1cc936090b6b8c487d833f3e3240` yields 415 new declaration/identifier names (including local `val`/`var`/parameter names). There is **no exact and no case-insensitive** collision with any `theme_keys.json` string. The only `THEMING.md` §4 overlaps are generic English/API words (`body`, `cast`, `data`, `first`, `name`, `next`, `other`, `plain`, `plan`, `playing`, `prefs`, `previous`, `provider`, `queue`, `resolved`, `seen`, `settings`, `tokens`), not distinctive kept-name model or config names. The list is `/tmp/opencode/phase-8-task-7-public-names.txt`. The `cast` renderer id exists only as a generic second renderer in settings-persistence tests (D-51 renderer independence); no Cast implementation exists.

## JVM acceptance evidence

### Pure planner table with at least 40 cases [VERIFIED JVM]

`TranscodePlannerTest` runs **67** cases in each variant. Most are a named table asserting a full `TranscodeDecision` (direct play, explicit/absent transcode parameters, typed refusal) from source format/bitrate/sample-rate, renderer capabilities, per-renderer settings, provider traits and the request slot; `table has at least 40 uniquely named cases` proves the table itself never shrinks below the roadmap threshold, and `plan is deterministic for every table case across repeated calls` re-runs every table case. `local playback renderer id maps to the local settings renderer id` pins the seam between the playback renderer id and `TranscodeRendererId.Local`. `PlannedStreamVariantsTest` (14) covers the offline desired-variant projection of the same planner.

### Cellular request carries the cellular bitrate [VERIFIED JVM]

`PlannedProviderStreamResolverTest > cellular network picks the cellular bitrate` plans over `NetworkState(wifi = false, metered = true)` with Wi-Fi 320/cellular 96 and asserts the provider seam receives `maxBitRateKbps = 96`. `wifi slot sends the wifi bitrate through the transcode seam with a variant cache key` asserts 320 on Wi-Fi, and the VPN/metered cases assert 96 by default and 320 when the user opts out. `TranscodeSlotResolverTest` (13) pins the pure slot rule, including offline→cellular and that `DOWNLOAD` is never produced; `PlannedStreamVariantsTest` (14) pins the offline variant carrying the cellular/wifi value with no cross-slot or cross-renderer leak.

### Three decision-fallback cases land on the plain stream [VERIFIED JVM]

`SubsonicTranscodeStreamTest` (11) proves each path separately with MockWebServer: `extension absent lands on the plain stream with rate format and seek offset` (no decision request is made), `server declined lands on the plain stream with a redacted logged reason` (`canTranscode: false`; `canTranscode without usable params is treated as declined` covers blank params), and `decision error response lands on the plain stream with a redacted reason` plus `decision malformed json lands on the plain stream with a redacted reason` and `decision http error lands on the plain stream with a redacted reason`. Every fallback asserts the plain `stream.view` URL with the requested `maxBitRate`/`format`/`timeOffset` and exactly one `transcode fallback reason=<fixed token>` log line, with server body text absent. `decision auth failure still throws instead of falling back` keeps auth errors fatal (D-58 fixed-token-only logging). In the resolver, `a failed transcode request falls back to the plain stream` and `a refused transcode falls back to the plain stream` cover the caller side.

### 192 kbps download default [VERIFIED JVM]

`DownloadTranscodeRulesTest > a format-only transcode defaults to 192 on the wire request` asserts `requestBitrateKbps = 192` while `isTranscoded` is true and identity fields are untouched; `an explicit bitrate is kept on the wire request`, `an explicit bitrate and format are both kept` and `a raw auto variant requests no bitrate and is not transcoded` bound the rule. At the transfer seam, `DownloadTransferEngineTest > a format-only transcode defaults to 192 on the wire without changing identity` asserts the actual request query and unchanged media key/path, and `an explicit transcode bitrate is kept on the wire request` / `a raw download requests no transcode parameters` prove the other two branches on the wire.

### Chunked transcoded download restart-only behavior (D-52) [VERIFIED JVM]

`DownloadTransferEngineTest > a chunked transcoded body pauses indeterminately and restarts on retry` uses a chunked/no-`Content-Length` MockWebServer body, asserts indeterminate (`-1`) progress through a pause, and requires the retry to restart at byte zero with no `Range`/`If-Range`. `a transcoded retry restarts from zero instead of sending a range`, `a transcoded part is restarted instead of range resumed` and `an unknown length streams to EOF and finalizes` complete the picture; `a transcoded failure keeps the signed url out of the row error` covers credential hygiene. `DownloadExportTransferTest > a transcoded export row restarts without creating offline files` applies the same rule to exports. Raw resume is unchanged and still asserted: `a valid 206 appends to the sidecar-validated part`, `a 206 without Content-Range restarts from zero once`, `a changed entity tag on 206 restarts instead of appending` and the pre-existing 416 case's raw half.

### Device sample-rate limit boundaries (D-59) [VERIFIED JVM]

`TranscodePlannerTest` boundary cases are `sample rate at the device limit direct-plays`, `sample rate below the device limit direct-plays`, `sample rate above the device limit forces a server transcode`, `a very high sample rate above the limit forces a server transcode`, `missing device limit cannot prove a sample-rate exceedance` and `missing source sample rate cannot prove a mismatch against a device limit`. Combination cases cover explicit bitrate/format, mandatory mode, provider refusal (`RENDERER_CANNOT_PLAY_SOURCE`), unknown renderer formats, the download slot, and an empty capability set. The planner reads no device API, so device-reported capability values are not claimed.

### Server-transcode seek/offset [VERIFIED JVM]

`SubsonicTranscodeStreamTest > decision success returns the transcode stream with opaque params and seek offset` asserts `getTranscodeStream.view` with the opaque `transcodeParams` and `offset`; `decision stream offset is positive only and independent of the plain offset extension`, `seek offset is omitted without the offset extension or when not positive`, `non-positive offsets are omitted even with the offset extension probed` and `zero offset is omitted even with the offset extension probed` pin the positive-only contract. `PlannedProviderStreamResolverTest > a positive start position becomes a positive-only time offset and a zero stream start` and `a sub-second or negative start position is not sent as an offset` pin the caller contract, and `QueuePlayerImplTest > a resolver that bakes the offset into the request keeps positions song-relative` proves the position base (ticks and seeks stay song-relative). Playback against a live server's actual seek target remains `[UNKNOWN]`.

### TODO 6 mid-track re-request [VERIFIED JVM]

`QueuePlayerImplTest > replanCurrentStream re-requests the same entry at the current position without a skip` asserts the unchanged queue entry/index, `startPositionMs = 42000`, a `REPLAN` `TrackChanged` with the same previous/current entry, no skip event and the preserved state position. `a superseded replan leaves the armed position for its successor`, `a replan whose re-request fails bounces to the next entry like any start failure` and `replanCurrentStream is a no-op while idle` / `... for a radio station` / `... after release` bound the behavior. `PlayHistoryRecorderTest > a same-entry REPLAN re-request keeps the open span and counts one play` and `PlaybackControllerImplTest > a REPLAN that bounced to another entry re-arms the scrobble point` (RED-confirmed by D-62) cover history/scrobble bookkeeping. `StreamProfileWatcherTest` (8) proves a changed plan decision replans exactly once, including Wi-Fi↔cellular, settings edits and a `vpnAsCellular` toggle, while same-slot network flaps, non-playing and idle queues do not.

### TODO 6 offline no-restart [VERIFIED JVM]

`QueuePlayerImplTest > replanCurrentStream is a no-op for a direct offline stream` plays a `file://` direct URI, ticks to 42s, calls replan and asserts the player was started only once with no second `TrackChanged`. `OfflineFirstStreamResolverTest` positioned cases prove the offline file still wins before the online planner and reports the requested position back, so a re-request would be a seek rather than a restart. `PlannedProviderStreamResolverTest > a content uri resolves as a direct offline-capable source` and the resolver's radio cases close the direct-URI paths.

### TODO 6 VPN/metered settings rows [VERIFIED JVM]

`TranscodeSettingsViewModelTest` (6) asserts absent keys decode to both-on, persisted values build the initial state, each toggle rewrites the whole renderer object (other bitrates and the sibling toggle survive), rows stay independent of another renderer, and rapid interleaved toggles lose no update. `TranscodeSettingsRowsTest` (2, debug only under the documented shell exclusion) composes the real `ShellSettingsScreen` and asserts both rows render as labelled switches that are on by default with their titles as accessibility descriptions, then clicks each switch and asserts the persisted `TranscodeSettings` (one toggle flipped, the sibling untouched). The section is composed in the shell playback group and the strings are owned by `:feature-settings`.

## Test-harness defect and coverage gap fixed during wrap-up

[VERIFIED JVM] The audit found no product/decision/identity defect. It did find that TODO 6's settings-row coverage was view-model only, so `TranscodeSettingsRowsTest` (2 cases) was added and now composes the real screen, asserts the accessible switch labels/descriptions and asserts the clicks persist the whole renderer object.

[VERIFIED JVM] The full `test` gate exposed a pre-existing Phase 7 flake, `:feature-downloads` `ForegroundDownloadRuntimeTest > a worker failure does not cancel its sibling and the failed row retries`: it advanced the virtual clock by exactly 10 s once, but the runtime arms its retry wait only after the failure is recorded, so a late arm could start the 10 s backoff after the advance and never reach its due time (reproduced 2 of 3 class runs; the single test passed in isolation). It was fixed at the cause by letting `pumpUntil` advance virtual time in 1 s steps while pumping, so the retry due time is reached whenever it is armed. All assertions are unchanged (three claims, retry completion, both rows finalized, lock release parity), and the class then passed three consecutive runs. No production behavior changed and no test was skipped or weakened.

## Device and live acceptance [UNKNOWN]

- **Audible mid-track transition and real network transitions:** `adb devices` listed no attached device, so no playback or transport trace exists.
- **Device-reported codec/sample-rate capabilities and audible downsampling:** no device was available; the planner consumes caller-supplied capabilities only.
- **Live Navidrome/OpenSubsonic negotiation, transcoded bytes, seek target and throughput:** `OMNISONIC_NAVIDROME_URL`, `OMNISONIC_NAVIDROME_USER` and `OMNISONIC_NAVIDROME_PASS` were unset, so no live trace exists. The request/fallback contract is proven against MockWebServer fixtures only.
- **Live transcoded `Content-Length`/validator behavior:** no live server was available; the restart-only policy (D-52) stays conservative until such evidence exists.
- **TalkBack, row order/legibility and layout:** no device or accessibility run was available; the rows are composed and their descriptions asserted in Robolectric only.
- **VPN/metered capability reporting on hardware:** no device was available; `NetworkState.metered`/`vpn` are exercised through fakes and Robolectric capability shadows.

Data saver has no Phase 8 acceptance item: it was deferred by the user to a dedicated post-Phase-12 phase (D-61), and no data-saver artifact exists in the tree.

## Phase 9 boundary

[VERIFIED] Phase 8 leaves the playback and data seams intact: `MediaProvider.resolveTranscodeStream` is the single transcode request seam, `StreamResolver.resolve(entry, startPositionMs)` carries the re-request position, planner purity is preserved, offline-first resolution and variant-qualified identity (`DownloadKeys.mediaKey`, `StreamKey`/`cacheKey`, `offline_media.sourceKey`) are unchanged, and preferences remain DataStore-only at schema v3. Phase 9 (JSON style system) needs no change to any of these; it is gated on the R-7 decision, which is already resolved as "support loading third-party style JSON via a converter", with the sample files still to be placed.
