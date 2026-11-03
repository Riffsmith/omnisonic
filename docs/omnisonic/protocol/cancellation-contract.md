# Cancellation contract — `protocol/cancellation-contract.md`

Purpose: pins the rule that structured-coroutine cancellation is never
swallowed, logged as a task failure, or converted into a retry/backoff anywhere
in Omnisonic's suspend call graph.

How derived: pre-release correctness audit finding (suspend calls wrapped in
`runCatching`/`catch (Exception)`), confirmed by the failing tests listed below.

Confidence: the drainer and client swallowing behaviour was [VERIFIED] red
before the fix; the syncer-site claim was only [PARTIALLY] reproducible (see
below).

Date: 2026-11-03

Links: `core-model` `runCatchingCancellable`; `provider-api` `OutboxDrainer`;
`provider-navidrome` `SubsonicClient`; `core-data` `SubsonicLibrarySyncer`;
`../decisions.md` (cancellation contract).

---

## Rule

- `core-model.runCatchingCancellable` is the ONLY permitted wrapper around
  suspend calls that need failure capture. It rethrows
  `kotlin.coroutines.cancellation.CancellationException` untouched and captures
  every other `Throwable` as a failed `Result`. [VERIFIED]
- `OutboxDrainer.drain` runs each task under `runCatchingCancellable`: a
  cancelled drain records NO `store.fail(...)` row and no backoff; the
  CancellationException propagates to the caller. [VERIFIED by
  `cancelling the drain mid-task records no failure and cancels the caller` —
  red before the fix: a `fail()` row was recorded and the drain returned
  normally.]
- `SubsonicClient.probe` (extensions fallback), `SubsonicClient.submitPlays`
  (per-scrobble fallback) and the transport await in `requestAttempt` use
  `runCatchingCancellable`: cancelling a call mid-flight propagates
  cancellation instead of yielding a default capability set, a partial scrobble
  batch, or a wrapped transport error. [VERIFIED by the two
  `cancelling ... propagates` tests in `SubsonicClientTest` — both red before
  the fix: the cancelled body completed normally.]
- `SubsonicLibrarySyncer.runSync` rethrows `CancellationException` before its
  `catch (Exception)` so a cancelled sync never records a failed sync status.
  [PARTIALLY VERIFIED: on the JVM the old code already failed to write the
  status row because the cancelled coroutine cannot enter
  `database.withTransaction`; the explicit rethrow removes the dependence on
  that incidental behaviour. The guard test
  `cancelling the sync records no failed sync status` pins the contract but was
  green before the fix.]
- `OutboxDrainer.probeOnce` logs probe failures but never captures
  cancellation. [VERIFIED by code inspection plus the drain cancellation test.]
