# SESSION 8 — T18, T19, T20, T21 (Gate D completion: persistence, cancellation, outbox contract, logging/credentials)

Date: 2026-11-03

Scope: exactly T18–T21 from the review prompt, in order, one commit per task,
reproduction test before each fix. T22 was NOT implemented (per instruction:
it was already answered and folded into the schema-v2 migration — see
`SESSION-T16-4-QUEUE-HISTORY.md`, decision `(b)` per-play table + rollup; the
`play_events` table and view exist since commit `d4b517c`). No Phase 4 work
was started.

---

## Summary table

| Task | Problem confirmed? | Reproduction (red) | Commit |
|------|--------------------|--------------------|--------|
| T18 | **Yes** | `writes clear the legacy ordinal keys` failed: `expected: null but was : 1` (ordinal int under the old key) | `a437204` |
| T19 | **Yes** (drain + client sites); syncer site only partially reproducible | `cancelling the drain mid-task ...` failed: a `fail()` row was recorded and the drain returned normally; both SubsonicClient cancellation tests failed: the cancelled body completed normally | `6d936e0` |
| T20 | **Yes** | 4 red drainer tests (empty payload completed, no-delta completed, `track` silently defaulted to SONG, resume-before-play ordering) + 2 red core-data tests (`expected: [song-b, song-c, song-a] but was : [song-a, song-b, song-c]`; `expected: SONG but was : track`) | `6f6af04` |
| T21 | **Yes** | 3 red logger tests (raw throwable to sink); `unreadable credentials leave the provider unserved` failed: `expected: null but was : StubMediaProvider` (empty-password client served) | `9e67f8b` |

## T18 — persist enums by name (`a437204`)

Problem confirmed. `PlaybackSettingsStore` stored `OffloadMode`,
`ReplayGainMode`, `RgNormalisation`, `TransientLossAction` as `.ordinal` ints;
reordering or inserting a constant remaps saved settings.

Fix:

- New `*_name` string keys (`engine.offload_mode_name`,
  `engine.replay_gain_mode_name`, `engine.replay_gain_normalisation_name`,
  `engine.transient_loss_action_name`); resolution by exact enum name; write
  stores the name and REMOVES the legacy int key.
