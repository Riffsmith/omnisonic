# Public Downloads export — `export-publication.md`

Purpose: pin the Phase 7 task-6 contract — export-only download rows that publish a real document through `MediaStore.Downloads`, the fixed public values and sanitization rules, the staged verified copy with pending visibility, the durable export-session ledger that makes retry/process recovery idempotent, and the independent lifetime of a published artifact.

How derived: the task plan `.agents/plans/phase-7-tasks.md` §TODO 6/6a/6b, the confirmed export-ownership recommendation (export-only plus an explicit conversion/new-export command; keep-plus-copy only as "never damage an existing offline file"), the task-3 staging/range contract (`storage/download-transfer.md`), the task-5a completion contract (`storage/download-completion.md`), own design decision D-46, and the shipped `:core-data`/`:feature-downloads` code plus its JVM tests. No studied-app code or naming is involved.

Confidence: value sanitization, MIME/extension fallbacks, pending set/clear, truncating verified copy with cleanup, settlement ordering, crash windows at insert/copy/publish, retry without duplicate documents, channel/destination conflict signalling, and the ownership boundaries are [VERIFIED] by JVM/Robolectric + fake-store tests. Real MediaStore behavior — actual Downloads-app visibility, on-device filename collision suffixing, `is_music` honoring for a pending insert, resolver/volume failure timing — is [UNKNOWN] until a device trace exists; Robolectric shadows are not evidence.

Date: 2026-11-20

Links: `storage/download-transfer.md`, `storage/download-completion.md`, `storage/offline-storage-layout.md`, `storage/offline-file-integrity.md`, `storage/download-queue-lifecycle.md`, `storage/schema-v2.md`, `.agents/plans/phase-7-tasks.md` §TODO 6, D-46 in `../decisions.md`.

---

## Ownership model (user-confirmed)

An export row (`download_queue.exportTarget != null`) is **export-only**: the completed bytes become a public document under `Download/Omnisonic/`, no `offline_media` row is written and no app-private permanent file is created. Ordinary offline downloads never produce public copies. An export request for a stream key that already has a published offline file is a **copy** of that verified file, not a second download, and the offline file is never written, moved or deleted. [VERIFIED]

The public document then belongs to the user. Internal deletion, provider deletion, root migration and the integrity sweep never delete it — nothing in those paths knows its URI because it is not stored in `offline_media`, and the export ledger refers to it only for its own attempt. [VERIFIED by the survival test: an internal provider delete and a verified root change leave the fake document untouched.] Automatic pruning (task 8) will likewise see only queue rows and `offline_media` rows, so it has no path to a public document; that boundary is [INFERRED] until task 8 lands its own survival test.

## Public values and sanitization

| Value | Rule |
|---|---|
| Collection | `MediaStore.Downloads` on the primary external volume (`content://media/external_primary/downloads`) [VERIFIED code / UNKNOWN device] |
| `RELATIVE_PATH` | the one fixed `Download/Omnisonic/` subtree; a foreign path is refused by `ExportNaming.isOwnedRelativePath` [VERIFIED] |
| `DISPLAY_NAME` | `"<sanitized title> [<8-hex identity token>].<extension>"`: NFC, control characters and `\ / : * ? " < > \|` replaced, whitespace collapsed, leading/trailing dots/spaces stripped, bounded to 96 characters without splitting a surrogate pair, reserved names/blank → `Track`; the token is `sha256(mediaKey)`'s first eight hex characters, so the same title on two songs cannot collide and the same song is stable [VERIFIED] |
| Extension | explicit row format first, then the source extension, then `bin` (`OfflinePathRules`); an unsafe value falls back to `bin` [VERIFIED] |
| `MIME_TYPE` | fixed map from the extension (`flac→audio/flac`, `mp3→audio/mpeg`, `m4a→audio/mp4`, `ogg/oga→audio/ogg`, `opus`, `aac`, `wav→audio/x-wav`, `webm`, `mka`, `ape`, `wv`, …), else `application/octet-stream` [VERIFIED] |
| `IS_PENDING` | `1` on insert, cleared only after the verified copy [VERIFIED] |
| `is_music` | `0`, so a later local-library index (`Audio.Media` with `IS_MUSIC != 0`) does not see the artifact [VERIFIED code / UNKNOWN device honoring] |
| No URL metadata | no provider path, signed URL, query string or credential is ever copied into a public value; the display name comes only from the row title [VERIFIED] |

## Publication protocol

1. The task-3 transfer stages bytes in the app-private `.part` with its resumable sidecar, exactly as an ordinary download; the export row never reaches an app-private final path. [VERIFIED]
2. When a published offline file exists for the stream key it becomes the copy source instead of a network transfer — one copy, no second download, offline file untouched. [VERIFIED]
3. `insertPending` creates the document with `IS_PENDING=1`; the app records the returned URI in the export ledger as phase `PENDING` before any byte is written. An insert failure returns `Unavailable` and writes nothing. [VERIFIED]
4. The staged file (or the offline file) is copied with a **truncating** write (`openOutputStream(uri, "wt")`, never an append), hashed while copying, and verified against a read-back digest and the exact byte count. A short, truncated or mismatched copy is a failure. [VERIFIED policy; UNKNOWN on-device read-back]
5. The row must still be `ACTIVE` with no pause/cancel signal; otherwise the document is deleted and nothing is settled. [VERIFIED]
6. `IS_PENDING` is cleared, then the ledger moves to `PUBLISHED`, then the queue row is deleted through the task-1 lease-fenced conditional delete. Settlement is last on purpose. [VERIFIED]
7. Only on an applied settlement are the ledger entry and the staged `.part` + sidecar removed. A failed/cancelled settlement removes the document it created and leaves the row retryable. [VERIFIED]

