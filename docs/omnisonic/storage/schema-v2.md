# Database schema — v1 baseline + additive v2/v3 steps (`schema-v2.md`)

Purpose: Record the exported database baselines, their table
and column vocabulary, the sync-run stamp columns, the queue snapshot, the
per-play history view, the approved additive migrations, and the
migration-harness contract.

How derived: own-source reading of the Room entities, DAOs and
`OmnisonicDatabaseFactory`, the exported Room schema artifacts
`core-data/schemas/app.omnisonic.core.data.db.OmnisonicDatabase/1.json`,
`2.json` and `3.json`, and in-repository migration-harness runs. Evidence
categories: own-source reading, exported Room schema artifacts, in-repository
test runs.

Confidence:
- [VERIFIED] `OmnisonicDatabase.SCHEMA_VERSION = 3`; `Migrations.ALL` carries
  exactly `MIGRATION_1_2` and `MIGRATION_2_3`; neither database builder installs
  a destructive fallback.
- [VERIFIED] `1.json` and `2.json` (untouched) and `3.json` (newly exported)
  are the exported schemas. v1 contains every final entity, the renamed column
  labels, `lastSeenSyncId` on songs/albums/artists/playlists,
  `queue_state.positionMs`, and the `play_events_daily` view; v2 adds the
  `offline_media` read model; v3 adds only the provider-owned `download_rules`
  table and its unique identity index.
- [VERIFIED] `MigrationHarnessTest` seeds the `1.json` and `2.json` baselines,
  runs the real migration path, validates against `3.json`, reopens a seeded
  database through the production factory, and proves the rule cascade trigger
  is installed.
- [UNKNOWN] Behaviour on a large production database — no released database
  exists; the harness uses small fixture rows.

Date: 2026-11-21

Links: `automatic-rules.md`, `library-sync-ownership.md`, `sqlite-api29-floor.md`,
`aggregate-null-semantics.md`, `outbox-contract.md`, `download-completion.md`,
`../decisions.md`

---

The filename is retained for link stability; its subject is now the v1
baseline plus the first approved additive step, rather than the retired
pre-release v2/v3 chain.

## One clean baseline, then additive steps only (D-11 option B)

The pre-release v1 → v2 → v3 migration chain, its old schema exports and its
migration harness were removed in FIX-15. No database from that chain was ever
released. The repository shipped one clean v1 baseline, followed by two
approved additive steps: the Phase 7 task-5a offline read model (D-43) and the
task-7a automatic-rule table (D-47):

```kotlin
const val SCHEMA_VERSION = 3
val Migrations.ALL: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)
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

## The v2 → v3 automatic-rule step (D-47)

`MIGRATION_2_3` creates one new table and its unique index; no existing table,
column or row changes [VERIFIED]:

- `download_rules` — `id`, `providerId`, `kind` (stored by NAME), non-null
  `targetExternalId`, optional `transcodeBitrateKbps`/`format`, `createdAt`,
  `updatedAt`.
- Unique `index_download_rules_providerId_kind_targetExternalId` on
  `(providerId, kind, targetExternalId)` is the deterministic duplicate key.
  FAVORITES uses the empty-string target sentinel because SQLite UNIQUE treats
  NULLs as distinct.
- `ProviderCascadeTriggers` gained `download_rules`, so a provider delete
  removes its rules alongside queue and offline rows.

Rule identity, the sentinel rationale, scoped reads and the expansion/pruning
boundary are documented in `automatic-rules.md`.

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
| `download_queue` | Download tasks, owned by a provider; nullable `autoRuleId` is enqueue-time intent, not ownership |
| `download_rules` | Provider-owned FAVORITES/PLAYLIST/ALBUM rules; v3 adds the table with the unique `(providerId, kind, targetExternalId)` identity index |
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

1. asserts the current baseline is version 3 with exactly the ordered
   `1 → 2` and `2 → 3` steps;
2. seeds `1.json` with fixture rows exercising the renamed labels plus three
   `offline_media` rows (plain, colon-containing and unparseable source keys)
   and a `download_queue` row, runs `Migrations.ALL` to version 3 and validates
   the schema against `3.json`, asserting preserved paths/source ids, the
   permanent default and the tolerant `externalId` backfill;
3. seeds `2.json` with offline and queue rows, upgrades to 3, and proves those
   rows survive, the rule table is writable, the unique index rejects a
   duplicate, and the favorites sentinel is stored;
4. reopens the v1-seeded database through `OmnisonicDatabaseFactory`, which
   proves Room's identity-hash validation, the real migration path and trigger
   installation on the production path (including the rule trigger).

The harness is structural (Room schema validation plus direct SQLite queries)
because a Robolectric SQLite image cannot faithfully reproduce every on-device
SQLite behaviour.
