# SESSION 3 — T8–T12 (Gate A completion + Gate B)

Date: 2026-10-31

Scope: T8, T9, T10, T11, T12 from Prompt.md. Stopped at the T8–T12 checkpoint as
instructed; T13+ not started.

Decisions applied (from `SESSION-2-DECISIONS.md` §6, all confirmed by the user):
- **T8 = (c)**: global cleartext allow + in-app warning for http on non-private hosts.
- **T10 = (A)**: tombstone pruning with the three recommended rules (playCount>0 OR
  pending outbox row keeps the song as `missingOnServer=true`; isFavorite does NOT
  block deletion; albums/artists pruned when no live children remain; playlists
  pruned outright).
- **T11/D-8 = (a)**: hint-based default (`auto` = ON only for `navidrome`,
  unknown/absent hint OFF) + per-provider `auto|on|off` override.

---

## T8 — Manifest and network policy

- Problem confirmed? **Partially.** FGS base permission missing and no NSC declared:
  [VERIFIED] by manifest inspection and a failing test. The on-device cleartext
  failure itself could not be reproduced on the desktop JVM: a Robolectric probe
  showed `NetworkSecurityPolicy` stays permissive under Robolectric 4.14.1 even
  with targetSdk 36 and no NSC (the framework's config-driven policy install does
  not run), so a Robolectric *behavior* test cannot distinguish the policy. The
  JVM tests therefore pin the *declared* policy instead.
