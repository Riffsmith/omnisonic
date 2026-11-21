# Crash-safe download completion + offline publication — `download-completion.md`

Purpose: pin the Phase 7 task-5a contract — the additive `offline_media` read-model migration, the atomic offline-publish/queue-delete transaction after finalization, the verification state retained until that commit, the replacement-variant continuity rule, and the orphan adoption/quarantine recovery at every finalization step.

How derived: the task plan `.agents/plans/phase-7-tasks.md` §TODO 5a, the user-approved schema gate (2026-11-20: `tempCache` + nullable `externalId` + `(providerId, externalId)` index, over the exact-sourceKey-parse alternative), own design decision D-43, the task-3 transfer contract (`storage/download-transfer.md`), the task-4 runtime contract (`storage/download-foreground-service.md`), and the shipped `:core-data` publication/recovery code plus its JVM tests. No studied-app code or naming is involved.

Confidence: the migration and v1 upgrade preservation, commit atomicity/rollback, crash-at-each-finalization-step recovery, permanent/rolling metadata, provider/external lookup and replacement-variant continuity are [VERIFIED] by JVM/Robolectric tests. Real power-loss/rename durability, filesystem fault behavior and on-device recovery timing remain [UNKNOWN] without a device trace; JVM crash-step tests do not establish hardware durability.

Date: 2026-11-20

Links: `storage/schema-v2.md`, `storage/download-transfer.md`, `storage/download-queue-lifecycle.md`, `storage/offline-storage-layout.md`, `storage/download-foreground-service.md`, `storage/offline-file-integrity.md`, `.agents/plans/phase-7-tasks.md`, D-43 in `../decisions.md`.

---

## Additive read-model migration (v1 → v2)

`Migrations.MIGRATION_1_2` is the first approved schema change on the clean v1 baseline. It is additive only [VERIFIED]:

- `offline_media.tempCache INTEGER NOT NULL DEFAULT 0` — the queue row's rolling intent persists onto the completed offline row; v1 rows decode as **permanent** (`false`), and the entity declares `@ColumnInfo(defaultValue = "0")` so Room's schema validation matches the SQL default.
- `offline_media.externalId TEXT NULL` plus index `index_offline_media_providerId_externalId (providerId, externalId)` — an O(log n) lookup of every offline variant of one song, instead of scanning and parsing each provider row's `sourceKey`.
- A tolerant backfill inside the migration parses each `sourceKey` with the exact colon-safe `stream:`-key parse and sets `externalId`. An unparseable key keeps NULL (reads tolerate it); no `offlinePath`, `sourceKey`, row or path is rewritten or deleted.

`SCHEMA_VERSION = 2`; `2.json` is newly exported and `1.json` is untouched. The migration harness seeds `1.json` with offline rows (plain, colon-containing and unparseable keys) plus a `download_queue` row, runs the real `1 → 2` step, validates against `2.json`, and reopens the seeded database through the production factory. [VERIFIED]

`DownloadDao`'s scheduling/column shape is unchanged; no other table changed.

## Publication protocol (ordinary offline rows)

After the complete-size check, per-path mutex and non-replacing rename, the engine settles success with **one Room transaction**: `offlineFileDao.upsert(offlinePublicationRow(...))` plus the conditional `download_queue` deletion (`deleteIfActive`, fenced by the current lease). The queue row is deleted only inside that commit, so there is no completed queue state and no phantom offline row. [VERIFIED]

The published row carries `providerId`, `mediaType = SONG`, `sourceKey = mediaKey`, the variant-relative `offlinePath`, `sizeBytes`, title/image, the requested `bitrateKbps`, `autoRule` (decimal rule id, null = manual), `downloadedAt`, `tempCache` (rolling intent) and the parsed `externalId`. Export-only rows (`exportTarget != null`) never publish offline media and never reach an app-private final path: task 6 replaced the legacy settlement with the staged public publication in `storage/export-publication.md`, and this adoption pass refuses any orphan whose queue row carries an `exportTarget`. [VERIFIED]

### Verification state is retained until the commit

The `<hash>.part.state` sidecar is **no longer deleted at rename time**. It survives until the publication transaction commits, and only then is it removed. It now also carries the owning `mediaKey` (`key=` line, written before the first body byte; older sidecars without it still decode). After a crash between rename and commit, the filesystem therefore holds a complete file plus its persisted variant hash, expected length and owning stream key — enough to adopt it without any provider or network lookup. [VERIFIED]