Visible partial files are impossible: the document is invisible until step 6, and every failure/cancel path deletes it. [VERIFIED policy]

## Durable export-session ledger (no schema change)

`ExportSessionJournal` keeps one `<sha256(mediaKey)>.export` file per attempt in app-internal storage (`filesDir/ExportSessions`, so a root change never moves it), written with an atomic replace and a validating decode of the media key, an owned document URI, display name, MIME, expected bytes and phase. It is deliberately not a queue column: it is attempt bookkeeping, there is no schema change, and a corrupt/foreign file is dropped rather than trusted. [VERIFIED]

| Crash/failure window | On-disk/DB state | Recovery |
|---|---|---|
| After `insertPending`, before the copy completes | `PENDING` ledger + invisible document | Recovery deletes the document and the ledger entry; the row retries and inserts one fresh document [VERIFIED] |
| After the verified copy, before clearing pending | `PENDING` ledger + invisible complete document | Same cleanup; the staged `.part` still exists so the retry re-copies — never a second document [VERIFIED] |
| After clearing pending, before the ledger update | `PENDING` ledger + **visible** complete document | The document is deleted and re-published on retry: one final document, never a partial, never a duplicate [VERIFIED] |
| After the ledger says `PUBLISHED`, before the row delete | `PUBLISHED` ledger + visible document + queue row | Recovery settles the row (deleting any known state) and removes the ledger entry; **no** second document and no re-download [VERIFIED] |
| After the row delete, before ledger/part cleanup | `PUBLISHED` ledger + visible document, no row | Recovery removes only the ledger entry; the artifact is never deleted; the task-5b sweep removes the now-unclaimed `.part` [VERIFIED] |
| Document vanished (user/OS removed it) | `PUBLISHED` ledger, doc gone, row present | Reported; the ledger entry is dropped and the row retries with a fresh document (nothing is guessed) [VERIFIED] |
| Ledger cannot delete the document (resolver unavailable/refuses) | `PENDING` ledger retained | Reported and retried on the next pass instead of losing the only pointer to an invisible document [VERIFIED] |
| Unreadable ledger file | own file | Reported and deleted; nothing else is touched [VERIFIED] |

Recovery runs on the runtime's single pre-claim hook, **before** task-5a orphan adoption, and only while no lease is live — so an export row can never be published as offline media (the adoption pass additionally refuses any orphan whose queue row has an `exportTarget`). [VERIFIED]

## Destination conflicts

A same-`mediaKey` request for another destination is never silently dropped or overwritten. Under the mutation lock, `DownloadWorkerCoordinator.enqueueChecked` reports `EnqueueAttempt.Duplicate` (same destination — the strict whole-row no-op) or `EnqueueAttempt.DuplicateOtherDestination(existingExportTarget)`; `DownloadEnqueuer` maps the latter to `DownloadEnqueueResult.NeedsDestinationChange` with nothing written. `DownloadWorkerCoordinator.convertDestination(rowId, exportTarget)` is the explicit command: it refuses a row a worker owns and otherwise rewrites only `exportTarget` (never the scheduling anchor or any other field). A completed item needs no conversion — its queue row is gone, so an export enqueue creates a fresh row that copies the published offline file. [VERIFIED]

## Permissions and platform boundary

minSdk 29 means an app-owned `MediaStore.Downloads` document needs no storage permission. The export path adds no permission and has no legacy pre-29 branch; the merged manifest is asserted to carry no `WRITE_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`, no `ACCESS_MEDIA_LOCATION`, and `READ_EXTERNAL_STORAGE` only with `maxSdkVersion=32` for the pre-existing local-library read, with no `requestLegacyExternalStorage` flag. [VERIFIED JVM manifest test]

## Deliberately out of scope

Automatic-rule expansion/pruning (tasks 7–8) never sees a public export row — export rows are not automatic and are not `offline_media`; the downloads UI and its conversion/export entry points are task 9. No Files-app/Downloads-app presentation, collision suffixing, volume behavior or removable-volume export is claimed here. [UNKNOWN device]

## Evidence

[VERIFIED JVM 2026-11-20] `:core-data` **687/0/0/0** (`ExportNamingTest` 11, `ExportSessionJournalTest` 8, `DownloadExportPublisherTest` 18, `DownloadExportTransferTest` 6, `DownloadCompletionRecoveryTest` 12 incl. the export-owned orphan guard; download package **281**); `:feature-downloads` **18/0/0/0** (export recovery before orphan adoption through the real runtime); `:app` debug **167/0/0/0** + release **41/0/0/0** (manifest no-broad-permission case); `:app:assembleDebug` green; `lintDebug` on `:core-data`, `:feature-downloads`, `:app` exit 0 with the two pre-existing `UsableSpace` warnings only. No schema change (`Migrations.ALL = listOf(MIGRATION_1_2)`, `1.json`/`2.json` untouched). Banned-name scan exit 0; 22-name public-name diff vs `theme_keys.json` + THEMING.md §4 clean. All new tests confirmed in XML. **Not committed.**
