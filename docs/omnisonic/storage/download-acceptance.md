# Phase 7 download acceptance and close — `download-acceptance.md`

Purpose: Consolidate the Phase 7 TODO 10 gate, the behavior actually asserted by the JVM acceptance tests, the schema and clean-room audits, and the device/live checks that remain open. This document closes Phase 7; it does not start Phase 8.

How derived: One exact serial `./gradlew --no-parallel :app:assembleDebug test lint` invocation, per-class JUnit XML parsing, direct reading of the named test assertions and their production paths, inspection of the Room migrations/exports/harness, the banned-name scanner, and comparison of new public names with `reference/extracted/theme/theme_keys.json` plus `reference/docs/THEMING.md` §4. Device availability was checked with `adb devices`; live-test environment variable presence was checked without printing values.

Confidence:
- [VERIFIED] The build, lint, XML totals, schema/harness and clean-room checks below are green.
- [VERIFIED] The acceptance section distinguishes concrete JVM assertions from device or live-server behavior; it is not a claim that temporary directories, Robolectric or MockWebServer emulate hardware.
- [UNKNOWN] Every item under Device and live acceptance remains open for the stated reason.

Date: 2026-11-21

Links: `download-queue-lifecycle.md`, `download-transfer.md`, `download-completion.md`, `offline-file-integrity.md`, `offline-storage-layout.md`, `export-publication.md`, `automatic-rules.md`, `schema-v2.md`, `../playback/offline-resolution.md`, `../ui/downloads-workspace.md`, `../decisions.md` (D-39…D-50), `.agents/plans/phase-7-tasks.md` TODO 10.

---

## Starting state and exact gate

[VERIFIED] TODO 10 started on branch `main` at `8d6d23e9091d735a83f46321be1b34528bdb1ebf`. The initial status contained 37 modified tracked paths and 42 untracked paths (79 total). The tracked diff stat was 37 files, 1,222 insertions and 75 deletions. Every Phase 7 path was preserved; wrap-up changed records/documentation only. All 80 paths were subsequently committed in `1311f803b9d644ea35442894599b221fcc25676d`; no push was performed.

[VERIFIED] The exact invocation exited 0 with `BUILD SUCCESSFUL`. Its complete output is `/tmp/opencode/phase-7-task-10-gate.log`; parsed totals are `/tmp/opencode/phase-7-task-10-xml-totals.txt`. Numbers are `tests / skipped / failures / errors` from the result XML, not task-success inference.

| Module | Debug | Release |
|---|---:|---:|
| `:app` | 169 / 0 / 0 / 0 | 41 / 0 / 0 / 0 |
| `:core-data` | 781 / 0 / 0 / 0 | 781 / 0 / 0 / 0 |
| `:core-model` | 26 / 0 / 0 / 0 | 26 / 0 / 0 / 0 |
| `:core-network` | 25 / 0 / 0 / 0 | 25 / 0 / 0 / 0 |
| `:feature-downloads` | 31 / 0 / 0 / 0 | 31 / 0 / 0 / 0 |
| `:feature-library` | 145 / 0 / 0 / 0 | 145 / 0 / 0 / 0 |
| `:feature-search` | 26 / 0 / 0 / 0 | 26 / 0 / 0 / 0 |
| `:feature-settings` | 70 / 0 / 0 / 0 | 70 / 0 / 0 / 0 |
| `:playback` | 314 / 0 / 0 / 0 | 314 / 0 / 0 / 0 |
| `:playback-api` | 13 / 0 / 0 / 0 | 13 / 0 / 0 / 0 |
| `:provider-api` | 67 / 0 / 0 / 0 | 67 / 0 / 0 / 0 |
| `:provider-local` | 12 / 0 / 0 / 0 | 12 / 0 / 0 / 0 |
| `:provider-navidrome` | 43 / 4 / 0 / 0 | 43 / 4 / 0 / 0 |
| `:style` | 55 / 0 / 0 / 0 | 55 / 0 / 0 / 0 |
| **Total** | **1,777 / 4 / 0 / 0** | **1,649 / 4 / 0 / 0** |

The grand total is **3,426 results, 8 skips, 0 failures and 0 errors** across 397 XML files. The 128-result variant delta is exactly the `:app` debug/release difference (169 − 41), caused by the documented release `**/shell/**` exclusion in `app/build.gradle.kts`; all other module totals match across variants. The four skips per variant are `NavidromeLiveAcceptanceTest`'s gated probe/walk, failover, favorite-toggle and bookmark-create/delete cases.

Key debug XML counts, duplicated exactly in release, are `AutomaticRuleReactionTest` 18, `AutomaticRulePruningTest` 19, `DownloadWorkerCoordinatorTest` 43, `AutomaticRuleExpanderTest` 17, `MigrationHarnessTest` 5, `ForegroundDownloadRuntimeTest` 19 and `DownloadsViewModelTest` 7.

