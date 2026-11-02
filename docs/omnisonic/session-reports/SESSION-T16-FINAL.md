# SESSION T16-5 — FINAL: single v1→v2 migration and verification

Date: 2026-11-02
Status: complete. One explicit `Migration(1, 2)`; `SCHEMA_VERSION = 2`; `2.json`
exported; no destructive fallback; T16 code and tests green.

Branch: `main`, 13 commits ahead of `origin/main` at session start, not pushed.
Working tree at start carried the intentional incomplete Session 2–4 changes; no
user changes needed preserving.

## 1. Exit criteria

| Criterion | Status | Evidence |
|---|---|---|
| `SCHEMA_VERSION = 2` | [VERIFIED] | `OmnisonicDatabase.SCHEMA_VERSION` |
| `2.json` committed | [VERIFIED] | `core-data/schemas/.../2.json`, identity `ad813ac81d5569ad7fd7bcee7472f51c` |
| `1.json` untouched | [VERIFIED] | `git status` reports no change to `1.json` |
| One explicit SQL step 1→2 | [VERIFIED] | `Migrations.ALL == [MIGRATION_1_2]` |
| No `fallbackToDestructiveMigration` | [VERIFIED] | both builders in `OmnisonicDatabaseFactory` |
| Real migration test | [VERIFIED] | `MigrationHarnessTest.v1SchemaMigratesToV2WithDefaultsBackfillHistoryAndCascade` |
| `PRAGMA recursive_triggers` stays off | [VERIFIED] | migration test asserts `0` |
| Provider edit keeps data | [VERIFIED] | migration test updates a provider and re-counts children |
| Provider delete removes `download_items` | [VERIFIED] | migration test deletes provider 42 and asserts 0 downloads |
| Wi-Fi-only blocks sync/drain | [VERIFIED] | `ProviderStatusTrackerTest`, `SubsonicLibrarySyncerTest`, `OutboxDrainerTest`, `DownloadEnqueuerTest` |
| mTLS through mapper → real handshake | [VERIFIED] | `MtlsEndToEndHandshakeTest` (both MEDIA and API profiles) |
| `DaoSqlCompatibilityTest` green | [VERIFIED] | part of the full test run |
| Docs stubs updated | [VERIFIED] | `docs/omnisonic/storage/` (`schema-v2.md` added) |

## 2. Schema delta

Tables added: `queue_entries`, `queue_state`, `play_events`.
Table removed: `playback_events` (copied into `play_events`).
View added: `play_events_daily`.

`providers`:

| Column | Change |
|---|---|
| `kind` | added `TEXT NOT NULL DEFAULT 'REMOTE_SERVER'` |
| `wifiOnly` | added `INTEGER NOT NULL DEFAULT 0` |
| `mtlsEnabled` | added `INTEGER NOT NULL DEFAULT 0` |
| `mtlsKeystoreBlob` | added `TEXT NULL` |
| `mtlsAlias` | added `TEXT NULL` |
| `trustedCertsBlob` | added `TEXT NULL` |
| `primaryScheme` | `NOT NULL` → nullable |
| `primaryHost` | `NOT NULL` → nullable |
| `primaryPort` | `NOT NULL` → nullable |
| `primaryPath` | `NOT NULL` → nullable |

`download_items`:

| Column | Change |
|---|---|
| `providerId` | added `INTEGER NOT NULL` (indexed) |
| `tempCache` | added `INTEGER NOT NULL` (no SQL default) |
| `exportTarget` | added `TEXT NULL` |
| `autoRuleId` | added `INTEGER NULL` |

Indexes: `index_download_items_providerId`, `index_play_events_providerId_externalId_playedAtMs`,
`index_play_events_providerId_dayStartMs`, plus the queue-entry unique/position/provider
indexes. Cascade triggers grew from 16 to 17 provider-delete triggers plus the
playlist-entry trigger.

## 3. Migration mechanics

