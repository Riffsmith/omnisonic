# Database schema — v1 baseline + additive v2 read model (`schema-v2.md`)

Purpose: Record the exported database baselines, their table
and column vocabulary, the sync-run stamp columns, the queue snapshot, the
per-play history view, the first approved additive migration, and the
migration-harness contract.

How derived: own-source reading of the Room entities, DAOs and
`OmnisonicDatabaseFactory`, the exported Room schema artifacts
`core-data/schemas/app.omnisonic.core.data.db.OmnisonicDatabase/1.json` and
`2.json`, and in-repository migration-harness runs. Evidence categories:
own-source reading, exported Room schema artifacts, in-repository test runs.

Confidence:
- [VERIFIED] `OmnisonicDatabase.SCHEMA_VERSION = 2`; `Migrations.ALL` carries
  exactly `MIGRATION_1_2`; neither database builder installs a destructive
  fallback.
- [VERIFIED] `1.json` (untouched) and `2.json` (newly exported) are the
  exported schemas. v1 contains every final entity, the renamed column labels,
  `lastSeenSyncId` on songs/albums/artists/playlists, `queue_state.positionMs`,
  and the `play_events_daily` view; v2 adds only the `offline_media` read-model
  columns and lookup index.
- [VERIFIED] `MigrationHarnessTest` seeds a database from `1.json`, runs the
  real `1 → 2` migration, validates it against `2.json`, and reopens the seeded
  database through the production factory.
- [UNKNOWN] Behaviour on a large production database — no released database
  exists; the harness uses small fixture rows.

Date: 2026-11-20

Links: `library-sync-ownership.md`, `sqlite-api29-floor.md`,
`aggregate-null-semantics.md`, `outbox-contract.md`, `download-completion.md`,
`../decisions.md`

---

The filename is retained for link stability; its subject is now the v1
baseline plus the first approved additive step, rather than the retired
pre-release v2/v3 chain.

## One clean baseline, then additive steps only (D-11 option B)

The pre-release v1 → v2 → v3 migration chain, its old schema exports and its
migration harness were removed in FIX-15. No database from that chain was ever
released. The repository shipped one clean v1 baseline, and the first approved
additive change is the Phase 7 task-5a offline read-model step (D-43):

```kotlin
const val SCHEMA_VERSION = 2
val Migrations.ALL: List<Migration> = listOf(MIGRATION_1_2)
```

Every future schema change appends exactly one migration to `Migrations.ALL`
and exports the next schema; the harness seeds `1.json` and validates the
result against the newest export. No future migration is invented ahead of an
approved change, and an existing exported baseline is never edited.

## The v1 → v2 offline read-model step (D-43)

`MIGRATION_1_2` is additive on `offline_media` only [VERIFIED]:

- `tempCache INTEGER NOT NULL DEFAULT 0` — the completed row's rolling intent;
  v1 rows stay permanent. The entity declares `@ColumnInfo(defaultValue = "0")`
  so Room's schema validation matches the SQL default.
- `externalId TEXT NULL` plus `index_offline_media_providerId_externalId`
  `(providerId, externalId)` — an indexed provider/external lookup of every
  offline variant of one song.
- A tolerant backfill parses each `sourceKey` with the exact colon-safe
  `stream:`-key parse and sets `externalId`; unparseable keys keep NULL and no
  path, source id or row is rewritten or deleted.

`download_queue` and every other table are unchanged.

## Column vocabulary

The baseline uses the durable labels below. The mapping is enforced by
`BannedColumnScannerTest`, whose obsolete-label list lives in local Git
configuration (`omnisonic.bannedColumns`, with `OMNISONIC_BANNED_COLUMNS` as a
CI override).

| Table | Final column labels (renamed labels in bold) |
|---|---|
| `songs` | `latinTitle`**, `providerPayloadJson`**, `resumeMs`**, `windowStartMs`**, `windowEndMs`** |
| `albums` | `latinTitle`**, `providerPayloadJson`**, `resumeTrackPath`**, `discNamesJson`** |
| `artists` | `latinTitle`**, `providerPayloadJson`** |
| `genres`, `library_sources`, `radio_stations` | `providerPayloadJson`** |
| `playlists` | `providerPayloadJson`**, `ruleJson`** |
| `playlist_items` | `position`** (entry order) |
| `chapters` | `origin`**, `rank`** (chapter source kind/priority) |

Generic identifiers such as `providerId`, `externalId`, `playCount`,
`isFavorite` and `isAudiobook` are unchanged.

