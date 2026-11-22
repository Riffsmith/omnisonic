# Subsonic server-transcode request paths — `subsonic-transcode-requests.md`

Purpose: Pins down how the Subsonic/Navidrome provider turns a planned server transcode into wire requests: `stream.view` rate/format/seek parameters, the probe-gated `getTranscodeDecision` → `getTranscodeStream` pair, and the three plain-stream fallback paths with redacted logged reasons.

How derived: Phase 8 task 3a design (D-53 planner output consumed at the provider seam, D-54 request/fallback contract), the OpenSubsonic extension probe already used for capabilities (`getOpenSubsonicExtensions`), and MockWebServer tests in `SubsonicTranscodeStreamTest` / `TranscodeStreamDefaultTest`.

Confidence: request shapes and fallback routing are [VERIFIED JVM]; live-server negotiation, actual transcoded bytes and range/seek behavior on a real server are [UNKNOWN] (no live trace in this environment).

Date: 2026-11-22

Links: `../decisions.md` D-53/D-54; `../playback/transcode-planner.md`; `../playback/transcode-settings.md`; `../playback/stream-resolution.md`; `../providers/credential-handling.md`; `provider-navidrome/src/main/java/app/omnisonic/provider/navidrome/SubsonicClient.kt`; `provider-api/src/main/java/app/omnisonic/provider/api/TranscodeStream.kt`; `.agents/plans/phase-8-tasks.md` TODO 3a.

---

## Seam

`MediaProvider.resolveTranscodeStream(externalId, maxBitRateKbps, format, timeOffsetSeconds)` is the provider seam where the pure planner's `ServerTranscode(maxBitRateKbps, format)` output becomes request parameters. A null bitrate/format means "server default"; nothing is invented here (the 192 kbps download default belongs only to TODO 3b). The default interface implementation returns the plain `streamUrl(...)` with `serverTranscoded = false` and no fallback reason, so providers without the extension (including the local provider, whose traits already report `supportsServerTranscode = false`) need no override. The result is `TranscodeStreamResolution(url, serverTranscoded, fallbackReason)`; `url` keeps the provider's signed query parameters exactly like `streamUrl`, and signed URLs/credentials never enter rows, logs, planner outputs or UI errors.

## Plain `stream.view` parameters

- `stream.view?id=<id>` always carries the auth parameters plus protocol line (`u/t/s` or `p` or `apiKey`, `v`, `c`, `f=json`).
- `maxBitRate=<kbps>` and `format=<token>` are added when non-null — the planner's explicit values land here (and on `download.view`, unchanged, for the existing download path).
- `timeOffset=<seconds>` is added only when the probe advertised the `transcodeOffset` OpenSubsonic extension and the requested offset is positive. Zero/negative offsets are omitted. [VERIFIED JVM]
- Since Phase 8 TODO 6 (D-61) the same seam serves online playback: `PlannedProviderStreamResolver` calls `resolveTranscodeStream` with the planned slot's `maxBitRateKbps`/`format`, and a mid-track re-request (`TrackChangeCause.REPLAN`) passes the current song-relative position as `timeOffsetSeconds`. When the server bakes that offset into the stream, the resolver returns `startPositionMs = 0` and `QueuePlayerImpl` adds its position base, so player ticks/seeks stay song-relative. The re-request URL differs only by the offset parameter; signed URLs/credentials still never enter rows, logs or UI (D-58). [VERIFIED JVM] Request shapes; live-server offset behavior `[UNKNOWN]`.

## Probe-gated decision flow

