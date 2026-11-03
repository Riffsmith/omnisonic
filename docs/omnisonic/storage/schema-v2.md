# Schema v2 — `schema-v2.md`

Purpose: Record the single pre-release v1→v2 Room migration shipped in T16 and
amended in T24 (clean-room table renames), the column and table deltas it
carries, and the ownership/materialisation rules that depend on it.

How derived: `core-data/schemas/app.omnisonic.core.data.db.OmnisonicDatabase/1.json`
and `2.json`, `Migration1To2.kt`, `ProviderCascadeTriggers.kt`, and the migration
harness test. The studied app is not a source.

Confidence:
- [VERIFIED] `OmnisonicDatabase.SCHEMA_VERSION = 2`; `Migrations.ALL` contains exactly
  one `Migration(1, 2)`; no destructive fallback exists in either database builder.
- [VERIFIED] `1.json` is unchanged from the pre-T16 baseline; `2.json` is the KSP
  export for version 2 (regenerated with the T24 table names).
- [VERIFIED] `MigrationHarnessTest` seeds a real v1 database from `1.json`, runs the
  migration, validates against `2.json`, and checks data, defaults, history, queue
  restore, the renamed tables' data survival, trigger names, index names, and
  cascade behaviour.
- [UNKNOWN] Behaviour of the migration on a large production database — there is no
  released v1 and no such database in this repository.

Date: 2026-11-03

Links: `sqlite-api29-floor.md`, `library-sync-ownership.md`,
`aggregate-null-semantics.md`, `../session-reports/SESSION-T16-FINAL.md`

---

## Version and migration

[VERIFIED] The upgrade is one explicit step:

```kotlin
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) { ... }
}
```

It tears down the v1 cascade triggers, rebuilds `providers`, rebuilds
`download_items` as `download_queue`, renames `playlists_entries` →
`playlist_items`, `internet_radios` → `radio_stations`, `offline_files` →
`offline_media`, and `sync_status` → `sync_state`, creates `queue_entries`,
`queue_state`, `play_events`, and the `play_events_daily` view, copies old
`playback_events` rows into `play_events`, drops `playback_events`, and installs
the provider cascade triggers under the v2 names. `PRAGMA recursive_triggers` is
never enabled.

## Clean-room table renames (T24)

Approved by the user in the T23/T24 session (option C: folded into the v2
migration; precondition verified — no v2 database was ever installed on any
device, `adb devices` empty and no install recorded in the project history).

| v1 name | v2 name |
|---|---|
| `playlists_entries` | `playlist_items` |
| `internet_radios` | `radio_stations` |
| `download_items` | `download_queue` (rebuilt) |
| `offline_files` | `offline_media` |
| `sync_status` | `sync_state` |

[VERIFIED] SQLite keeps table data attached through `ALTER TABLE RENAME`, but
index names keep the old prefix, so the migration recreates every affected index
under the v2 name (e.g. `index_playlist_items_providerId`). The migration test
asserts the new index names exist and the old ones are gone.

## Tables added

| Table | Purpose |
|---|---|
| `queue_entries` | Queue metadata snapshot; restores without a `songs` row |
| `queue_state` | Single-row play index, shuffle order/seed, repeat/speed/pitch, source |
| `play_events` | One row per play or skip (T22 option b) |

`play_events_daily` is a view grouped by `(providerId, externalId, dayStartMs)`
exposing `date`, `MAX(played)`, `MAX(skipped)`, `SUM(durationMs)`, and
`COUNT(*) AS playCount`.

## Tables removed

| Table | Replacement |
|---|---|
| `playback_events` | `play_events`; each old merged day row becomes one play event |

The original per-play count of an old merged day row cannot be recovered; the
migration copies `played`, `skipped`, and `durationMs` unchanged and sets both
`playedAtMs` and `dayStartMs` to the old `date`.

## Columns added or changed

`providers`:

| Column | Change | Notes |
|---|---|---|
| `kind` | added `TEXT NOT NULL DEFAULT 'REMOTE_SERVER'` | Old rows become `REMOTE_SERVER` |
| `wifiOnly` | added `INTEGER NOT NULL DEFAULT 0` | Wi-Fi-only gate |
| `mtlsEnabled` | added `INTEGER NOT NULL DEFAULT 0` | mTLS is opt-in; blobs alone cannot enable it |
| `mtlsKeystoreBlob` | added `TEXT NULL` | Sealed PKCS#12 |
| `mtlsAlias` | added `TEXT NULL` | Non-secret alias |
| `trustedCertsBlob` | added `TEXT NULL` | Sealed JSON array of base64 DER certs |
| `primaryScheme` | `NOT NULL` → nullable | `DEVICE`/`VIRTUAL` have no server endpoint |
| `primaryHost` | `NOT NULL` → nullable | |
| `primaryPort` | `NOT NULL` → nullable | |
| `primaryPath` | `NOT NULL` → nullable | |

`download_queue` (v1 name `download_items`; renamed, rebuilt):

| Column | Change | Notes |
|---|---|---|
| `providerId` | added `INTEGER NOT NULL` | Backfilled from `mediaKey`; no default owner |
| `tempCache` | added `INTEGER NOT NULL` | No SQL default; new rows must choose |
| `exportTarget` | added `TEXT NULL` | e.g. `MEDIASTORE_DOWNLOADS` |
| `autoRuleId` | added `INTEGER NULL` | Future automatic-download rule |

A `(providerId)` index was added alongside the existing unique `mediaKey` and
`status` indexes.

## Download orphan policy

[VERIFIED] `mediaKey` is `stream:<providerId>:<externalId>:<maxBitRateKbps|auto>:
<format|auto>`. The migration parses the numeric provider segment with
`DownloadKeys.providerIdFromMediaKey` and keeps only rows whose provider row
exists. A row with no reliable provider segment, or one pointing at a missing
provider, is deleted. The migration never invents an owner.

## Provider kinds and song materialization

[VERIFIED] `kind` is stored by name: `REMOTE_SERVER`, `DEVICE`, `VIRTUAL`.
`REMOTE_SERVER` requires a valid primary block in code; `DEVICE` and `VIRTUAL`
load through `ProviderEndpoint.None`. See `library-sync-ownership.md` for the
column-ownership model.

[VERIFIED] `SongRepository.ensureSong` inserts a minimal song row for an unsynced
`(providerId, externalId)` and never overwrites locally-owned columns. It is used
by `PlayCounter`, `FavoritesRepository`, and `PlaylistRepository` so local state
does not require a prior library sync.

## Trigger set

[VERIFIED] `ProviderCascadeTriggers.CREATE_STATEMENTS` now has 17 provider-delete
triggers (including `download_queue`, `play_events`, and `queue_entries`) plus the
playlist-entry trigger (`trg_playlist_delete_playlist_items`), all named after the
v2 tables. The migration tears down the v1-named triggers first and reinstalls the
v2-named set, so both fresh and previously-opened v1 databases end with exactly
one trigger per statement. The migration test asserts the names, the count, and
that deleting a provider removes its owned rows.