## Schema, migration harness and clean-room audit

[VERIFIED] `OmnisonicDatabase.SCHEMA_VERSION = 3`; `Migrations.ALL = listOf(MIGRATION_1_2, MIGRATION_2_3)` in that order. `MIGRATION_2_3` adds only `download_rules` and its unique `(providerId, kind, targetExternalId)` index. The exported `3.json` matches the v3 entities/indexes. `MigrationHarnessTest`'s five cases assert the ordered migration list, seeded v1 → v2 → v3 preservation/backfill, seeded v2 → v3 preservation/rule uniqueness/favorites sentinel, and production-factory reopen/trigger installation. `1.json` and `2.json` are byte-identical to `HEAD`; only `3.json` is new. The seeded v2 harness case does not itself delete a provider: the fresh-database case checks trigger installation, while `AutomaticRuleRepositoryTest > provider deletion cascades its rules together with queue and offline rows` proves the actual cascade. The harness KDoc was corrected to state that split; no assertion changed.

[VERIFIED] `scripts/check-banned-names.sh` exited 0 over the initial 79-path Phase 7 superset. The declaration/resource diff covered 66 new top-level public types, three top-level composables, eight public nested variants and 120 Android resource names, plus changed existing public members and test sources. There are no exact theme-key collisions and no distinctive §4 kept-model/config-name collisions. The only §4 lexical overlaps are the generic property words `title`, `tracks`, `playlists` and `albums`, which are permitted generic vocabulary rather than distinctive copied names. TODO 10 adds no production declaration. After adding this acceptance document, the final documentation-inclusive scan also exited 0 over all 80 Phase 7 paths later included in that commit.

## JVM acceptance evidence

The following entries were checked against their assertions. XML presence/pass status for the principal cases is saved in `/tmp/opencode/phase-7-task-10-acceptance-xml.txt`.

### Interrupted transfer and complete exported bytes [VERIFIED]

`DownloadExportTransferTest > a resumed transfer exports the checksum of the whole file` seeds 256 bytes of a 512-byte payload, asserts `Range: bytes=256-` and a validated 206 response, then requires the exported bytes to equal all 512 original bytes, pending to clear, the queue row to delete and the staged `.part` to disappear. Its fake document store compares content, not a digest; `MediaStoreExportDocumentStoreTest > the real adapter truncates writes and verifies the read-back digest` supplies the complementary real-adapter SHA-256/read-back assertion. Thus whole-file resumed content and digest verification are proven by two tests, not one end-to-end resumed MediaStore digest test. `DownloadTransferEngineTest` separately asserts validated 206 append, clean restart behavior for invalid resume responses and short-body refusal: a truthful partial `.part` can survive for retry, but it cannot finalize. This proves the client contract against fixtures, not a live server's range implementation.

### Three slow parallel workers [VERIFIED]

`DownloadTransferEngineTest > three slow workers run under the cap without sharing a file` uses a three-request latch so serial execution cannot succeed, asserts a fourth claim is null, and requires three completed, distinct result paths whose bytes match their song-specific payloads. Coordinator tests separately assert cap 2 admits two claims and a release admits exactly one replacement, plus eight concurrent claims against cap 3 yield exactly three leases. `DownloadTransferIntegrationTest` observes maximum concurrent handlers exactly 3 with three complete 2,048-byte files. This is concurrency/accounting evidence, not live-server throughput.

### Low space and write failure [VERIFIED, bounded]

`disk exhaustion during a write blocks without counting` injects free space below the floor and asserts `Blocked(STORAGE_LOW_SPACE)`, a `PENDING` row with `errorCount = 0`, and no final file. Separate preflight cases assert no media resolution/HTTP request before storage readiness and no part/final file when a known body does not fit. `a file open failure counts without finalizing` makes the `.part` path a directory and asserts `Retry(WRITE_FAILED, PENDING)`, `errorCount = 1`, no verification sidecar and no final file. These outcomes return through normal coordinator settlement; lease caps/releases are asserted by the coordinator tests above. A genuine mid-write `ENOSPC` on a real volume remains separate and unknown.

### Rule removal and overlapping ownership [VERIFIED]

`AutomaticRulePruningTest > removing one overlapping rule prunes only its unshared rolling rows and files` removes Favorites while retaining a playlist rule and asserts exactly one queue cancellation: the shared queue/offline rows and shared file survive while the exclusive automatic rolling rows/file are removed. That overlap case uses a direct-DAO cancellation lambda; production conditional coordinator ownership is proven separately by `DownloadWorkerCoordinatorTest > automatic pruning cancellation refuses rows whose ownership changed` and by the active-transfer cancellation test through a real coordinator. Other pruning tests assert fresh-row revalidation and file-before-row deletion. A failed file delete leaves the offline row retryable; manual, permanent, exported, wanted, other-provider and mismatched/unknown identities are protected.

