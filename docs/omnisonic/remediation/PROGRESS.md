# Pre-Phase-5 remediation — final state

Purpose: Record the closed pre-Phase-5 remediation pass (FIX-01 through FIX-15) and the evidence that closed it.

How derived: direct code inspection, focused test runs, and the full Gradle gate recorded below. Status entries are [VERIFIED] by those runs; untested paths are tagged [UNKNOWN].

Date: 2026-11-07

Links: `../decisions.md` (D-10, D-11, column vocabulary), `../storage/schema-v2.md`, `../storage/outbox-contract.md`, `../protocol/scrobble-timestamps.md`.

---

## Outcome

All fifteen remediation items are complete. No Phase-5 work started.

| Item | Area | Resolution | State |
| --- | --- | --- | --- |
| FIX-01 | Sync prune / SQL bounds | `lastSeenSyncId` run stamps plus chunked SQL (`...Chunk` methods, 500-row batches); no statement exceeds the SQLite 3.22 bind limit | [VERIFIED] |
| FIX-02 | Sync prune safety | Prune skipped on an empty seen-set, a reported song count below half the known count, or any failed album fetch; NULL-stamp rows join the unseen set behind those guards | [VERIFIED] |
| FIX-03 | Scrobble submission | `submitPlays` stops at the first rejected timestamp and returns the accepted prefix | [VERIFIED] |
| FIX-04 | Outbox races | Completion and progress are conditional on the submitted payload; a value enqueued during a submission survives or wins | [VERIFIED] |
| FIX-05 | Outbox play volume | No 100-timestamp cap; plays submit chronologically in chunks and resume after a failed chunk without loss or duplication | [VERIFIED] |
| FIX-06 | Tombstone revival | `updateServerFields` clears `missingOnServer`; a re-reported song returns with local state intact | [VERIFIED] |
| FIX-07 | TLS | A trust-anchor-only connection builds a custom SSLContext instead of falling back to platform trust | [VERIFIED] |
| FIX-08 | Provider capabilities | Capabilities initialize from the connection, so backdating and explicit overrides work before the first probe | [VERIFIED] |
| FIX-09 | Production wiring | Registry-owned HTTP client reaches production providers; learned failover blocks persist; no double client construction | [VERIFIED] |
| FIX-10 | Auth errors | Authentication/authorization failures surface as `ProviderAuthException` and do not retry as transient errors | [VERIFIED] |
| FIX-11 | Media session | Service discoverable through the media3 session action, `sessionActivity` set, notification provider wired | [VERIFIED] |
| FIX-12 | zstd negotiation | `Accept-Encoding` advertises zstd only when the JNI library loaded; gzip-only otherwise | [VERIFIED] |
| FIX-13 | Disclosure | Banned-name scanner reports `path:line` only; derivation notes rewritten without leaking the studied name | [VERIFIED] |
| FIX-14 | Startup / cache / timeouts | No `runBlocking` on the service boot path; Coil cover key and fetcher registered; provider read timeout persisted and clamped | [VERIFIED] |
| FIX-15 | Schema vocabulary | One clean exported v1 baseline, obsolete migration chain removed, banned-column scanner added | [VERIFIED] |

## FIX-15 — clean v1 baseline (D-11 option B)

No released database exists, so the pre-release `1→2→3` migration chain protected nothing. It was deleted together with its old schema exports and scaffold test.

- `OmnisonicDatabase` is version 1; `Migrations.ALL` is empty. Future schema changes start a real v1→v2 migration.
- `core-data/schemas/.../1.json` is the exported baseline. It contains the final column labels, the four `lastSeenSyncId` stamps, `queue_state.positionMs`, and the `play_events_daily` view.
- Eleven legacy UI-facing labels were replaced by the durable vocabulary: `latinTitle`, `providerPayloadJson`, `position`, `origin`, `rank`, `discNamesJson`, `resumeTrackPath`, `resumeMs`, `windowStartMs`, `windowEndMs`, `ruleJson`. The old spellings are banned by `BannedColumnScannerTest` and are not repeated here. Generic names (`id`, `name`, `path`, `title`, `sortTitle`, …) are unchanged. The outbox JSON field also uses `resumeMs`; no released payload exists.
- `MigrationHarnessTest` seeds the exported v1 schema, runs `Migrations.ALL` (today the identity path), validates against `1.json`, reopens through the production factory, and exercises the production triggers. It does not invent a v2 schema.
- `BannedColumnScannerTest` scans source, SQL, exported schemas and docs for the old labels. The list lives in local Git config (`omnisonic.bannedColumns`) with an `OMNISONIC_BANNED_COLUMNS` CI override, mirroring the studied-name scanner. Hits are reported as `path:line` only.
- `SyncColumnOwnershipTest` classifies the renamed columns under the same KEY/SERVER/LOCAL rules.

## Verification

Focused suites: `MigrationHarnessTest`, `BannedColumnScannerTest`, `SyncColumnOwnershipTest`, `DaoSqlCompatibilityTest`, `DaoSqlBoundedListTest`, `DaoChunkedOperationsTest` — see the gate record below.

Full gate (2026-11-07):

```
./gradlew test assembleDebug assembleRelease lint
BUILD SUCCESSFUL in 3m 42s (1,438 tasks)
```

- Unit tests: 666 debug + 666 release = 1,332 results, 0 failures, 0 errors, 4 skipped per variant (`provider-navidrome`'s existing skips).
- `assembleDebug` and `assembleRelease`: successful.
- `lint`: successful across all modules.
- Focused FIX-15 suites: `MigrationHarnessTest`, `BannedColumnScannerTest`, `SyncColumnOwnershipTest`, `DaoSqlCompatibilityTest`, `DaoSqlBoundedListTest`, `DaoChunkedOperationsTest` — 27 tests, 0 failures.
- Source-tree scan (`git ls-files` plus untracked files, local pattern config): exit 0, no hits.
- Debug APK scan (all extracted entries, studied-name pattern and banned-column pattern): exit 0, no hits.
- `1.json` export verified to contain all eleven durable labels, `lastSeenSyncId`, `queue_state.positionMs`, and the `play_events_daily` view.

## Remaining [UNKNOWN] items

- [UNKNOWN] Behavior against a live Navidrome server (needs a server).
- [UNKNOWN] Real-device network gate and mTLS behavior (no device attached).
- [UNKNOWN] Cold-start before/after delta for FIX-14 (no device attached).
- [UNKNOWN] Live/hung-provider read-timeout surfacing end to end (mapping and socket-timeout tests exist).
- [UNKNOWN] Real on-device cover fetch through the routed call factory with provider headers and mTLS.
- [UNKNOWN] Default `PRAGMA recursive_triggers` on real devices (Robolectric reports 1 in memory, 0 file-backed; the app never sets it and no DAO uses REPLACE).
- [UNKNOWN] Two concurrent full syncs of one provider have no lease; run ids stay distinct but walks interleave.
- [UNKNOWN] Server-side duplicate scrobble handling (outside remediation scope).
