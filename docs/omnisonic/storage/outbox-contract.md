# Outbox contract — `storage/outbox-contract.md`

Purpose: pins the outbox wire contract (payload JSON shape), the media-type and
action vocabularies, the due-ordering rule, the failure semantics for
structurally broken rows, and the drain schedule plus the pending-sync read
model the settings screen renders.

How derived: audit finding in the pre-release review (duplicate
payload classes with Moshi-ignored drift, mixed media-type vocabulary, missing
ordering guarantees, silent completion of empty payloads); the per-item action
order is a recorded design decision. The concurrent-enqueue rules come from the
pre-Phase-5 remediation finding that a drain deleted plays enqueued while a
submission was in flight. The play-count invariant and chunked submission come
from the remediation finding that an uncapped delta and a capped timestamp list
could disagree. The trigger set (foreground start, post-write, manual retry) is
a Phase 6 task-3 design decision; `OutboxQueue`'s enqueue completion is
deliberately not a trigger because it fires inside the write transaction. See
`../decisions.md`.

Confidence: all behaviours [VERIFIED] by the listed tests; the per-item action
order, the trigger set and the manual-retry back-off clear are design decisions
recorded in `../decisions.md`.

Date: 2026-11-18

Links: `provider-api` `Outbox.kt` (`OutboxActionPayload`, `OutboxActions`,
`OutboxStore`, `OutboxDrainer`), `core-data` `OutboxQueue`, `OutboxDao`,
`RoomOutboxStore`, `OutboxSync`, `OutboxPendingRepository`, `feature-settings`
`PendingSyncViewModel`, `PendingSyncSection`, `../decisions.md`.

---

## Single payload type

- `provider-api.OutboxActionPayload` is the ONLY outbox payload class; the
  former core-data mirror was deleted. The Room row stores this class encoded
  with Moshi. [VERIFIED by `OutboxActionPayloadJsonTest` golden round-trip: the
  JSON field names are pinned, so a rename fails the test instead of silently
  nulling fields at drain time.]
- Old payloads without `playTimestamps` decode gracefully (all fields default
  null). [VERIFIED]

## Vocabularies