### Offline playback without a provider [VERIFIED]

`OfflineFirstStreamResolverTest > an offline file plays even when the provider is unavailable` models provider unavailability as a null provider URL and asserts that the offline file URI wins with `requiresNetwork = false`; production resolves offline before invoking the online resolver. This test does not inject a provider exception or count provider invocations. The direct-source assertions check a percent-encoded `file://` URI, `DIRECT_URI`, `requiresNetwork = false`, `isRadio = false`, `startPositionMs = 0`, null expiry and the offline row's variant `cacheKey`; player tests reach ready/natural completion and assert no streaming-cache population. Hardware airplane mode is not inferred from these assertions.

### Retry parking and sticky user pause [VERIFIED]

`DownloadTransferEngineTest > the sixth counted failure parks the row` performs six HTTP-500 attempts and asserts `FAILED`, `errorCount = 6` and no further claim. The coordinator retry test also asserts retained progress, sanitized error state, and explicit retry to `PENDING` with count 0 and retained progress. Coordinator sticky-pause cases are the production proof that resume-all releases global `PAUSED` rows but leaves `USER_PAUSED` and `FAILED` rows untouched. `DownloadsViewModelTest` proves call forwarding and sticky UI preservation; its fake leaves the ordinary row `PAUSED`, so it is not the production transition proof. Workspace rendering marks the sixth-failure row parked and exposes retry.

### Root-move failure and rollback [VERIFIED JVM policy]

`OfflineRootMigrationTest` asserts that low target space leaves the old root authoritative and its original bytes intact with ordered gate pause/resume and no pending journal, an interrupted move whose target vanished restores the old root and clears pending state, and a symlinked source entry aborts without deleting the original regular source file. The current debug XML records all three as passed, not skipped; the symlink case retains a platform `assumeTrue` guard for unsupported filesystems. The tests inspect persisted journal/root state and real temporary files. They do not establish removable-volume, power-loss or hardware fault behavior.

### Cancellation and test-only service bindings [VERIFIED]

Automatic queue cancellation goes through `DownloadWorkerCoordinator.cancelIfAutomaticRolling` under the same roster lock as destination conversion; offline pruning resolves and deletes the file before deleting its row. `FakeDownloadServiceModule` exists only under `app/src/test`; production service/download bindings remain in main source sets and no production binding references the fake.

## Device and live acceptance [UNKNOWN]

- **FGS background starts, Doze, `dataSync` timeout and OEM freezing:** `adb devices` returned no attached device, so no platform trace exists.
- **Notification-permission denial and notification/deep-link feel:** no attached device was available; Robolectric intent/notification assertions are not platform feel or permission-denial evidence.
- **Cold `MainActivity.onCreate` intent → navigation:** the manifest intent and warm request path are tested, but no end-to-end cold activity/navigation assertion or device trace exists.
- **TalkBack, layout and reachability:** no attached device or accessibility run was available.
- **Real external-volume move, unmount, writability and low space:** tests use temporary JVM directories; no hardware volume was available.
- **MediaStore Downloads visibility, collision suffixing and `is_music` handling:** Robolectric/fake resolver assertions do not establish the platform Downloads provider or Downloads-app presentation.
- **Live Navidrome byte ranges and three-worker throughput:** `OMNISONIC_NAVIDROME_URL`, `OMNISONIC_NAVIDROME_USER` and `OMNISONIC_NAVIDROME_PASS` were unset, and no live trace was supplied.
- **True mid-write `ENOSPC`:** no real-volume fault injection was available; the JVM proves preflight and modeled write-failure settlement only.
- **Process death during a delete or root move:** JVM interruption/journal tests do not prove hardware process-death timing or filesystem durability.
- **Airplane-mode playback:** no attached device was available to disable radios and play permanent and rolling offline media.

## Phase 8 boundary

[VERIFIED] Phase 7 preserves bitrate/format identity end to end: optional `transcodeBitrateKbps`/`format` on automatic rules and queue rows, `DownloadKeys.mediaKey`, variant-qualified offline paths and `offline_media.sourceKey`, provider download URL/endpoint resolution, and exact-variant offline stream selection. Absent values mean no client-selected override. Phase 8 must supply and plan those values without flattening identity to provider + song alone. Public export artifacts remain user-owned and distinct from private offline-cache ownership.

[VERIFIED] Phase 8 has not started. The next-session prompt is limited to its first `TranscodeSettings` task; the pure planner, server request path, network-aware resolution and device/live acceptance remain subsequent Phase 8 work.
