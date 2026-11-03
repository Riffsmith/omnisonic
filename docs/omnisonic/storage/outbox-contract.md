# Outbox contract — `storage/outbox-contract.md`

Purpose: pins the outbox wire contract (payload JSON shape), the media-type and
action vocabularies, the due-ordering rule, and the failure semantics for
structurally broken rows.

How derived: audit finding in the pre-release review (duplicate
payload classes with Moshi-ignored drift, mixed media-type vocabulary, missing
ordering guarantees, silent completion of empty payloads); the per-item action
order is a recorded design decision. See `../decisions.md`.

Confidence: all behaviours [VERIFIED] by the listed tests; the per-item action
order is a design decision recorded in `../decisions.md`.

Date: 2026-11-03

Links: `provider-api` `Outbox.kt` (`OutboxActionPayload`, `OutboxActions`,
`OutboxDrainer`), `core-data` `OutboxQueue`, `OutboxDao`, `../decisions.md`.

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
