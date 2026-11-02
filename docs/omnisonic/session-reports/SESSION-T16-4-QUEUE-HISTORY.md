# SESSION T16-4 — Queue snapshots, `ensureSong` consumers, T10/T22

Status: implementation complete for the storage layer. No migration, no schema
version bump, no schema export, no commit.

## Scope completed

- Queue persistence rows in the main database, independent of a synced `songs`
  row.
- `QueueState` ↔ snapshot mapping in `:playback`.
- `PlayCounter`, `FavoritesRepository`, and `PlaylistRepository` now call
  `SongRepository.ensureSong` before writing locally-owned playback state.
- T22 decision `(b)` implemented as a per-play table plus a per-day rollup view.
- T10 ownership behaviour pinned with an additional server-refresh test.

## Queue persistence

New tables (main database, v2 target):

### `queue_entries`

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK autoincrement | row identity |
| `queueItemId` | INTEGER NOT NULL | unique; playback identity |
| `position` | INTEGER NOT NULL | unique; original entries order |
| `providerId` | INTEGER NOT NULL | cascade trigger target |
| `externalId` | TEXT NOT NULL | provider identity |
| `songRowId` | INTEGER NULL | optional convenience, never required |
| `title` | TEXT NOT NULL | snapshot |
| `artistName` | TEXT NULL | snapshot |
| `albumExternalId` | TEXT NULL | snapshot |
| `coverImageId` | TEXT NULL | snapshot |
| `durationMs` | INTEGER NULL | snapshot |
| `isRadioStation` | INTEGER NOT NULL | snapshot; default 0 |

Restoration reads `queue_entries` directly. It never joins `songs`, so an entry
whose song was never synced still restores with its title, artist, album id,
cover id, duration, and radio flag.

### `queue_state`

Single-row transport state. This table was a judgment call: only
`queue_entries` was mandated, but restoring the current position and shuffle
anchor needs it.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | constant `1` |
| `playIndex` | INTEGER NOT NULL | index into the effective order |
| `shuffleOrderJson` | TEXT NULL | entry-index permutation, CSV |
| `shuffleSeed` | INTEGER NULL | original seed |
| `repeatMode` | TEXT NOT NULL | enum by name (`OFF`, `ALL`, `ONE`) |
| `speed` | REAL NOT NULL | |
| `pitch` | REAL NOT NULL | |
| `sourceType` | TEXT NULL | |
| `sourceData` | TEXT NULL | |

Shuffle order is stored instead of regenerated from the seed because the current
anchor index depends on the original order.

New APIs:

- `core-data/.../repository/QueueSnapshotRepository.kt` — save, restore, clear.
- `playback/.../queue/QueuePersistence.kt` — maps `QueueState` to snapshots and
  validates a persisted shuffle permutation before restoring it.

No lifecycle owner calls `QueuePersistence.save` yet. Automatic save/restore
belongs to the Phase 4 queue/controller wiring; the persistence contract and
round-trip tests exist now.

## T22 — per-play history

Decision `(b)` from the audit is implemented.

Removed entity/DAO:

- `PlaybackEventEntity` (`playback_events`)
- `PlaybackEventDao`

Added:

### `play_events`

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK autoincrement | |
| `providerId` | INTEGER NOT NULL | cascade trigger target |
| `externalId` | TEXT NOT NULL | |
| `playedAtMs` | INTEGER NOT NULL | exact play or skip time |
| `dayStartMs` | INTEGER NOT NULL | local start of day for the old bucket |
| `played` | INTEGER NOT NULL | counted as a play |
| `skipped` | INTEGER NOT NULL | left before the end |
| `durationMs` | INTEGER NOT NULL | listened duration |

Indexes: `(providerId, externalId, playedAtMs)` and
`(providerId, dayStartMs)`.

### `play_events_daily` (view)

`@DatabaseView` grouped by `(providerId, externalId, dayStartMs)` exposing
`date`, `MAX(played)`, `MAX(skipped)`, `SUM(durationMs)`, and `COUNT(*)` as
`playCount`.

`PlayEventDao` provides per-play reads and both rollup reads. `PlayCounter`
writes one event per play and one event per skip; a skip is
`played = false, skipped = true` and does not increment `songs.playCount`.

## `ensureSong` consumers

