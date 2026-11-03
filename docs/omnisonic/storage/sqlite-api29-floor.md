# SQLite syntax floor on API 29 — `sqlite-api29-floor.md`

Purpose: Pin the SQLite feature floor for queries compiled into the app so DAO SQL cannot rely on syntax that Android 10 devices reject.

How derived: The AOSP `external/sqlite` `README.version` at the Android 10 release tag, the upstream SQLite release notes, and a source-scanning DAO compatibility test. The studied app is not a source.

Confidence:
- [VERIFIED] Android 10 (`android-10.0.0_r47`) ships SQLite 3.22.0.
- [VERIFIED] SQLite 3.24.0 added `UPSERT`; SQLite 3.25.0 added window functions; SQLite 3.35.0 added `RETURNING`.
- [VERIFIED] `PlayEventDao` writes one row per play or skip with a plain `INSERT`; no UPSERT is needed for playback history.
- [VERIFIED] `DaoSqlCompatibilityTest` scans DAO source and rejects `ON CONFLICT`, `RETURNING`, and `OVER (`.
- [VERIFIED] The single v1→v2 migration uses only DDL available in SQLite 3.22 (table rebuild/copy, indexes, view, triggers); it does not use UPSERT, window functions, or `RETURNING`.

Date: 2026-11-02

Links: `schema-v2.md`, `../00-template.md`

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

## Per-play history (v2)

[VERIFIED] The per-play history decision replaces the merged-day `playback_events` table with
`play_events` (one row per play or skip) plus the `play_events_daily` view. The
DAO no longer needs an insert-or-ignore/update merge: every event is an insert.

[VERIFIED] `PlayEventDaoTest` covers per-play timestamps, per-day rollups, and the
skip/play split. The v1→v2 migration copies each old merged day row to one
`play_events` row, as documented in `schema-v2.md`.

## Guardrail

`DaoSqlCompatibilityTest` reads every Kotlin file in `core-data/src/main/java/app/omnisonic/core/data/db/dao` and fails with file and line for any forbidden construct. This keeps a newer Robolectric SQLite from hiding a device-only syntax error.
