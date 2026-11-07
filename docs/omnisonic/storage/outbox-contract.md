# Outbox contract — `storage/outbox-contract.md`

Purpose: pins the outbox wire contract (payload JSON shape), the media-type and
action vocabularies, the due-ordering rule, and the failure semantics for
structurally broken rows.

How derived: audit finding in the pre-release review (duplicate
payload classes with Moshi-ignored drift, mixed media-type vocabulary, missing
ordering guarantees, silent completion of empty payloads); the per-item action
order is a recorded design decision. The concurrent-enqueue rules come from the
pre-Phase-5 remediation finding that a drain deleted plays enqueued while a
submission was in flight. The play-count invariant and chunked submission come
from the remediation finding that an uncapped delta and a capped timestamp list
could disagree. See `../decisions.md`.

Confidence: all behaviours [VERIFIED] by the listed tests; the per-item action
order is a design decision recorded in `../decisions.md`.

Date: 2026-11-06

Links: `provider-api` `Outbox.kt` (`OutboxActionPayload`, `OutboxActions`,
`OutboxStore`, `OutboxDrainer`), `core-data` `OutboxQueue`, `OutboxDao`,
`RoomOutboxStore`, `../decisions.md`.

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