- Files changed: `app/src/main/AndroidManifest.xml` (added
  `android.permission.FOREGROUND_SERVICE`, `android:networkSecurityConfig`),
  `app/src/main/res/xml/network_security_config.xml` (base-config
  cleartextTrafficPermitted=true), `provider-api/.../CleartextHostPolicy.kt`
  (pure host classifier: RFC1918 + CGNAT + loopback + link-local IPv4, IPv6
  ULA/link-local/::1, IPv4-mapped IPv6, localhost/*.localhost, *.local),
  `docs/omnisonic/network/cleartext-policy.md` (warning call-site contract).
- Test added: `app/.../NetworkPolicyManifestTest` (3 tests: config content,
  manifest attribute, FGS permission — all failed before the fix, shown);
  `provider-api/.../CleartextHostPolicyTest` (10 tests, failing state = unresolved
  reference before implementation).
- Result: `./gradlew :app:testDebugUnitTest --tests '...NetworkPolicyManifestTest'`
  → 3/3 pass; `:provider-api:testDebugUnitTest` → pass; `:app:lintDebug` → pass.
- Commit: **454a6fa** `fix(T8): permit cleartext http and add private-host classifier`
- Unresolved: on-device enforcement proof needs an adb-connected device (manual
  check per Prompt.md T8; device was offered but not connected this session). The
  warning UI call-site lands with the profiles screen; classifier + contract ready.

## T9 — Wake mode for streaming

- Problem confirmed? **Yes.** `ExoEngineFactory` hardcoded
  `C.WAKE_MODE_LOCAL`; Media3 1.3.1 bytecode confirms LOCAL disables the wifi lock
  (`wifiLockManager.setEnabled(wakeMode == WAKE_MODE_NETWORK)`), so long Wi-Fi
  streaming can stall with the screen off.
- Files changed: `playback-api/.../AudioEngine.kt` (new `WakeMode` enum +
  `EngineConfig.wakeMode`, default NETWORK), `playback/.../ExoEngineFactory.kt`
  (mapping), `docs/omnisonic/playback/wake-mode.md`.
- Test added: `playback/.../WakeModeTest` (default selects NETWORK; NETWORK engine
  acquires the wifi lock on playback and releases the wake lock on pause; LOCAL
  never creates a wifi lock). Failing state: `Unresolved reference 'WakeMode'` +
  `No parameter with name 'wakeMode'` before implementation. Shadow note: the
  wifi-lock count assertion uses the build-to-playing *delta* because Robolectric's
  `ShadowWifiLock` counts the constructor's initial release (documented in the doc).
- Result: `:playback:test` → all pass (WakeModeTest 3/3). `./gradlew :app:assembleDebug` → pass.
- Commit: **1b94084** `fix(T9): default engines to network wake mode`
- Unresolved: real-device screen-off streaming check (needs device).

## T10 — Sync transactions, merge safety, deletions

- Problem confirmed? **Yes** (all six evidence points):
  1. One global transaction wrapped the whole walk → reproduced by a test that
     enqueues an outbox write while a sync is parked in `getAlbum`; it blocked
     (fail message "outbox enqueue blocked behind the sync transaction") before
     the fix and completes mid-sync after it.
  2. Album/song rebuild-and-REPLACE reset local fields → reproduced by
     "album and song local-only fields survive a full sync" (isAudiobook,
     description, resumeFilePath, discTitlesJson, isCompilation, clips, replay
     gain were reset before the fix).
  3. Server bookmark clobbered local resume state → reproduced by
     "server bookmark applies only when newer..." (before: always overwritten).
  4. titleRoman/sortTitle never computed → new test asserts sortTitle "bjork" /
     "johansson" after sync (was null before).
  5. No pruning → tests for full-sync pruning failed before the fix.
  6. Two requests per album → request-count tests (N albums → N getAlbum + 1 page)
     failed before (2N).
- Files changed: `core-data/.../sync/SubsonicLibrarySyncer.kt` (rewritten),
  Song/Album/Artist DAOs (INSERT OR IGNORE + column-scoped `updateServerFields`,
  `selectUnseenDeletableIds`, `tombstoneUnseen`, `deleteUnseenWithoutLinks`,
  `selectUnseenWithoutSongsIds`, `deleteUnseen` for playlists), SongEntity
  (+`resumeUpdatedAt`, +`missingOnServer`), OutboxDao pending-count queries,
  Genre/Radio/Playlist `(providerId, externalId)` lookups, SongArtist/AlbumArtist
  link cleanup, `provider-api` `ProviderAlbumDetails` + `MediaProvider.getAlbum`
  (replaces albumDetails; browseDirectory kept), SubsonicClient/LocalMediaProvider
  implementations, `core-model/.../text/TextNormalizer.kt` (ICU transliteration if
  available, else NFD + strip marks), regenerated `core-data/schemas/.../1.json`
  (pre-release v1 redefinition; the standalone migration ships with T16 per the
  one-migration rule), docs `storage/library-sync-ownership.md`.
- Test added: `SubsonicLibrarySyncerMergeTest` (12 tests: transaction
  concurrency, local-field survival, 3 resume-merge cases, 3 pruning/tombstone
  cases, diff-sync no-prune, tombstoned song keeps album, playlist pruning,
  request-count), `SyncColumnOwnershipTest` (every column of Song/Album/Artist
  classified SERVER/LOCAL/KEY; new columns fail until classified),
  `SubsonicLibrarySyncerTest` updated (getAlbum fake, resume-conservative
  expectation, single-request paging assertion, romanization test),
  `TextNormalizerTest` (4 tests).
- Result: `:core-data:test`, `:provider-api:test`, `:provider-navidrome:test`,
  `:provider-local:test`, `:core-model:test` → all pass.
- Commit: **acc254d** `fix(T10): scope sync writes and prune with tombstones`
- Unresolved: live-server pruning verification (needs a real server).

## T11 — Scrobble correctness and capability detection (D-8)

- Problem confirmed? **Yes.** formPost/transcodeOffset had no connection to the
  scrobble `time` parameter [VERIFIED by KDoc + rule inspection]; cold drains sent
  no timestamps (only the E2E test probed manually); `submitPlays` sent one time
  for all N plays; `enqueuePlayCountDelta` had no timestamp and merge kept play 1's
  timestamp for plays 2..N.
- Files changed: `provider-api/.../MediaModels.kt` (`BackdateScrobbles` enum,
  hint-gated `supportsBackdatedScrobbles`, misleading KDoc removed),
  `ProviderConnection.backdateScrobbles`, `MediaProvider.submitPlays(externalId,
  timestamps)`, `Outbox.kt` (`playTimestamps` payload field, legacy-decode fallback,
  lazy one-per-drainer probe before the first play-count task, remaining-timestamps
  progress), `SubsonicClient` (per-timestamp submissions, strictly increasing
  +1 ms on ties, capability-gated `time`), `LocalMediaProvider` signature,
  `core-data` `ProviderConfigMapper`/`OptionsJson` (`backdateScrobbles:
  auto|on|off`), `OutboxQueue.enqueuePlayCountDelta(..., playedAtMs, now)` with
  capped timestamp merge (100, newest kept), docs
  `protocol/scrobble-timestamps.md`.
- Test added: `OutboxDrainEndToEndTest` rewritten (7 tests: exact times t1<t2<t3,
  same-ms +1 ms, cold-drain probes exactly once then scrobbles with time, override
  off omits time, unknown hint conservative, resume-point ordering, partial
  acceptance keeps remaining timestamps, backoff) — all failed against the old
  code; `OutboxDrainerTest` updated + probe-once test + legacy-payload test;
  `OutboxQueueTest` timestamp merge + 100-cap test; `SubsonicClientTest`
  hint/override tests.
- Result: `:provider-api:test`, `:provider-navidrome:test`, `:provider-local:test`,
  `:core-data:test` → all pass.
- Commit: **0b7eff9** `fix(T11): hint-gated backdated scrobbles with per-play timestamps`
- Unresolved: nothing writes `serverSoftwareHint`/`optionsJson` yet, so `auto` is
  OFF in practice until the profiles UI exists (recorded interim gap from the
  decision brief); other-server behavior needs a live server.

## T12 — Failover completion and client lifecycle

- Problem confirmed? **Yes.** `SubsonicProviderFactory.create()` never installed
  `onPreferredBlockChange` so `lastWorkingBlock` was never persisted (DAO method
  existed, zero callers); failover never retried the primary after switching; every
  `create()` built a fresh OkHttpClient.
- Files changed: `provider-api/.../BlockFailover.kt` (2-failure hysteresis,
  `DEFAULT_PREFER_PRIMARY_RETRY_INTERVAL_MS` = 5 min with injected clock, primary
  re-probe after the interval, success resets preference, suspend callback),
  `SubsonicClient`/`SubsonicProviderFactory` (suspend callback wired through),
  `core-data/.../providers/ProviderClientRegistry.kt` (per-providerId cache of
  MediaProvider + MEDIA-profile OkHttpClient; rebuilds on row edit/delete or
  explicit invalidate), `core-data/build.gradle.kts` (+okhttp),
  docs `providers/client-lifecycle.md`.
- Test added: `BlockFailoverTest` rewritten (15 tests: single failure no flip, flip
  after 2 consecutive failures, hysteresis reset, remembered secondary before the
  interval, healthy primary reclaims after interval, failed probe re-arms timer,
  5xx hysteresis, 404/fatal terminal, single block never flips, default interval);
  `ProviderClientRegistryTest` (6 tests: one provider + one client per provider id,
  preference persisted via the real Room DAO, invalidate/edit/delete rebuild rules,
  unknown api → null).
- Result: `:provider-api:test`, `:core-data:test` → all pass.
- Commit: **9b10202** `fix(T12): cache provider clients and complete block failover`
- Unresolved: stream/cover URLs still use the preferred block without failover
  (documented as accepted in Prompt.md T12); long-run LAN/WAN behavior needs a
  live dual-block server.

---

## Session-wide verification

- `./gradlew test` (all modules) + `./gradlew :app:assembleDebug` → BUILD
  SUCCESSFUL; 301 unit tests, 0 failures/errors (JUnit XML aggregate).
- `scripts/check-banned-names.sh` over all 44 non-docs files changed in this
  session → clean.
- Docs updated under `docs/omnisonic/`: network/cleartext-policy.md (new),
  playback/wake-mode.md (new), storage/library-sync-ownership.md (new),
  protocol/scrobble-timestamps.md (new), providers/client-lifecycle.md (new).

## Environment not available (never fabricated)

- No adb device/emulator: T8 on-device cleartext proof, T3 zstd androidTest and
  T5 API-29 instrumented check (pending from Session 1), T9 real screen-off
  streaming all remain unverified on hardware.
- No live server: live Navidrome sync/drain/failover acceptance not run.

## Leftovers observed (not touched, not committed with fix tasks)

- Pre-existing uncommitted work: modified
  `playback-api/.../queue/QueuePlayer.kt` (setQueue gains sourceType/sourceData),
  untracked `playback/.../queue/QueuePlayerImpl.kt` + test, `.gitignore` adding
  `Prompt.md`. Untouched; left for the user's decision.
- `docs/omnisonic/session-reports/` (SESSION-2-DECISIONS.md + this file) committed
  in a separate docs commit so the decision record is durable.
