# Offline storage settings, layout and root changes — `offline-storage-layout.md`

Purpose: pin the persisted download settings (worker count, Wi-Fi-only), the app-private offline file layout, path/extension rules, readiness gates, and the verified root-change contract implemented by Phase 7 task 2.

How derived: own design decision D-40 (user-confirmed 2026-11-19 at the Phase 7 task-2 gate), the task plan `.agents/plans/phase-7-tasks.md` §TODO 2, and the shipped `:core-data` `downloads` package plus its tests. Storage behaviour is reasoned from plain `java.io.File` semantics; no studied-app code or naming is involved.

Confidence: settings bounds/defaults, layout determinism, extension rules, containment rejection, free-space boundaries, root selection and the JVM-visible move/rollback/recovery sequence are [VERIFIED] by JUnit tests. Real volume mount/unmount behaviour, writability on hardware, and cross-volume copy timing remain [UNKNOWN] without a device trace.

Date: 2026-11-20

Links: `storage/download-queue-lifecycle.md`, `storage/download-transfer.md`, `storage/schema-v2.md`, `providers/provider-connections.md`, `.agents/plans/phase-7-tasks.md`, D-40 in `../decisions.md`.

---

## Settings

`DownloadQueueSettingsStore` persists everything in the DataStore file `omnisonic_downloads`. [VERIFIED]

| Setting | Default | Bounds | Encoding |
|---|---|---|---|
| Worker count | 2 | 1–4, clamped on write and on decode | int |
| Wi-Fi-only | on | boolean | boolean |
| Global pause | off | boolean | boolean (task 1) |
| Root selection | `auto` | `auto` or a volume id | string |
| Published root | none | volume id | string |
| Root generation | 0 | non-negative long | long |
| Pending move | none | volume id + selection + generation | strings/long |
| Cleanup volume | none | volume id | string |

- A corrupt stored worker count decodes clamped, so a value of 99 becomes 4 and zero becomes 1. [VERIFIED]
- `awaitReady()` returns the first persisted settings emission; defaults exist for a fresh file only and must not start a worker run. [VERIFIED]
- Every store write runs through one mutex, so a multi-key root transition is not interleaved with a settings write. [VERIFIED]
- A settings/root read failure propagates instead of decoding as fresh defaults. It can never silently clear pause or select and publish the `auto` root over an unavailable published root. Transfer maps a root-journal read failure to an uncounted storage block; if the pause gate is also unreadable during lease release, the row takes the safe `PAUSED` state and the slot is still freed. [VERIFIED JVM 2026-11-20 audit]
- Worker count and Wi-Fi-only are settings only in this task. Enforcement belongs to the task-4 service gate (`wifiOnly` defaults on, as confirmed).

## Root selection

Roots are app-private directories supplied by `OfflineRootSource`; production supplies `getExternalFilesDirs` entries followed by internal `filesDir`. No SAF tree, no broad storage permission, and no arbitrary user-typed path exists. [VERIFIED JVM shape]

- `auto` prefers the primary app-private external root and falls back to internal files when the external root is missing or unwritable. [VERIFIED]
- An explicit volume, or an already-published root, never silently falls back: a missing volume is reported as unavailable, not as an empty library. [VERIFIED]
- The first successful `prepare()` pins the chosen volume as the active root. Later availability changes do not silently repin it; moving to another volume requires an explicit change. [VERIFIED]
- A pinned root that appears in the candidate list but is unwritable is `NOT_WRITABLE`; one that is absent is `ROOT_UNAVAILABLE`. [VERIFIED]
- An untrusted root is never used: `prepare()` verifies the directory, creates `OfflineMedias/` and `.nomedia`, probes writability, then checks free space. [VERIFIED]
- `prepare()` is safe under concurrent workers: a lost `mkdirs` race (the directory exists but this call did not create it) is success, not `NOT_WRITABLE`. [VERIFIED JVM 2026-11-19 — exposed by the Phase 7 task-3 three-worker test]

## Layout and identity

Stored paths are relative to the active root and use forward slashes:

```
OfflineMedias/<first hash char>/<sha-256 hex>.<safe extension>
OfflineMedias/<first hash char>/<sha-256 hex>.part
```