### Crash matrix at each finalization step

| Step at which the process dies (or pause/cancel lands) | State on disk/DB | Recovery |
|---|---|---|
| Before the rename | `.part` + sidecar, row still queued | Ordinary resume; part-equals-total finalizes without a request [VERIFIED] |
| After rename, before commit | Final file + sidecar (hash, total, key), row still queued | Orphan **adoption**: sidecar hash matches the file name and `totalBytes` equals the exact non-zero file length → publish + delete the queue row in one transaction, then delete the sidecar [VERIFIED] |
| Same window, row paused | Final file + sidecar, row `PAUSED`/`USER_PAUSED` | Adopted the same way — the bytes were already complete; the row settles by deletion [VERIFIED] |
| Same window, row cancelled | Final file + sidecar, row gone | Orphan is **quarantined/deleted**, never adopted or guessed from another row [VERIFIED] |
| Orphan without a sidecar, with a wrong/foreign hash, with a mismatched or missing total, or zero-length | Unverifiable | Quarantined/deleted; a surviving queue row retries safely (the path is free) [VERIFIED] |
| After commit, before sidecar deletion | Published `offline_media` row + final file + leftover sidecar | The published file is never touched; a re-claimed duplicate row settles idempotently and the stale sidecar/part is discarded [VERIFIED] |

### Two recovery entry points

- **Pre-claim startup pass** (`DownloadCompletionRecovery.recoverOrphanedFinalFiles`, called from the runtime's single `ensureRecovered` hook after any draining lease is released): scans the active root's `OfflineMedias` tree, skipping `.nomedia`, `.part`, `.state`, `.tmp`, `.moving` and symlinks. A final file with no `offline_media` row is adopted only when its sidecar verifies the exact length **and** names a still-present queue row (`publishAndSettleRecovered`, atomic over any known queue state); an unknown raw status fails closed and the orphan is deleted. An unavailable root skips the pass without deleting anything. [VERIFIED]
- **In-attempt resolution** (`DownloadTransferEngine.resolveExistingFinal`): a final path that already exists is (a) already published → settle the duplicate queue row without re-downloading and **without overwriting the published row's metadata**; (b) a verified crash orphan → adopt; (c) unverifiable → deleted and the transfer retries safely. A publication write failure parks `FAILED` fail-closed with no error increment, keeping the file and sidecar for a later explicit retry. [VERIFIED]

## Replacement-variant continuity

Variant-qualified hashing means a replacement (different bitrate/format) is a **different** final path and a **different** `sourceKey`. Publication therefore inserts a second row beside the old one: the old playable file and its row stay intact through the new download, so there is never an offline gap. Pruning/replacement ordering of the old variant belongs to task 8, not here. Re-publishing the same `sourceKey` is an idempotent upsert. [VERIFIED]

## Reads

New offline reads: `selectByOfflinePath` (identity lookup) and `selectByProviderAndExternal(providerId, externalId, scope)` using the Raw+`ProfileScope` wrapper convention — internal workers pass `Unrestricted`, UI reads pass the real scope. [VERIFIED]

## Deliberately out of scope

The startup missing/zero-file sweep, stale `.part` cleanup and manual/provider file deletion belong to task 5b (`storage/offline-file-integrity.md`); this document only defines which final files it must not touch. Offline-first playback resolution (task 5c) is documented in `playback/offline-resolution.md`; MediaStore export (task 6) is documented in `storage/export-publication.md`; automatic rules (tasks 7–8) and any downloads UI (task 9) remain out of scope.

## Evidence

[VERIFIED JVM 2026-11-20] `:core-data` **615/0/0/0** (download package **209**: `OfflineMediaRepositoryTest` 9, `DownloadCompletionRecoveryTest` 11, `DownloadTransferEngineTest` 47, `DownloadWorkerCoordinatorTest` 42, `DownloadTransferJournalTest` 7); `MigrationHarnessTest` **5**; `:feature-downloads` **16/0/0/0** (incl. pre-claim orphan adoption through the real runtime); `:app:assembleDebug` + `lintDebug` on `:core-data`, `:feature-downloads`, `:app` green. `1.json` untouched; `2.json` newly exported; banned-name scan + public-name diff clean. Device power-loss/durability items stay `[UNKNOWN]`.
