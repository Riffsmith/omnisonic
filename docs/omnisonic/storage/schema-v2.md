# Database schema — clean v1 baseline (`schema-v2.md`)

Purpose: Record the single exported pre-release database baseline, its table
and column vocabulary, the sync-run stamp columns, the queue snapshot, the
per-play history view, and the migration-harness contract.

How derived: own-source reading of the Room entities, DAOs and
`OmnisonicDatabaseFactory`, the exported Room schema artifact
`core-data/schemas/app.omnisonic.core.data.db.OmnisonicDatabase/1.json`, and
in-repository migration-harness runs. Evidence categories: own-source reading,
exported Room schema artifacts, in-repository test runs.

Confidence:
- [VERIFIED] `OmnisonicDatabase.SCHEMA_VERSION = 1`; `Migrations.ALL` is empty;
  neither database builder installs a destructive fallback.
- [VERIFIED] `1.json` is the only exported schema. It contains every final
  entity, the renamed column labels, `lastSeenSyncId` on songs/albums/artists/
  playlists, `queue_state.positionMs`, and the `play_events_daily` view.
- [VERIFIED] `MigrationHarnessTest` seeds a database from `1.json`, runs the
  real migration path (the identity step today), validates it against the same
  export, and reopens the seeded database through the production factory.
- [UNKNOWN] Behaviour on a large production database — no released database
  exists; the harness uses small fixture rows.

Date: 2026-11-07

Links: `library-sync-ownership.md`, `sqlite-api29-floor.md`,
`aggregate-null-semantics.md`, `outbox-contract.md`, `../decisions.md`

---

The filename is retained for link stability; its subject is now the v1
baseline rather than the retired pre-release v2/v3 chain.

## One clean baseline (D-11 option B)

The pre-release v1 → v2 → v3 migration chain, its old schema exports and its
migration harness were removed in FIX-15. No database from that chain was ever
released, so there is nothing to upgrade. The repository now ships one clean
v1 baseline:

```kotlin
const val SCHEMA_VERSION = 1
val Migrations.ALL: List<Migration> = emptyList()
```

A future schema change appends exactly one `Migration(1, 2)` to `Migrations.ALL`
and exports `2.json`; the harness then seeds `1.json` and validates the result
against `2.json`. No future migration is invented ahead of that change.

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
| `offline_media` | Offline files and their source keys |
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

1. asserts the current baseline is version 1 with no shipped migration step;
2. seeds `1.json` with fixture rows exercising the renamed labels, then runs
   `Migrations.ALL` (empty today) and validates the schema against `1.json`;
3. reopens that seeded database through `OmnisonicDatabaseFactory`, which
   proves Room's identity-hash validation and trigger installation on the
   production path.

The harness is structural (Room schema validation plus direct SQLite queries)
because a Robolectric SQLite image cannot faithfully reproduce every on-device
SQLite behaviour.