`SongRepository.ensureSong` already existed from Session 3. This session added
the missing production callers:

- `PlayCounter.recordPlay` — materialises the song, increments `playCount`,
  writes a play event, and enqueues the provider play-count delta.
- `PlayCounter.recordSkip` — materialises the song, marks it skipped, writes a
  skip event, and does not increment the play count.
- `FavoritesRepository.setFavorite` — materialises the song, writes
  `isFavorite`/`favoriteAt`, and enqueues the favorite change.
- `PlaylistRepository.addSong` — materialises the song, appends a
  `playlists_entries` row pointing at the materialised id, and recounts the
  playlist.

All three preserve locally-owned columns because `ensureSong` only fills NULL
columns. `PlaylistEntryDao.selectMaxEntryOrder` was added for append ordering.

## T10 ownership

`SongEntity.resumeUpdatedAt` and `SongEntity.missingOnServer` were already
present and already classified LOCAL by `SyncColumnOwnershipTest`. Added
`SongRepositoryTest.serverRefreshAfterEnsureSongKeepsLocallyOwnedColumns`, which
runs `SongDao.updateServerFields` after `ensureSong` and asserts that
`playCount`, `isFavorite`, `favoriteAt`, `resumePointMs`, and
`resumeUpdatedAt` survive.

## Provider cascade

`ProviderCascadeTriggers.CREATE_STATEMENTS` now includes:

- `play_events` (replacing `playback_events`)
- `queue_entries`

`queue_state` has no `providerId` and therefore no trigger. Materialised
`songs` rows continue to cascade through the existing `songs` trigger.

## Migration requirements for Session 5

Session 5 must include all of the following in the single `1 -> 2` migration:

1. Create `queue_entries` and `queue_state` exactly as above.
2. Create `play_events` and the `play_events_daily` view.
3. Copy every old `playback_events` row into `play_events`:
   - `playedAtMs = playback_events.date`
   - `dayStartMs = playback_events.date` (old dates are already local midnight)
   - `played`, `skipped`, `durationMs` copied unchanged
   - one old merged day row becomes one play event; its original per-play count
     cannot be recovered.
4. Drop `playback_events`.
5. Recreate provider cascade triggers for `play_events` and `queue_entries`
   (the trigger installer does this on open, but the migration test should
   assert the final trigger count).
6. Keep `SCHEMA_VERSION = 2`, export `2.json`, and leave the tracked `1.json`
   untouched.

Important build detail: KSP rewrites
`core-data/schemas/app.omnisonic.core.data.db.OmnisonicDatabase/1.json` on every
compile while the version is still 1. During this session the file was backed
up before Gradle runs and restored afterwards. Session 5 must bump the version
before accepting any generated schema file, and must verify that `1.json` still
matches the committed v1 baseline.

## Tests and results

Commands:

```
./gradlew :core-data:testDebugUnitTest
./gradlew :playback:testDebugUnitTest
./gradlew :app:assembleDebug
```

Results:

- `:core-data:testDebugUnitTest` — 146 tests, 0 failures, 0 errors, 1 skipped
  (`MigrationHarnessTest` intentionally ignored until Session 5).
- `:playback:testDebugUnitTest` — 130 tests, 0 failures, 0 errors.
- `:app:assembleDebug` — successful.

New or rewritten tests:

- `PlayEventDaoTest` — per-play timestamps plus per-day rollups.
- `QueueSnapshotRepositoryTest` — restore without a `songs` row, shuffle/state
  round trip, save replacement and clear.
- `PlayCounterTest` — unsynced play, idempotent materialisation, skip semantics,
  no-user-data provider.
- `FavoritesRepositoryTest` — unsynced favorite and local-state preservation.
- `PlaylistRepositoryTest` — unsynced playlist add and append ordering.
- `SongRepositoryTest` — server refresh preserves T10 local columns.
- `ProviderCascadeTriggerTest` — provider delete removes `queue_entries` and
  `play_events`.
- `QueuePersistenceTest` — playback-state round trip and invalid shuffle
  fallback.

## Not done in this session

- No `SCHEMA_VERSION` bump.
- No `2.json`.
- No `Migration` entry in `Migrations.ALL`.
- No commit.
- No automatic queue save/restore wiring from the playback lifecycle.
- No migration data copy; that is Session 5 work.