## Tables

| Table | Purpose |
|---|---|
| `providers` | Provider rows: api type, endpoint blocks, secrets, options, mTLS |
| `library_sources` | Provider-reported library roots and paths |
| `artists`, `albums`, `songs`, `genres` | Synced library metadata plus local playback state |
| `album_artists`, `song_artists` | Role-carrying artist links |
| `playlists`, `playlist_items` | Playlists and their ordered entries |
| `radio_stations` | Internet radio rows |
| `chapters` | Chapter/segment rows attached to songs and albums |
| `play_events` | One immutable row per play or skip |
| `outbox_items` | Pending provider user-data changes |
| `download_queue` | Download tasks, owned by a provider |
| `offline_media` | Offline files and their source keys; v2 adds `tempCache` (rolling intent) and nullable `externalId` with a `(providerId, externalId)` lookup index |
| `saved_filters` | Saved search/library filters |
| `sync_state` | Per-provider sync status |
| `queue_entries`, `queue_state` | Queue snapshot and single-row transport state |

`play_events_daily` is a view grouped by
`(providerId, externalId, dayStartMs)` exposing `date`, `MAX(played)`,
`MAX(skipped)`, `SUM(durationMs)`, and `COUNT(*) AS playCount`.

## Sync-run stamps (FIX-01 / D-10)

[VERIFIED] `songs`, `albums`, `artists` and `playlists` carry a nullable
`lastSeenSyncId` INTEGER. A full-sync run mints a unique id (DB-stamp floor plus
a per-instance atomic sequence) and stamps every row it writes. Full-sync
pruning selects rows whose stamp is NULL or different from the run id, behind
the FIX-02 safety guards. Rows materialised outside sync keep NULL. The stamp
columns are classified SERVER-owned in `SyncColumnOwnershipTest`.

## Queue snapshot

[VERIFIED] `queue_entries` stores the full metadata snapshot for every entry
(`providerId`, `externalId`, optional `songRowId`, `title`, `artistName`,
`albumExternalId`, `coverImageId`, `durationMs`, `isRadioStation`). Restoration
never joins `songs`, so an entry whose song was never synced still restores.

[VERIFIED] `queue_state` is the single-row transport state (`playIndex`,
repeat mode stored by name, `speed`, `pitch`, `sourceType`, `sourceData`,
`positionMs`) plus the shuffle order as an entry-index permutation
(`shuffleOrderJson`) alongside the original `shuffleSeed`. `positionMs` is the
resume point of the current entry; NULL means no restore point.

## Per-play history

[VERIFIED] `PlayCounter` writes one `play_events` row per play and one per
skip: a skip is `played = false, skipped = true` and does not increment
`songs.playCount`. `PlayEventDao` serves per-play reads plus both rollup reads.
The view keeps day-shaped reads for export/UI without merging the underlying
history.

## Triggers

[VERIFIED] `ProviderCascadeTriggers.CREATE_STATEMENTS` installs the
provider-delete cascade set plus the playlist-entry trigger, all named after
the final tables. The factory installs them in both `onCreate` and `onOpen`, so
a fresh database and a reopened file database end with exactly one trigger per
statement. `PRAGMA recursive_triggers` is never enabled.

## Materialisation and ownership

[VERIFIED] `SongRepository.ensureSong` inserts a minimal song row for an
unsynced `(providerId, externalId)` and never overwrites locally-owned columns.
It is used by `PlayCounter`, `FavoritesRepository`, and `PlaylistRepository` so
local state does not require a prior library sync. The SERVER/LOCAL column
classification and the sync write rules live in `library-sync-ownership.md`.

## Migration harness

[VERIFIED] `MigrationHarnessTest`:

1. asserts the current baseline is version 2 with exactly one `1 → 2` step;
2. seeds `1.json` with fixture rows exercising the renamed labels plus three
   `offline_media` rows (plain, colon-containing and unparseable source keys)
   and a `download_queue` row, runs `Migrations.ALL` to version 2 and validates
   the schema against `2.json`, asserting preserved paths/source ids, the
   permanent default and the tolerant `externalId` backfill;
3. creates a fresh `2.json` database and proves the new columns are writable;
4. reopens the v1-seeded database through `OmnisonicDatabaseFactory`, which
   proves Room's identity-hash validation, the real migration path and trigger
   installation on the production path.

The harness is structural (Room schema validation plus direct SQLite queries)
because a Robolectric SQLite image cannot faithfully reproduce every on-device
SQLite behaviour.