- The hash covers the provider id, the NFC-normalized provider path and the explicit bitrate/format variant, separated by NULs. The same path on two providers, two bitrates or two formats therefore cannot share a file. [VERIFIED]
- The extension is chosen by the explicit transcode format first (with `mpeg→mp3`, `mp4/alac→m4a`, `vorbis→ogg`, `pcm/wave→wav` aliases); passthrough or unknown formats fall back to the source path extension, then to `bin`. Only a known audio extension survives; query/fragment text and non-audio suffixes do not. [VERIFIED]
- The `.part` deliberately has no extension: the final extension may only be known from the response in task 3, while resumable bytes must keep one stable name. It is the deterministic sibling of the final file and is variant-qualified through the same hash. [VERIFIED]
- `.nomedia` is created inside `OfflineMedias/`, hiding the whole managed tree from media scanning. [VERIFIED]
- Unicode paths are NFC-normalized, so canonically equivalent spellings share one file; very long provider paths still produce short file names; colon-containing ids are hashed, never used literally. [VERIFIED]
- A hash collision is refused rather than overwritten: callers pass the assigned `relativePath → identityKey` map, and a different identity at an assigned path returns `Collision`. [VERIFIED]
- Stored paths reject blank values, absolute paths, drive-qualified paths, backslashes, control characters, empty segments, `.`/`..` segments and over-long values before touching the filesystem. [VERIFIED]
- Resolution canonicalizes the candidate under the canonical root; a symlinked intermediate directory that escapes the root returns null. [VERIFIED]

## Readiness gates

- The free-space floor is 100 MiB: `usableBytes >= 100 MiB + requiredBytes` is required. Exactly at the floor (with zero required bytes) is ready; one byte below is `LOW_SPACE`. [VERIFIED]
- An unwritable root is blocked before paths are planned or any network work starts. [VERIFIED]
- Blocked storage is a typed state (`ROOT_UNAVAILABLE`, `NOT_WRITABLE`, `LOW_SPACE`), never an empty library or a zero-byte success. The task-4 service keeps rows `PENDING` on these blocks without an error increment.

## Root change and recovery

The confirmed contract is move, never silent delete (D-40). [VERIFIED JVM]

1. Resolve the target and the published source before pausing. A missing target fails before any worker pause; a missing source fails without treating the old library as empty.
2. Pause worker claims through `OfflineRootWorkGate`; the old root remains authoritative for the whole copy phase.
3. Journal the pending target, selection and generation before copying.
4. Copy each regular file to a sibling temporary file, flush and `sync`, verify the SHA-256 digest, then rename into place. Exclude `.nomedia` (recreated at the target). Nested directories are preserved. Symlinks, non-regular entries and unsafe relative names abort the change as `INVALID_TREE`.
5. An existing target file is adopted only when its content digest matches; a different file at the same relative path aborts with `DESTINATION_CONFLICT`.
6. After every copy verifies, commit the publication in one settings edit: selection, active volume, new generation, pending cleared, cleanup volume recorded. Only now is the new root authoritative.
7. Delete the old copies while still paused, then clear the cleanup field. Deletion failure leaves the cleanup volume recorded for the next startup instead of failing the publication.
8. Always resume the work gate in a non-cancellable `finally`.

Failure before the commit deletes only files created by this attempt, clears the pending journal and leaves the old root active. Cancellation leaves the pending journal in place; recovery re-runs idempotently because already-verified target files are adopted by digest. [VERIFIED]

Startup recovery runs before workers:

- A pending move is re-run; an unavailable target rolls the journal back and keeps the old root authoritative.
- A published move with a recorded cleanup volume finishes deleting the old media tree only while the published target volume and its media directory are available. An unavailable target leaves both the old copy and cleanup journal intact. [VERIFIED JVM 2026-11-20 audit]
- With neither field set it is a no-op.

An unavailable root never maps to an empty path list, an empty library or a delete sweep. [VERIFIED JVM policy; device volume behaviour UNKNOWN]

Task 4's `OfflineRootWorkGate` must stop new claims and drain active transfers before publication/cleanup; the task-2 gate interface alone does not make an active file write safe. This is a service-construction requirement, not a change to stored paths or the journal. [INFERRED — service tests belong to task 4]

## Deliberately out of scope

HTTP transfer and `.part` writes, the foreground service/notification, startup network triggers, MediaStore export, automatic rules, downloads UI/settings surface, and any schema migration are later tasks. `download_queue`/`offline_media` are untouched; `Migrations.ALL` remains empty. [VERIFIED]

## Evidence

Original task evidence: `:core-data:testDebugUnitTest` **507/0/0/0**; `:app:assembleDebug` green. The 2026-11-20 audit added fail-closed DataStore read coverage and unavailable-target cleanup recovery, and strengthened concurrent settings-write accounting. Final audit evidence is `:core-data` **582/0/0/0**, download package **177/0/0/0**, with `DownloadSettingsStoreTest` **10** and `OfflineRootMigrationTest` **13**. Real cross-volume timing and mount behavior remain device-gated; JVM roots in one temporary tree prove copy/publication policy, not removable-volume behavior. No exported Room baseline changed. [VERIFIED JVM 2026-11-20]
