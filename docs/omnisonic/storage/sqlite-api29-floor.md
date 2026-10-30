# SQLite syntax floor on API 29 — `sqlite-api29-floor.md`

Purpose: Pin the SQLite feature floor for queries compiled into the app so DAO SQL cannot rely on syntax that Android 10 devices reject.

How derived: The AOSP `external/sqlite` `README.version` at the Android 10 release tag, the upstream SQLite release notes, and a source-scanning DAO compatibility test. The studied app is not a source.

Confidence:
- [VERIFIED] Android 10 (`android-10.0.0_r47`) ships SQLite 3.22.0.
- [VERIFIED] SQLite 3.24.0 added `UPSERT`; SQLite 3.25.0 added window functions; SQLite 3.35.0 added `RETURNING`.
- [VERIFIED] `PlaybackEventDao.recordDayEvent` now uses `INSERT OR IGNORE` plus `UPDATE`, wrapped in `@Transaction`.
- [VERIFIED] `DaoSqlCompatibilityTest` scans DAO source and rejects `ON CONFLICT`, `RETURNING`, and `OVER (`.

Date: 2026-10-30

Links: `../00-template.md`

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

## Playback event merge

[VERIFIED] The first insert writes zero flags and zero duration. The following update ORs the flags and adds the duration, so inserting and merging the same event produces the caller's values exactly once.

[VERIFIED] `PlaybackEventDaoTest` still covers same-day merge, separate days, and independent tracks unchanged.

## Guardrail

`DaoSqlCompatibilityTest` reads every Kotlin file in `core-data/src/main/java/app/omnisonic/core/data/db/dao` and fails with file and line for any forbidden construct. This keeps a newer Robolectric SQLite from hiding a device-only syntax error.