- The `getOpenSubsonicExtensions` probe result (`ProviderCapabilities.extensionNames`) gates the flow: without the advertised `transcoding` extension no decision call is made and the request lands directly on the plain stream with `fallbackReason = EXTENSION_ABSENT`. [VERIFIED JVM]
- With the extension, `getTranscodeDecision.view?mediaId=<id>&mediaType=song` is called through the same failover/auth machinery as every other endpoint. The response's `transcodeDecision` carries `canDirectPlay`, `canTranscode`, `transcodeReason[]`, `errorReason` and the opaque `transcodeParams` string; only `canTranscode` + `transcodeParams` are consumed.
- On `canTranscode = true` with a non-blank `transcodeParams`, the stream URL is `getTranscodeStream.view?mediaId=<id>&mediaType=song&transcodeParams=<opaque>[&offset=<s>]`, with `offset` (seconds, positive only) for seeking into the server-side transcode session. `maxBitRate`/`format` are [VERIFIED source] absent from both decision-path requests; the opaque params carry the server-chosen target. Consequently, explicit client bitrate/format reach only the plain-stream path. How a live server chooses the negotiated target remains [UNKNOWN] without a live trace.
- Auth failures (HTTP 401/403, Subsonic codes 40–44) still throw `ProviderAuthException`; the plain stream would fail the same way, so no fallback is attempted. [VERIFIED JVM]

## Fallback contract (D-54)

Every non-auth decision failure lands on the plain `stream.view` with the requested `maxBitRate`/`format` (and probe-gated `timeOffset`), with `serverTranscoded = false` and one of three redacted reasons, each logged once as `transcode fallback reason=<token>`:

| `TranscodeFallbackReason` | Trigger |
|---|---|
| `EXTENSION_ABSENT` | probe advertised no `transcoding` extension; no decision call is made |
| `SERVER_DECLINED` | decision answered `canTranscode: false`, or `canTranscode: true` with blank/missing `transcodeParams` |
| `DECISION_FAILED` | decision call failed: transport, HTTP error, Subsonic error envelope, or malformed JSON |

The logged reason is a fixed token only: server-provided `transcodeReason`/`errorReason` text, URLs, salts, tokens and passwords never reach the log. D-58 removes the throwable formerly attached to `DECISION_FAILED`, because its cause chain could contain server response text or transport URLs even after credential-pattern scrubbing. Fallback logging now passes no throwable to any sink. [VERIFIED JVM]

## Verification

[VERIFIED JVM 2026-11-22] Serial `./gradlew --no-parallel --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m :provider-api:compileDebugKotlin :provider-api:compileReleaseKotlin :provider-api:testDebugUnitTest :provider-api:testReleaseUnitTest :provider-navidrome:compileDebugKotlin :provider-navidrome:compileReleaseKotlin :provider-navidrome:testDebugUnitTest :provider-navidrome:testReleaseUnitTest :app:assembleDebug` exited 0 (`/tmp/opencode/phase-8-task-3a-gate.log`). XML: `:provider-api` **68/0/0/0** and `:provider-navidrome` **54/0/0/4** in each variant (the 4 skips are the pre-existing env-gated live Navidrome cases); `SubsonicTranscodeStreamTest` contributes 11 named cases and `TranscodeStreamDefaultTest` 1, all present in the result XML (`/tmp/opencode/phase-8-task-3a-xml-totals.txt`). Banned-name scan exit 0 over all touched files; new public names have no `theme_keys.json` / `THEMING.md` §4 collision.

[VERIFIED JVM 2026-11-22 — audit D-58] The corrective serial gate `/tmp/opencode/phase-8-audit-fix-gate.log` exited 0 with the same module totals per variant (`/tmp/opencode/phase-8-audit-fix-xml-totals.txt`). The 11 provider cases now traverse complete log cause chains, require one token-only fallback line on every failure route, cover negative offsets and prove negotiated positive offset without `transcodeOffset`.

## Scope boundaries

[VERIFIED] Task 3a changes only the provider seam and the Navidrome client: no planner policy change, no download/offline variant integration, no 192 kbps default, no network policy, no player/resolver change, no UI, no schema change. `OfflineFirstStreamResolver` still desires `StreamKey(providerId, externalId)` (`auto:auto`); variant-qualified rule/queue/media-key/file/source-key identity and public-export ownership are untouched.

[UNKNOWN] Live-server behavior of `getTranscodeDecision`/`getTranscodeStream` (extension advertisement, decision payloads, transcoded bytes, `offset` seek semantics and server target selection) remains gated on a live trace. The request shapes above follow the published OpenSubsonic extension contract and MockWebServer coverage only. `NavidromeLiveAcceptanceTest` does not exercise either transcode endpoint, and its four live cases remain environment-gated skips in this environment. Explicit bitrate/format omission from the decision path is instead a [VERIFIED source] fact, not an unknown.