1. Rebuild `providers`; old rows get `kind = 'REMOTE_SERVER'`, `wifiOnly = 0`,
   `mtlsEnabled = 0`; primary and secondary endpoint values are copied verbatim.
2. Rebuild `download_items`; `providerId` is parsed from
   `stream:<providerId>:<externalId>:<bitrate|auto>:<format|auto>` via
   `DownloadKeys.providerIdFromMediaKey`.
3. Create `queue_entries`, `queue_state`, `play_events`, and the
   `play_events_daily` view.
4. Copy each old merged `playback_events` day row to one `play_events` row:
   `playedAtMs = dayStartMs = old date`; flags and duration unchanged.
5. Drop `playback_events`.
6. Install the provider cascade triggers.

Orphan-download policy (documented in `Migration1To2.kt`): a row whose `mediaKey`
has no reliable provider segment, or whose provider row does not exist, is deleted.
No owner is invented.

`PRAGMA recursive_triggers` is never enabled, so provider edits cannot fire the
cascade. Deletes still fire the top-level triggers.

## 4. Traits and consulted paths

| Trait | Subsonic family | Local | Consulted by |
|---|---|---|---|
| `hasLibrarySync` | true | true | `SubsonicLibrarySyncer.runSync` |
| `supportsDownload` | true | false | `DownloadEnqueuer` (typed `SkippedDownloadUnsupported`) |
| `supportsServerTranscode` | true | false | future transcode planner (Phase 8 seam) |
| `reportsUserData` | true | false | `OutboxQueue.enqueueMediaChange` / `enqueuePlayCountDelta` |

The local provider's user-data methods remain local no-ops; with
`reportsUserData = false`, the outbox enqueue path writes nothing.

## 5. Tests

Full run (`./gradlew test`):

| Module | Debug tests | Release tests | Failures/skips |
|---|---|---|---|
| core-data | 147 | 147 | 0 / 0 |
| playback | 130 | 130 | 0 / 0 |
| provider-api | 50 | 50 | 0 / 0 |
| provider-navidrome | 26 | 26 | 0 / 4 live-server assumptions |
| core-model | 18 | 18 | 0 / 0 |
| provider-local | 12 | 12 | 0 / 0 |
| core-network | 10 | 10 | 0 / 0 |
| playback-api | 10 | 10 | 0 / 0 |
| app | 4 | 4 | 0 / 0 |
| **Total** | **407** | **407** | **0 failures; 4 skipped (live Navidrome)** |

The migration test seeds a v1 database from `1.json` with two providers (one with a
secondary block), a song, an outbox row, three download rows (one valid, one
unparseable key, one missing provider), and a `playback_events` row. After migration
it asserts preserved values, defaults, backfill/orphan deletion, history/view
contents, queue restore without a `songs` row, `recursive_triggers = 0`, edit
safety, new-row defaults, trigger count, and cascade deletion.

## 6. Verification commands

```
./gradlew test                     # BUILD SUCCESSFUL
./gradlew lint :app:assembleDebug   # BUILD SUCCESSFUL
scripts/check-banned-names.sh <changed files>  # clean
```

Incidental fixes required to make lint green:

- `core-data/src/main/AndroidManifest.xml` now declares `ACCESS_NETWORK_STATE`
  (the library uses `ConnectivityManager`; lint analyzes modules independently).
- Media3 `@UnstableApi` opt-in annotations were added to the playback audio/cache/
  engine/player classes that already used unstable Media3 APIs. Behaviour is
  unchanged; lint had been failing on these pre-existing errors.

## 7. Follow-ups

- T17 remains separate: replace REPLACE-style upserts with Room `@Upsert`, and
  cover `ensureSong` explicitly.
- Phase 4 wiring still owns automatic queue save/restore and sync/drain callers.
- Phase 7/8 wiring still owns the download pipeline and transcode planner.
- Live Navidrome behaviour remains untested (4 skipped assumptions).

Docs: `docs/omnisonic/storage/schema-v2.md`,
`library-sync-ownership.md`, `sqlite-api29-floor.md`,
`aggregate-null-semantics.md`.
