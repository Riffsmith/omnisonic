# Offline file integrity and deletion — `offline-file-integrity.md`

Purpose: pin the Phase 7 task-5b contract — the pre-claim missing/zero-file sweep, stale `.part`/sidecar cleanup, the unavailable-root and cross-provider/root boundaries, manual offline deletion that cancels an equivalent queue row, and the provider-deletion file hook that leaves the existing DB cascade intact.

How derived: `.agents/plans/phase-7-tasks.md` §TODO 5 and §TODO 5b, the task-5a completion contract (`storage/download-completion.md`, D-43) for the boundary it already owns, own design decision D-44, the task-1/3/4 queue, transfer and runtime contracts, and the shipped `:core-data` sweep/deletion code plus its JVM tests. No studied-app code or naming is involved.

Confidence: missing/zero-row removal, stale-artifact selection, active-`.part` preservation, unavailable-root and cross-provider/root protection, manual delete with queue cancellation and no resurrection, and the provider hook with the DB cascade are [VERIFIED] by JVM/Robolectric tests. Real volume unmount/removal and on-device filesystem behavior remain [UNKNOWN] without a device trace.

Date: 2026-11-20

Links: `storage/download-completion.md`, `storage/offline-storage-layout.md`, `storage/download-transfer.md`, `storage/download-foreground-service.md`, `storage/schema-v2.md`, `.agents/plans/phase-7-tasks.md`, D-43/D-44 in `../decisions.md`.

---

## Sweep ownership and ordering

`OfflineFileSweep.sweep()` runs on the runtime's single `ensureRecovered` hook, after `recoverOnStartup()` and after task 5a's `recoverOrphanedFinalFiles()`, and before the first claim or any pruning. Recovery waits for a cancelled session's leases to drain before it runs, so no transfer is writing the artifacts it examines. [VERIFIED]

Ordering matters: task 5a settles publish/crash boundaries first (adopt a verified orphan or quarantine/delete an unverifiable one), and the sweep then reconciles the surviving rows and leftover attempt artifacts against the filesystem. The sweep never deletes a final media file — that stays the task-5a protocol, so the two passes cannot disagree. [VERIFIED]

## Missing and zero-length files

- Every `offline_media` row is resolved against the **active root** (`OfflineMedias/…` relative path). A row whose file is absent or not a regular file is removed; nothing outside the app-private media tree is touched. [VERIFIED]
- A zero-length file is unplayable: the file and its row are removed. [VERIFIED]
- The pass skips entirely when `OfflineStorage.activeRoot()` reports the published/selected volume unavailable, and also when the root exists but `OfflineMedias/` is absent. "Missing" is not provable in either state, so a missing mount or an empty tree is never reported as an empty library. [VERIFIED]
- Only rows for the active root are examined; another provider's row or another volume's file is never swept by inference. [VERIFIED]

## Stale attempt artifacts

Only provably unclaimable artifacts are removed: [VERIFIED]

| Artifact | Removed when | Kept when |
|---|---|---|
| `<hash>.part` | Its sidecar is missing/unreadable, or the sidecar's `key=<mediaKey>` no longer matches any `download_queue` row | The sidecar key matches a row in any status (`PENDING`, `PAUSED`, `USER_PAUSED`, `ACTIVE`, `FAILED`, or an unrecognized raw value that still fails closed on claim) — resumable bytes survive pause, retry and parked work |
| `<hash>.part.state` | Its `.part` no longer exists (a published file's leftover sidecar after a commit/process-death window, or an orphan the task-5a pass already resolved) | The `.part` exists; the part entry owns both files |
| `<hash>.part.state.tmp` | Always — an interrupted sidecar write is never referenced | — |

A cancelled or provider-deleted queue row therefore leaves a stale part that the next sweep removes; the transfer engine already discards its own part on a live cancel. Symlinked entries are never followed or deleted. [VERIFIED]

## Manual offline deletion

`OfflineMediaDeletion.deleteOffline(offlineId)` removes one offline row and its file: [VERIFIED]

1. The row is read by id (internal identity lookup, unrestricted).
2. An equivalent queue row with `mediaKey == offline.sourceKey` is cancelled through `DownloadQueueCancellation` (production: the foreground runtime's process-wide coordinator, which deletes the row from any known state and signals an active transfer). A host with no runtime uses the direct row delete.
3. The `offline_media` row is deleted and the stored file is removed under the active root.

No resurrection: the queue row is gone before the offline row, and the task-5a publication transaction is fenced by the still-`ACTIVE` queue row, so a late worker settlement cannot recreate the offline row or a phantom publication. A cancelled transfer's `.part` is discarded by the engine; any leftover artifact is provably stale on the next sweep. [VERIFIED]

When the root is unavailable the row is still removed (explicit user intent) and the file is left: the task-5a orphan pass deletes a remaining unreferenced final file once the volume returns. [VERIFIED policy; device volume behavior [UNKNOWN]]

Deleting one variant never touches another variant or another provider's row/file; each has its own variant-qualified path and stream key. [VERIFIED]

## Provider deletion hook

`OfflineMediaDeletion.deleteProviderOffline(providerId)` selects the provider's offline rows, deletes each resolved file, then deletes those rows. It is designed as a **pre-delete hook**: it runs before the `providers` row is deleted, and the existing `ProviderCascadeTriggers` remains the backstop that removes any remaining queue/offline rows. [VERIFIED method behavior; INFERRED wiring] **No production caller is wired yet**: provider deletion today relies on the cascade, which removes rows but leaves files for the task-5a orphan pass, so the hook is exposed for the provider-management task (7–9) rather than claimed as active.

- Other providers' rows and files are untouched. [VERIFIED]
- An active worker for the deleted provider cannot republish: the cascade removes its queue row and the publication write is lease/`ACTIVE`-fenced, so the late settlement reports not-applied. [VERIFIED]
- Parts owned by the deleted provider's cancelled queue rows are removed by the next startup sweep once their sidecar key no longer matches a row; the hook does not delete a `.part` that a worker may still be writing. [VERIFIED policy]
- With an unavailable root, rows are removed and files stay until the mount returns and the task-5a orphan pass resolves them. [VERIFIED policy; device behavior [UNKNOWN]]

## Deliberately out of scope

Offline-first playback resolution (task 5c), MediaStore export (task 6), automatic rules and their pruning (tasks 7–8), downloads UI/commands (task 9), final-file crash-orphan adoption/quarantine (task 5a), and size/digest re-verification of present files. Root-move `.moving` staging is owned by the task-2 root-change recovery and is never touched by this sweep. No schema change was introduced. [VERIFIED]

## Evidence

[VERIFIED JVM 2026-11-20] `:core-data` **636/0/0/0** (`OfflineFileSweepTest` 13, `OfflineMediaDeletionTest` 8; download package 230); `:feature-downloads` **17/0/0/0** (incl. the pre-claim sweep test on the real runtime); `:app` debug **166/0/0/0**; `:app:assembleDebug` + `lintDebug` on `:core-data`, `:feature-downloads`, `:app` green. Banned-name scan exit 0 and public-name diff vs `theme_keys.json` + THEMING.md §4 clean. Device volume removal/unmount and on-device `File.delete` behavior remain `[UNKNOWN]`.
