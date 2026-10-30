# Aggregate NULL semantics — `aggregate-null-semantics.md`

Purpose: Pin the empty-album behavior of album aggregate recomputation so a SQL `NULL` never violates the non-null `albums.playCount` column.

How derived: Code reading of `AggregateDao.kt`, the `AlbumEntity` schema, and the T6 empty-album regression tests. The studied app is not a source.

Confidence:
- [VERIFIED] `SUM` over no rows is `NULL`, so the two sum queries already wrapped it in `COALESCE(..., 0)`.
- [VERIFIED] `AVG` and `MIN` over no rows are also `NULL`; their four queries previously wrote that `NULL` into a `NOT NULL` column.
- [VERIFIED] All six album recompute queries now produce `playCount = 0` when the album has no songs.
- [VERIFIED] Empty albums keep `lastPlayed` and `resumeFilePath` as `NULL`.

Date: 2026-10-30

Links: `sqlite-api29-floor.md`, `../00-template.md`

---

## Failure mode

[VERIFIED] Before the fix, `recomputeAlbumsAverageById`, `recomputeAlbumsMinimumById`, `recomputeAlbumsAverageByProvider`, and `recomputeAlbumsMinimumByProvider` raised `SQLiteConstraintException` for an album with zero songs. The four new tests reproduced that failure before the query change.

## Fixed queries

[VERIFIED] The by-id and by-provider variants of average and minimum use:

```sql
COALESCE(ROUND(AVG(songs.playCount)), 0)
COALESCE(MIN(songs.playCount), 0)
```

[VERIFIED] The sum variants keep `COALESCE(SUM(songs.playCount), 0)`.

## Regression coverage

`AggregateRecomputeTest` covers average and minimum on an empty album through both the id-list and provider entry points, asserting `playCount = 0`, `lastPlayed = null`, and `resumeFilePath = null`.