- The Room `mediaType` column stores `ProviderMediaType` NAME strings
  (`SONG`, `ALBUM`, `ARTIST`). The enqueue API takes the enum; free-form
  strings cannot enter the table. [VERIFIED by
  `roomColumnStoresTheProviderMediaTypeEnumName` — red before the fix, which
  stored PlayCounter's `"track"` literal.]
- The drainer resolves the media type strictly by enum name; an unknown value
  parks the row as failed (`unknown media type: <raw>`) and never guesses
  `SONG`. [VERIFIED by `unknown media type parks the task as failed ...` —
  red before the fix, which defaulted to SONG and executed the call.]
- Action constants live in `provider-api.OutboxActions` (`play_count`,
  `favorite`, `user_rating`, `resume_point`). [VERIFIED]

## Ordering

- `OutboxDao.selectDue` orders by `nextAttemptAt, createdAt, id`; ties are
  deterministic. [VERIFIED by `selectDueOrdersByNextAttemptThenCreatedAtThenId`
  — red before the fix (insertion order was returned for ties).]
- The drainer processes each item group
  (`providerId, mediaType, externalId`) in action order
  play_count → favorite → user_rating → resume_point; item groups keep their
  relative due order. A resume-point clear never overtakes a play count for
  the same item. [VERIFIED by `resume point clear runs after a play count task
  for the same item` — red before the fix (pure due order).]

## Empty payload handling

- A known action whose payload has no usable value (missing field, undecodable
  JSON) parks the row as FAILED with `lastError = "empty payload"` via the
  normal backoff path; it is never marked COMPLETED. This covers favorite,
  user_rating, resume_point (no value) and play_count (no positive delta or no
  timestamps). [VERIFIED by the `parks as failed` drainer tests — red before
  the fix, which completed the task and silently dropped the change.]
- Unknown actions are still dropped with a warning (unchanged behaviour).

## Play-count timestamps and partial acceptance

- A play-count payload keeps one timestamp per play: `playCountDelta` equals
  `playTimestamps.size`. `OutboxQueue` stores every enqueued timestamp (no
  cap), and merging a new play into a legacy row without an explicit timestamp
  list synthesises the legacy plays before appending the new one. [VERIFIED by
  `playTimestampsKeepEveryEnqueuedPlayAndMatchTheDelta`,
  `mergingIntoALegacyTimestamplessRowReconcilesDeltaWithTimestamps`, and the
  preserved `playCountDeltaMergeAccumulatesIntoSingleRow` — red before the fix,
  which truncated the list at 100 while keeping the summed delta.]
- `MediaProvider.submitPlays` returns the length of the accepted prefix: it
  stops at the first rejected timestamp instead of sending later ones. The
  drainer submits the stored timestamps in chronological chunks of
  `OutboxDrainer.PLAY_SUBMISSION_CHUNK_SIZE` (100). After every fully accepted
  chunk it calls `store.progress` before sending the next chunk, so an
  interruption can lose at most the current chunk. [VERIFIED by
  `play count submits in chunks and records progress between them` and
  `two hundred and fifty plays submit across chunks in strictly increasing
  order` — red before the fix, which submitted the whole list in one call.]
- A rejected timestamp ends the current chunk. The drainer passes the accepted
  prefix to `store.progress`, which removes exactly those timestamps from the
  row's current payload; the rejected timestamp and every later timestamp stay
  queued in order. The accepted prefix is never re-sent. [VERIFIED by
  `a rejected second submission keeps the failed timestamp and its successors`,
  `a rejected submission inside a chunk keeps every unsubmitted timestamp`,
  `a failed chunk resumes without losing or duplicating accepted timestamps`,
  and `submitPlays stops at the first rejected timestamp` — red before the fix,
  which kept submitting after the rejection and persisted only the timestamps
  after the failed one.]

## Changes enqueued during a drain

- `OutboxStore.complete(task)` deletes using both the row id and the payload
  the drain submitted (NULL-safe comparison) and reports `Deleted`,
  `Superseded` or `Missing`. A play, or a newer favorite/rating/resume value,
  merged into the row while the submission is in flight therefore cannot be
  deleted by the finishing drain. [VERIFIED by the three `enqueued during a
  submission` end-to-end tests — red before the fix, which deleted by id and
  dropped the newer change.]
- `OutboxStore.progress` is transactional: it re-reads the row, removes only
  the timestamps the drain submitted (at most as many occurrences as were
  submitted, so an identical timestamp enqueued meanwhile survives), keeps
  every other field, recomputes `playCountDelta`/`lastPlayedAt`, and deletes
  the row only when no timestamp remains. An undecodable stored payload is
  left untouched and reported `Unreadable`. [VERIFIED by the play race test,
  `submitting one of two identical timestamps keeps the other`,
  `submitting part of a legacy timestampless payload keeps the rest`, and the
  existing partial-acceptance tests.]
- A superseded favorite/rating/resume row is never rewritten by the drainer:
  the stored row already carries the newer value, so the next drain submits
  it. The finishing drain counts that task as progressed, not completed.
  [VERIFIED by the favorite and resume-point race tests.]

## Authentication failures

- A `ProviderAuthException` from the provider (Subsonic codes 40–44, HTTP
  401/403) is not a normal retry failure: the task stays due with no attempt
  increment and no backoff, the provider is reported through `onAuthFailed`,
  and every later task for that provider in the same drain is skipped. A
  subsequent drain is blocked by the run gate until the auth marker clears.
  [VERIFIED by `OutboxDrainerTest` — `auth failure stops later tasks for the
  provider in the same drain`, `an auth failed provider is not retried by the
  next drain`, and `OutboxDrainEndToEndTest` with a real Subsonic code 40.]
- Generic provider exceptions, rejected changes, transport failures and
  non-auth HTTP/Subsonic errors keep the existing backoff path and never call
  `onAuthFailed`. [VERIFIED by `OutboxDrainerTest` — `generic provider failure
  does not report an auth failure`.]
- Any non-auth provider interaction reports `onAuthSucceeded`, which lets the
  status tracker clear a stale `AUTH_FAILED` marker. [VERIFIED by
  `OutboxDrainerTest` and `OutboxDrainEndToEndTest` — `successful task reports
  authentication success`, `a successful drain clears a prior auth failure`.]

## Drain schedule and coalescing

- Triggers are: app foreground (`MainActivity.onStart`), a committed play
  (`CoreDataPlayHistorySink` requests after `recordPlay` returns, i.e. after the
  transaction), and a committed favorite (`FavoritesRepository` requests after
  its transaction). Every trigger calls `OutboxSyncControl.requestDrain()` and
  returns immediately; a failed drain is logged, never propagated into the
  write path. Enqueue completion is not a trigger — it happens inside the write
  transaction, before the change is readable. [VERIFIED by
  `OutboxSyncTest` — `a foreground request runs one drain`,
  `FavoritesRepositoryTest` — `favoritesAnItemWithoutASyncedSong` asserts one
  request.]
- Requests land in a conflated channel consumed by one worker, so a burst of
  writes is expected to produce one drain and a request that arrives mid-drain
  produces exactly one follow-up. [INFERRED from the `Channel.CONFLATED`
  implementation; no test pins the coalesced count.]
- A drain holds a try-lock; a second concurrent `drainNow()` returns null
  instead of stacking. `retryAllNow()` that finds the runner busy clears the
  back-off and re-requests, so the parked rows are still picked up by the
  running worker's follow-up. [INFERRED from `OutboxSync.drainNow`/
  `retryAllNow`; the concurrency path has no dedicated test.]