- One-release legacy fallback: with no name key present, an in-range legacy
  ordinal maps back to its entry (the prompt's Fix bullet "add a read fallback
  for old int keys"); an out-of-range ordinal falls back to the default.
- Interpretation note: the prompt's proof line "unknown/legacy value falls
  back to default" is implemented as: unknown NAME → default [VERIFIED], and
  out-of-range legacy ordinal → default [VERIFIED]. An in-range legacy ordinal
  maps (not to the default) because the prompt's Fix bullet requires reading
  old int keys, and dropping the mapping would have required weakening the
  pre-existing `persisted store keeps earlier values` test (forbidden).
- No migration: DataStore keeps both key shapes; nothing is released.

Files: `playback/src/main/.../PlaybackSettingsStore.kt`,
`playback/src/test/.../PlaybackSettingsStoreTest.kt` (5 new tests),
`docs/omnisonic/playback/settings-persistence.md` (new).

## T19 — never swallow CancellationException (`6d936e0`)

Problem confirmed at three of the four prompt sites:

- `OutboxDrainer.drain`: `runCatching { execute(...) }` recorded a `fail()`
  row (attempts + backoff) for a cancelled coroutine and the drain returned
  normally. Red test proved it.
- `SubsonicClient.probe` (extensions fallback) and `submitPlays`
  (per-scrobble fallback): cancellation swallowed → the body completed
  normally (probe with empty extensions / partial batch). Red tests proved
  both. `requestAttempt`'s `runCatching { awaitResponse() }` also wrapped a
  suspend call and was fixed the same way.
- `SubsonicLibrarySyncer.runSync`: [PARTIALLY] — on the JVM the old
  `catch (failure: Exception)` did NOT record a failed sync status, because
  the cancelled coroutine cannot enter `database.withTransaction` (the status
  write is skipped and CancellationException still propagates). The explicit
  rethrow before `catch (Exception)` removes the dependence on that
  incidental behaviour; the guard test
  `cancelling the sync records no failed sync status` pins the contract but
  was green before the fix (documented per VERIFY-FIRST rule 6).

Fix: `runCatchingCancellable` in `:core-model`
(`CoroutineCompat.kt`) rethrows `CancellationException`, captures everything
else; applied at `OutboxDrainer.drain` + `probeOnce`, `SubsonicClient.probe`
/ `submitPlays` / `requestAttempt`, and the syncer catch.

Test-harness note: the first version of the SubsonicClient cancellation tests
deadlocked the test JVM — `MockWebServer.takeRequest()` blocked the single
`runBlocking` thread so the launched probe coroutine never ran. Fixed by
fetching requests on `Dispatchers.IO`. Also, `job.isCancelled` is trivially
true after `cancel()` even when the body swallowed the exception; the tests
therefore assert on body-completion flags and recorded store failures instead.

Files: `core-model/.../CoroutineCompat.kt` (new), `provider-api/.../Outbox.kt`,
`provider-navidrome/.../SubsonicClient.kt`,
`core-data/.../SubsonicLibrarySyncer.kt`, 3 test files,
`docs/omnisonic/protocol/cancellation-contract.md` (new).

## T20 — outbox contract cleanup (`6f6af04`)

All four prompt bullets confirmed red before the fix:

1. **Duplicate payload classes**: core-data `OutboxPayload` vs provider-api
   `OutboxActionPayload` mirrored each other; Moshi ignores unknown/renamed
   fields, so drift completed `handleSimple` with null and dropped the task.
   The existing test `missing payload value drops the task` (asserting
   `completed = 1` for an empty payload) codified the silent-loss defect and
   was rewritten to the corrected contract (`parks as failed`), not weakened.
   Fix: single `provider-api.OutboxActionPayload`; core-data imports it; the
   mirror is deleted. Golden JSON round-trip test pins the field names.
2. **Media-type vocabulary**: `PlayCounter`/`FavoritesRepository` enqueued
   `"track"` while the drainer parsed `ProviderMediaType` names; the unique
   index includes mediaType, so the same item could get duplicate rows. Red:
   stored column was `track`, not `SONG`. Fix: `enqueueMediaChange` /
   `enqueuePlayCountDelta` take `ProviderMediaType` and store `.name`; the
   drainer resolves strictly and parks unknown values as FAILED
   (`unknown media type: <raw>`) instead of silently defaulting to SONG.
3. **Due ordering**: `selectDue` ordered by `nextAttemptAt` only. Red:
   ties returned in insertion order. Fix: `ORDER BY nextAttemptAt, createdAt, id`
   on `selectDue`, `selectAllDue`, `selectByProvider`.
4. **Per-item action ordering**: the drainer now groups due tasks by
   `(providerId, mediaType, externalId)` and executes each group in
   play_count → favorite → user_rating → resume_point order; groups keep
   their relative due order. Red test proved resume-before-play under the old
   code.

Empty payload handling: a known action with no usable value parks the row as
FAILED with `lastError = "empty payload"` (same backoff path); play_count
without a positive delta or timestamps parks the same way; never COMPLETED.

Files: `provider-api/.../Outbox.kt` (`OutboxActions` object + drainer),
`core-data/.../OutboxQueue.kt` (rewritten to shared types),
`OutboxDao.kt`, `PlayCounter.kt`, `FavoritesRepository.kt`,
`SubsonicLibrarySyncer.kt`, 6 test files,
`docs/omnisonic/storage/outbox-contract.md` (new).

## T21 — logging and credential handling (`9e67f8b`)

Problem confirmed:

- `OmnisonicLogger.write` scrubbed the message but passed the RAW `Throwable`
  to the sink — a Logcat sink would print unredacted exception text. Red:
  sink received the untouched error.
- `ProviderConfigMapper` returned `SecretPayload()` for an unsealable
  `secretBlob`, producing a silently empty-password connection. Red: the
  registry served a provider for a corrupt blob row.

Fix:

- `OmnisonicLogger` now hands sinks a scrubbed wrapper: redacted message,
  ORIGINAL stack trace, redacted cause chain (depth-capped at 8). Contract
  change: the log message no longer concatenates `error.toString()`; the
  scrubbed throwable carries the detail (the `scrubsExceptionText` test was
  updated to the corrected shape, and new tests pin message/stack/cause
  guarantees).
- `ProviderConfigMapper.from` returns sealed
  `ProviderLoadResult { Loaded | CredentialsUnreadable | Unloadable }`;
  a present-but-unsealable secret blob → `CredentialsUnreadable(providerId)`;
  an absent blob still loads (credential-free rows keep working); structural
  unloadability keeps the old null semantics as `Unloadable`. All 24 existing
  mapper call sites in tests updated mechanically.
- `ProviderClientRegistry` refuses to serve such providers and reports the
  id through a new `onCredentialsUnreadable` callback seam — the app wiring
  connects it to `ProviderStatusTracker.markCredentialsUnreadable`
  (tracker AUTH_FAILED behaviour already existed and stayed green; test
  `unreadableCredentialsMarkAuthFailedUntilCleared`).
- `:app` gained `AndroidLogSink` (Log.d/i/w/e mapping) with a Robolectric test
  proving level mapping and that only the scrubbed throwable reaches Logcat.
- `core-data.BackupExcludes.providerColumns` = `{ secretBlob, mtlsKeystoreBlob,
  trustedCertsBlob }` with tests asserting the required entries AND that every
  entry names a real `ProviderEntity` column (renames fail the test). Phase 6
  backup rules must consult this list.

Files: `core-model/.../OmnisonicLogger.kt` + test,
`core-data/.../ProviderConfigMapper.kt`, `ProviderClientRegistry.kt`,
`BackupExcludes.kt` (new) + 3 test files, `app/.../log/AndroidLogSink.kt`
(new) + test, `docs/omnisonic/providers/credential-handling.md` (new).

## Test results

- Per-task runs during the session: red runs shown above; green runs for
  `:playback`, `:core-model`, `:provider-api`, `:provider-navidrome`,
  `:core-data`, `:app` after each fix.
- Full verification after T21: `./gradlew clean test :app:assembleDebug` →
  **BUILD SUCCESSFUL in 1m 45s** — 886 tests, 0 failures, 0 errors, 8 skipped
  (skips pre-existing, live-server acceptance tests).
- `./gradlew lint` → BUILD SUCCESSFUL.
- `scripts/check-banned-names.sh` over `git ls-files` → clean.
- APK: `app/build/outputs/apk/debug/app-debug.apk` produced.

## Commits

- `a437204` fix(T18): persist playback enums by stable name with a legacy ordinal fallback
- `6d936e0` fix(T19): rethrow CancellationException from suspend-call error handling
- `6f6af04` fix(T20): consolidate the outbox payload and vocabularies with deterministic ordering
- `9e67f8b` fix(T21): scrub logged throwables, surface unreadable credentials, add logcat sink and backup excludes

## Unresolved / notes for the final gate

- The syncer cancellation site (T19) is only partially reproducible on the
  JVM; the guard test pins the contract. [UNKNOWN] whether a real Android
  environment could observe the old catch-all recording a status row.
- `ProviderClientRegistry.onCredentialsUnreadable` defaults to a no-op until
  app-level DI wiring exists (no Hilt module constructs the registry yet);
  the tracker seam and its tests are ready.
- Test-infrastructure debt found during T19 (documented, not product bugs):
  `runBlocking` + blocking `MockWebServer.takeRequest()` deadlocks launched
  coroutines; `job.isCancelled` is not evidence that a body observed
  cancellation.
- T22 needs no work: decided `(b)` and folded into schema v2 before T16.
