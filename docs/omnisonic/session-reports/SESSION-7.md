# SESSION 7 — T17 (Gate D: replace REPLACE upserts)

Date: 2026-11-02

Scope: T17 only. T16 was completed in the previous session (commit `d4b517c`, report
`SESSION-T16-FINAL.md`). Stopped after T17 per the checkpoint rule; T18 not started.

---

## Problem confirmed?

**Yes.** [VERIFIED by reading the pre-change code and Prompt.md T17]

Two independent hazards in the old `@Insert(onConflict = OnConflictStrategy.REPLACE)` upserts:

1. **Cascade wipe on provider edit.** SQLite `REPLACE` deletes the conflicting row, then
   inserts. Delete triggers fire for REPLACE-deleted rows only when
   `PRAGMA recursive_triggers` is ON. The schema installs `ProviderCascadeTriggers` over 17
   child tables, so if recursive triggers were ever enabled (platform or future change),
   editing a provider row with REPLACE would cascade-delete its entire library.
2. **Orphaned join rows on unique-key hits.** With `id = 0` in the entity, a REPLACE that
   hits the unique `(providerId, externalId)` index inserts a NEW id and deletes the old
   row — `song_artists`, `chapters`, and `playlist_entries` pointing at the old id are
   orphaned. The same class of bug made `SyncStatusDao.upsert` with `id = 0` a silent
   no-op on every call after the first (it hit the unique `providerId` index but updated
   0 rows, since the entity carried no real id).

Audit scope: every `@Dao` interface in
`core-data/src/main/java/app/omnisonic/core/data/db/dao/` (16 files), plus the repository
call sites that construct entities with `id = 0` (`SongRepository.ensureSong`,
`OutboxQueue`, `QueueSnapshotRepository`). `SubsonicLibrarySyncer` already used the safe
`insertOrIgnore` + column-scoped `updateServerFields` pattern and was not regressed.

## Files changed

Commit `6f855c0` — `fix(T17): replace REPLACE upserts with natural-key upserts`
(17 files, +650/−88):