- The drain coordinator runs on its own process scope, separate from the
  library sync scope, so a pending sync never waits behind a library walk. Rows
  blocked by the provider run gate stay queued without counting as deferred
  when the drain was asked for all providers. [VERIFIED by
  `a provider that is offline keeps every row without recording a failure`.]
- A failure parks the row through the existing back-off path
  (`attempts`/`nextAttemptAt`/`lastError`), so it is not retried before the due
  time. A manual retry clears `attempts` and `nextAttemptAt` for every row —
  parked rows become due immediately and the next failure starts from the base
  back-off — while `lastError` stays until the row actually succeeds, so the
  settings screen keeps showing what went wrong. [VERIFIED by `a failed
  submission backs off and a manual retry succeeds at the same clock` and
  `clearing the back-off makes parked rows due and keeps the error`.]

## Pending-sync read model

- `OutboxPendingRepository.providers` combines three aggregate queries:
  per-provider count plus failing count (`observeProviderCounts`), the newest
  error per provider ordered by `nextAttemptAt DESC, id DESC`
  (`observeProviderErrors`), and provider names in display order
  (`ProviderDao.observeIdentities`). Opening or writing to the settings screen
  never loads queued rows one by one. [VERIFIED by
  `counts errors and provider names aggregate per provider` and
  `the most backed-off failure supplies the provider error`.]
- Rows follow provider display order; a provider missing from the identity
  table sorts last. `outbox_items` has no foreign key to `providers`, so a
  queued row for a deleted provider can name no provider: the view falls back
  to `Provider <id>` rather than hiding the row. [VERIFIED by the read-model
  test; the fallback lives in `PendingSyncSection`.]
- `PendingSyncViewModel` exposes the provider rows, the summed total, and a
  `draining` flag from `OutboxSyncControl.state` (the retry button is disabled
  while a drain runs). Counts refresh through Room invalidation, so no explicit
  reload is needed. [VERIFIED by `PendingSyncViewModelTest` — pending rows with
  total and newest error, empty outbox, running drain marks draining, retry
  asks the coordinator — and `PendingSyncSectionTest`.]
