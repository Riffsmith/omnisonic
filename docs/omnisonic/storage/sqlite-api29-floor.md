# SQLite syntax floor on API 29 — `sqlite-api29-floor.md`

Purpose: Pin the SQLite feature floor for queries compiled into the app so DAO SQL cannot rely on syntax that Android 10 devices reject.

How derived: The AOSP `external/sqlite` `README.version` at the Android 10 release tag, the upstream SQLite release notes, and a source-scanning DAO compatibility test. Evidence categories: public AOSP source reading, upstream release documentation, in-repository scanning test.

Confidence:
- [VERIFIED] Android 10 (`android-10.0.0_r47`) ships SQLite 3.22.0.
- [VERIFIED] SQLite 3.24.0 added `UPSERT`; SQLite 3.25.0 added window functions; SQLite 3.35.0 added `RETURNING`.
- [VERIFIED] `PlayEventDao` writes one row per play or skip with a plain `INSERT`; no UPSERT is needed for playback history.
- [VERIFIED] `DaoSqlCompatibilityTest` scans DAO source and rejects `ON CONFLICT`, `RETURNING`, and `OVER (`.
- [VERIFIED] The v1 baseline uses only DDL available in SQLite 3.22 (tables, indexes, view, triggers); no migration exists yet to require newer syntax.
- [VERIFIED] SQLite 3.22 caps statements at 999 bound variables (`SQLITE_MAX_VARIABLE_NUMBER`, raised to 32,766 only in 3.32.0), so parameterized `IN`/`NOT IN` lists are bounded: raw queries are named `...Chunk` and `@Transaction` default methods feed them batches of 500 (`SqlChunking.kt`); `DaoSqlBoundedListTest` rejects any parameterized `IN (:`/`NOT IN (:` in DAO SQL whose method does not end in `Chunk`. Robolectric's SDK 34 image bundles a newer SQLite with the raised limit, so the crash itself is device-only and enforcement is structural.
- [VERIFIED] Stamp-based pruning (FIX-01/D-10) removed the largest unbounded lists entirely: full-sync prune queries compare `lastSeenSyncId` per row (2 bound variables) instead of binding one variable per seen external id (see `library-sync-ownership.md`).

Date: 2026-11-07

Links: `schema-v2.md`, `library-sync-ownership.md`, `../00-template.md`

---

## Platform version

AOSP tag `android-10.0.0_r47`, file `external/sqlite/README.version`:

```text
Version: 3.22.0
URL: https://www.sqlite.org/2018/sqlite-amalgamation-3220000.zip
```

[VERIFIED] The same file identifies commit `68b898381ac2942965a3dbd416a45ddf813d6df7ea160f500ae4978e44a3a050`. API 29 therefore predates every feature listed below.

## Upstream feature introductions

SQLite release notes, version 3.24.0 (2018-06-04):

```text
Add support for PostgreSQL-style UPSERT.
```

SQLite release notes, version 3.25.0 (2018-09-15):

```text
Add support for window functions
```

SQLite release notes, version 3.35.0 (2021-03-12):

```text
Add support for the RETURNING clause on DELETE, INSERT, and UPDATE.
```

[VERIFIED] `ON CONFLICT ... DO UPDATE` needs 3.24.0, window functions need 3.25.0, and `RETURNING` needs 3.35.0. None is available on API 29.

## Per-play history (v1)

[VERIFIED] The per-play history decision ships in the v1 baseline: `play_events`
holds one row per play or skip and `play_events_daily` is a view over it. The DAO
needs no insert-or-ignore/update merge: every event is an insert.

[VERIFIED] `PlayEventDaoTest` covers per-play timestamps, per-day rollups, and the
skip/play split.

## Guardrail

`DaoSqlCompatibilityTest` reads every Kotlin file in `core-data/src/main/java/app/omnisonic/core/data/db/dao` and fails with file and line for any forbidden construct. This keeps a newer Robolectric SQLite from hiding a device-only syntax error.

`DaoSqlBoundedListTest` scans the same directory and fails for any parameterized `IN (:` / `NOT IN (:` on a method not named `...Chunk`. Raw `Chunk` methods must stay single-batch (one pre-sized list per call); their plain-named wrappers chunk with `SQL_IN_CHUNK_SIZE` (500). This keeps a Robolectric SQLite with a raised variable limit from hiding the Android 10 999-variable crash.