- **Core pattern** (per DAO): abstract `@Upsert suspend fun upsertRow(...)` +
  `@Transaction suspend fun upsert(...)` default method that resolves the existing id via
  a natural-key `SELECT` and re-runs `upsertRow(entity.copy(id = resolvedId))`, returning
  the resolved id (never relying on `@Upsert`'s `-1` update return). Bulk path:
  `@Upsert upsertRows(...)` + `@Transaction upsertAll(...) { forEach { upsert(it) } }`.
  - `(providerId, externalId)`: `AlbumDao`, `ArtistDao`, `SongDao`, `GenreDao`,
    `InternetRadioDao`, `LibrarySourceDao` (new `selectIdByProviderAndExternal` where
    missing).
  - Link tables `(providerId, albumId|songId, artistId, role)`: `AlbumArtistDao`,
    `SongArtistDao` (new `selectIdByNaturalKey`).
  - `DownloadDao`: resolves `selectByMediaKey(mediaKey)?.id`; `insertIgnoring` kept.
  - `OfflineFileDao`: resolves by `sourceKey`, falls back to `offlinePath` (both queries new).
  - `MiscDaos.kt`: `SavedFilterDao` by `(mediaType, name)`; `SyncStatusDao` by
    `selectByProvider(...)?.id` (fixes the silent no-op).
  - `PlaylistDao`: resolves `selectByProviderAndExternal(...)?.id` (no unique index, but
    natural identity still applies); `PlaylistEntryDao`: plain `@Upsert` (no natural key).
  - `ChapterDao`, `ProviderDao`: plain `@Upsert` (PK-only semantics; `ProviderDao` was the
    main cascade fix).
  - `QueueDao`: `upsertState` changed from `@Insert` to `@Upsert`.
  - `OutboxDao`: new wrapper resolving `(providerId, mediaType, externalId, action)`.
- **`SongRepository.ensureSong`**: now selects the existing row by
  `(providerId, externalId)` and, if present, `copy`s it filling ONLY null snapshot
  columns (albumId, displayArtist, imagePath, durationMs) — local state, title, and
  `updatedAt` are preserved; if absent, inserts a fresh row with `updatedAt = 0` (epoch =
  "never synced"). Then `songDao.upsert(row)`. `SongDao.fillSnapshotFields` is now unused
  but retained to avoid an API break in this task.
- **New test** `core-data/src/test/java/app/omnisonic/core/data/UpsertSafetyTest.kt`.
- **Docs** (follow-up commit): `docs/omnisonic/storage/library-sync-ownership.md` —
  added the T17 upsert-contract bullet and updated the `ensureSong` description.

## Test added

`UpsertSafetyTest` (7 tests, Robolectric, in-memory Room):

1. `editing a provider keeps its songs when recursive triggers are on` — the T17-mandated
   worst case: sets `PRAGMA recursive_triggers = ON`, re-upserts the provider, asserts the
   song and its `song_artists` link survive. (Passes because no DAO uses REPLACE any more.)
2. `re-upserting the same song keeps its id and join rows` — the T17 proof: two
   `songDao.upsert` calls with the same `(providerId, externalId)` return the same id, and
   `song_artists` / `chapters` / `playlist_entries` survive with the original songId.
3. `natural-key upserts update the existing row instead of inserting a new one` — artist,
   album, genre, internet radio, library source: second upsert returns the original id and
   updates fields in place.
4. `link upserts keep the existing join row` — repeated song/album-artist link upserts
   return the original row id; still one row per side.
5. `playlist download offline and saved-filter upserts reuse natural identities`.
6. `outbox and sync-status upserts update the unique row` — covers the
   `SyncStatusDao` id=0 silent-no-op fix (one row after two upserts, latest result wins)
   and outbox payload/attempt overwrite.
7. `dao sources contain no REPLACE conflict strategy` — static scan of the DAO source
   directory failing on `OnConflictStrategy.REPLACE` or `INSERT OR REPLACE`.

Regression pre-existing and still green: `ProviderCascadeTriggerTest`,
`MigrationHarnessTest` (still asserts `recursive_triggers = 0` on the file-backed harness),
`DaoSqlCompatibilityTest`, `SongRepositoryTest`, `DatabasePerformanceTest` (100k-song seed
budget passes: `perf seedMs=7071 recomputeMs=33 topMs=2`).

### Note: dropped "recursive triggers stay off by default" assertion

An earlier 8th test asserted `PRAGMA recursive_triggers == 0` on the in-memory Room DB.
It failed with `expected: 0 but was : 1`: under Robolectric the framework's in-memory
SQLite connection reports recursive triggers ON by default, while the file-backed
`MigrationTestHelper` harness reports 0 (and the existing migration test asserts that).
The value is a property of the SQLite environment, not of app code — the app never sets
the pragma (verified: no `recursive_triggers` string anywhere in
`core-data/src/main`). Asserting it on the in-memory harness would be flaky against the
test environment, so the assertion was removed from `UpsertSafetyTest`; the meaningful
invariant (behaviour is safe even WITH recursive triggers ON) is test 1, and
`MigrationHarnessTest` keeps asserting 0 on the file harness. [INFERRED] that Robolectric
enables the pragma by default; not verified against a real device — flagged as unknown.

## Result

- `./gradlew :core-data:testDebugUnitTest --tests '...UpsertSafetyTest'` — 7/7 pass
  (after fixing one compile error: `genreDao.selectById` does not exist; replaced with
  `selectByProviderAndExternal(1, "rock")`).
- `./gradlew :core-data:testDebugUnitTest` — 154 tests, 0 failures, 0 errors.
- `./gradlew test :app:assembleDebug` — **BUILD SUCCESSFUL in 50s**; all modules combined:
  828 tests, 0 failures, 0 errors, 8 skipped.
- `./gradlew lint` — BUILD SUCCESSFUL, all modules.
- `scripts/check-banned-names.sh` on all changed files — clean.
- Repo-wide grep for REPLACE upserts: only remaining hit is under the gitignored
  `reference/` study tree, not product code.

## Unresolved / follow-ups

- [UNKNOWN] Whether real Android devices default `recursive_triggers` to 1 (Robolectric
  does). Irrelevant to correctness now that REPLACE is gone, but worth one line in a
  future device-test session.
- `SongDao.fillSnapshotFields` is dead code retained for API stability; remove in a later
  cleanup task.
- `@Upsert upsertRows(...)` list variants are currently unused by production code (bulk
  wrappers loop the per-row path to keep id resolution); keep or delete at T20 cleanup.
