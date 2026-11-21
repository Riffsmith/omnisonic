# Download queue lifecycle — `download-queue-lifecycle.md`

Purpose: pin the persisted download-queue state machine, retry timing, enqueue idempotence, and the worker claim/lease protocol implemented by Phase 7 task 1, so later transfer, service, rule and UI work builds on one recorded contract.

How derived: own design decision D-39 (user-confirmed 2026-11-19 at the Phase 7 task-1 gate), the task plan `.agents/plans/phase-7-tasks.md` §Task 1 lifecycle / §retry and enqueue / §no task 1 schema migration / §claim and race protocol, and the shipped `:core-data` `downloads` package plus its tests.

Confidence: the state rules, retry arithmetic, conditional writes, lease fencing and pause ordering are [VERIFIED] by JVM tests. HTTP transfer, storage layout, service/process behavior and device timing are out of scope here and remain [UNKNOWN] until their own tasks.

Date: 2026-11-20

Links: `storage/schema-v2.md`, `storage/download-transfer.md`, `storage/download-completion.md`, `providers/provider-connections.md`, `.agents/plans/phase-7-tasks.md`, D-39 in `../decisions.md`.

---

## States

`DownloadStatus` persists by name and has exactly five values; there is no completed state. Queue success and explicit cancel delete the row, so a finished download is represented by its `offline_media` row, not by a queue status. Since task 5a, ordinary success publishes that offline row and deletes the queue row in one lease-fenced Room transaction — see `storage/download-completion.md`. [VERIFIED]

| State | Meaning | Claimable |
|---|---|---|
| `PENDING` | Waiting for its retry delay, if any; eligible when due and the provider run gate allows work | yes, when due |
| `PAUSED` | Held by the global pause-all gate; new unique enqueues while the gate is set also start here | no |
| `USER_PAUSED` | Sticky explicit per-item pause; resume-all never releases it | no |
| `ACTIVE` | Claimed by exactly one worker lease | no |
| `FAILED` | Parked after the sixth counted transfer failure; never becomes due on its own | no |

Unknown or invalid persisted raw values (for example a legacy `RUNNING`) parse to null and fail closed: they are never claimed, transitioned, paused, retried or cancelled. `DownloadLifecycleRules.canApply` returns false for every command, and the conditional SQL only matches the five known names. [VERIFIED]

## Pause and resume

- Pause-all writes the persisted gate first, then moves `PENDING` and `ACTIVE` rows to `PAUSED`. `USER_PAUSED` and `FAILED` are untouched. [VERIFIED]
- Resume-all releases `PAUSED → PENDING` first, then clears the gate. Only `PAUSED` is released. [VERIFIED]
- Pause-one moves `PENDING`, `PAUSED` or `ACTIVE` to `USER_PAUSED`. Pausing an already globally paused row therefore converts it into a sticky item pause, and repeating pause-one on `USER_PAUSED` is a no-op. `FAILED` rows cannot be paused. [VERIFIED]
- Resume-one releases only `USER_PAUSED`, targeting `PENDING` when the gate is clear and `PAUSED` while it is set. It never bypasses the global gate. [VERIFIED]
- The gate lives in a DataStore file (`omnisonic_downloads`), not in queue rows, so pausing an empty queue is remembered across process death. [VERIFIED JVM]
- Enqueue and pause mutations share one coordinator mutex, closing the check-then-insert race. [VERIFIED]

## Enqueue and retry

- Duplicate enqueue is a strict whole-row no-op in every state: status, progress, title, ownership, rule, export intent, timestamps, error count and error text are all preserved. A `FAILED` row is revived only by an explicit retry. [VERIFIED]
- Media identity remains `DownloadKeys.mediaKey(providerId, externalId, transcodeBitrateKbps, format)`; provider, bitrate and format variants are distinct rows. [VERIFIED]
- Capability is checked before the run gate: an unsupported provider returns the typed rejection and a blocked provider returns the typed blocked result; neither writes a row. [VERIFIED]
- A unique enqueue starts `PENDING`, or `PAUSED` while the global gate is set. [VERIFIED]
- Counted failures 1–5 increment `errorCount`, sanitize and store `lastError`, stamp `updatedAt` with the failure time, retain progress and return the row to `PENDING`. The sixth sets `errorCount = 6` and `FAILED`, retaining progress and the error. [VERIFIED]
- Explicit retry applies only to `FAILED`: it resets `errorCount` to zero, clears `lastError`, stamps a fresh scheduling time, retains progress and targets `PENDING` or `PAUSED` according to the gate. Retry-all affects `FAILED` rows only. [VERIFIED]
- Network loss, authentication/run-gate blocks, Wi-Fi gating, low-space gating and coroutine cancellation are never counted transfer failures and never create a pause state. [VERIFIED]

## Retry timing without a schema column

There is no `nextAttemptAt` column and no Task 1 migration. `updatedAt` is the scheduling anchor, not a generic last-write timestamp. Only a unique enqueue, a counted failure and an explicit retry write it. [VERIFIED]

- `errorCount == 0` is immediately eligible, even when the wall clock moved backwards.
- For `errorCount` 1–5, `dueAt = updatedAt + errorCount × 10 000 ms`.
- Due rows are ordered by effective `dueAt`, then `createdAt`, then `id`. A long-back-off row that became due earlier therefore keeps its place ahead of a later short-back-off row, and ties are deterministic.
- Counts below zero or above five are corrupt and ineligible.
- Arithmetic saturates at `Long.MAX_VALUE`, so overflow becomes not-yet-due rather than an early retry.

Claim, progress, pause, resume, lease release and startup recovery never touch `updatedAt`, so none of them resets or extends a retry delay. [VERIFIED]

## Claim, lease and race protocol

- The coordinator takes an explicit worker count; zero or negative values sanitize to one. Task 2 decides the user-facing default and range. [VERIFIED]
- A claim is a conditional `PENDING → ACTIVE` update requiring the observed `updatedAt` and `errorCount` to still match, so two independent coordinator/repository instances cannot claim one row. Selection alone is never a claim. [VERIFIED]
- A successful claim returns a process-unique lease token. Every later progress, failure, completion and release write validates that the row still maps to this token in the coordinator and conditionally requires the row to remain `ACTIVE`. This closes the `ACTIVE → pause → resume → ACTIVE` ABA race. [VERIFIED]
- A lease remains registered until the worker has finished settlement and called release. A paused-but-not-yet-exited lease keeps consuming its worker slot, so an immediate resume cannot overlap two transfers on one row. [VERIFIED]
- Release runs in a non-cancellable section: it returns only this worker's still-`ACTIVE` row to `PENDING` or `PAUSED` without incrementing the error count, and never overwrites a concurrent pause, cancel or committed failure. [VERIFIED]
- Blocked providers are skipped without changing status, error count or timestamps, and later providers are still considered (no head-of-line blocking). [VERIFIED]
- Startup recovery converts orphaned `ACTIVE` rows to the gate state and also reconciles any `PENDING` row left behind by a crash between the ordered gate/row writes. Claiming independently refuses all work while the persisted gate is set, including before recovery. [VERIFIED JVM 2026-11-20 audit]
- Cancel deletes the row from any known state; a stale worker's progress, failure, completion or release cannot recreate or mutate it. Provider deletion cascades active rows and a stale settlement stays a no-op. [VERIFIED]

## Scoped reads

Every new queue read follows the `ProfileScope` convention: the DAO exposes a `*Raw` query projecting `excludedProviderIds` plus a wrapper taking the scope. Internal worker reads pass `ProfileScope.Unrestricted` explicitly; UI reads pass the real scope. Identity lookups (`selectById`, `selectByMediaKey`, `selectByProvider`) are deliberate exemptions in `ProfileScopeQueryTest`. [VERIFIED]

## Deliberately out of scope

HTTP transfer and range resume, MediaStore export, offline playback, the foreground service/notification, wake locks, network triggers, automatic-rule expansion/pruning, and downloads UI are later Phase 7 tasks. The user-facing worker/Wi-Fi settings, storage roots, `.part` naming and root changes now live in `offline-storage-layout.md` (task 2). This document covers the persisted lifecycle and claim foundation only. [VERIFIED]

## Evidence

Original task evidence: `:core-data:testDebugUnitTest` 460 tests, 0 failures/errors/skips; `:app:assembleDebug` green. The 2026-11-20 audit added regression coverage for gate/row write-failure ordering, persisted-gate crash recovery, direct gate enforcement during claims, exact concurrent worker caps, and interruption-signal settlement. Final audit evidence is `:core-data` **582/0/0/0**, download package **177/0/0/0**, including `DownloadWorkerCoordinatorTest` **36** and `DownloadEnqueuerTest` **14**. No exported Room baseline changed and `Migrations.ALL` stays empty. [VERIFIED JVM 2026-11-20]
