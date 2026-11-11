# Mid-track starts on never-played songs — historical record

Status: **fixed 2026-11-11 in Phase 5 task 6b** (D-18). The behavior contract
now lives in `resume-and-bookmarks.md`; this file keeps the measured evidence
and the cause chain, which nothing else records at this resolution.

How derived: user bug report (2026-11-11) · executed evidence on 2026-11-11 —
a server `getBookmarks` dump against the live Navidrome account, a full
`omnisonic.db` dump pulled from the debuggable build on the user's device
(`run-as`, WAL included), and an exact join of the two · own-source reading of
the start path (`ProviderStreamResolver`, `QueuePlayerImpl.startCurrent`,
`OmniPlayerImpl`) and of the only writer of `songs.resumeMs`
(`SubsonicLibrarySyncer.syncBookmarks`).

Date: 2026-11-11

Links: `resume-and-bookmarks.md` (the contract this produced),
`stream-resolution.md`, `../storage/library-sync-ownership.md`,
`../decisions.md` (D-18)

---

## 1. Symptom

Songs never played in Omnisonic started part-way through, at a stable
per-song offset. **279 of 1389 songs (20 %)** carried an offset. Examples from
the live DB (title · duration · start · % of track · bookmark last changed):

| Song | Duration | Starts at | % | Bookmark changed |
|---|---|---|---|---|
| Nit Khair Mansan Sohnia Main Teri | 11:51 | 9:45 | 82 % | 2026-10-26 |
| Toh Phir Aao (lounge version) | 5:55 | 4:01 | 68 % | 2026-04-02 |
| Origin | 4:04 | 2:42 | 66 % | 2026-07-07 |
| Kali Kali Zulfon Ke Phande Nah Dalo | 11:45 | 3:15 | 28 % | 2026-11-02 |
| 張力と重さ | 14:58 | 4:10 | 28 % | 2026-11-06 |
| Summertime Sadness | 4:24 | 0:50 | 19 % | 2026-11-11 |
| Epilogue : Young Forever (Japanese ver.) | 2:53 | 0:10 | 6 % | 2026-11-07 |

Offset distribution across the 279: < 10 % of the track 46, 10–60 % 167,
≥ 60 % 66; median 64 s, max 9:45.

## 2. Cause

1. `SubsonicLibrarySyncer.syncBookmarks` runs on every full **and** diff sync
   and, for each server bookmark passing three gates (no pending
   `resume_point` outbox row, non-null bookmark timestamp,
   `bookmark.changed > songs.resumeUpdatedAt`), calls
   `SongDao.applyServerResumePoint` — the **only** production writer of
   `songs.resumeMs`.
2. `SubsonicClient.listBookmarks` maps `bookmark.position` straight through as
   milliseconds and `bookmark.changed` to `updatedAtMs` (offset-aware; the
   join below proves it to the millisecond).
3. `ProviderStreamResolver.resumePositionMs` returned that value whenever it
   was `> 0` and below a known duration — **no played-here, provenance,
   freshness or user-intent gate** — and `resolve()` used it as
   `ResolvedStream.startPositionMs` for every start.
4. `QueuePlayerImpl.startCurrent` kept the resolver's position unless a queue
   snapshot position was armed for the same entry; `OmniPlayerImpl` handed it
   to ExoPlayer as the media start position.

Why it never self-healed: Omnisonic never called `createBookmark` /
`deleteBookmark` in production, a completed play wrote no resume point (Phase
6), `syncBookmarks` had no delete direction, and the outbox was empty.

## 3. Evidence (reproducible)

```bash
# 1. server bookmarks (credentials: OMNISONIC_NAVIDROME_URL/_USER/_PASS)
set -a; . ~/.config/omnisonic/navidrome.env; set +a
curl -s "$OMNISONIC_NAVIDROME_URL/rest/getBookmarks?u=$OMNISONIC_NAVIDROME_USER&p=$OMNISONIC_NAVIDROME_PASS&v=1.16.1&c=OmnisonicProbe&f=json" \
  | python3 -m json.tool > /tmp/omni-bookmarks.json

# 2. device database (WAL included; the debug build installs in place over the
#    tmpLocal-signed release — same debug keystore, versionCode = 1)
mkdir -p /tmp/omnidb
for f in omnisonic.db omnisonic.db-wal omnisonic.db-shm; do
  adb exec-out run-as app.omnisonic cat "databases/$f" > "/tmp/omnidb/$f"
done
sqlite3 /tmp/omnidb/omnisonic.db "PRAGMA integrity_check;"   # ok, user_version 1

# 3. resume rows
sqlite3 -csv -header /tmp/omnidb/omnisonic.db \
  "SELECT id, externalId, title, durationMs, resumeMs, resumeUpdatedAt, playCount, lastPlayed FROM songs WHERE resumeMs IS NOT NULL;" \
  > /tmp/omni-resume.csv
```

| Measurement | Value |
|---|---|
| Server bookmarks for the measured account | **291** |
| `songs` rows in the app DB | 1389 |
| Rows with `resumeMs > 0` | **279** |
| … of those, with a matching server bookmark | **279** |
| … position identical in ms | **279** |
| … position **and** `resumeUpdatedAt == bookmark.changed` | **279** |
| … with `playCount = 0` and `lastPlayed IS NULL` | **279** |
| … with `play_events` rows / `outbox_items` rows | 0 / 0 |
| … at or past `durationMs` (resolver would zero) | 0 |
| … with unknown duration (clamp skipped) | 0 |
| Bookmark ids with no local row | 12 — 9 titles the library holds under a *different* id (re-imported or duplicate copies), 3 songs not in this library |

Interpretation: `songs.resumeMs` on that install was **100 % foreign bookmark
data** applied as a start position on songs this app had never played.

Supporting facts: `sync_state.lastFullSyncAt` = 2026-11-11T10:38:31Z (the
bookmarks in the dump were applied); `queue_state.positionMs = 50000` for the
single snapshot entry (the legitimate session-restore path, independent of this
defect); `albums.resumeTrackPath` non-null for albums whose songs carry
`resumeMs` (the aggregate is derived from the column).

## 4. Ruled out

- **Unit conversion (s vs ms).** Nothing converts; the join is exact.
- **Queue-snapshot position leaking onto another song.** Positions are copied
  only when `pendingPositionId == target.queueItemId`, and entry ids are
  monotonic past a restored snapshot.
- **Provider payload / sync merge writing the column.** `ProviderSong` has no
  resume field; the restore merge preserves `existing?.resumeMs`; the column is
  LOCAL-owned.
- **Stream cache / partial download.** The offset is a media-item parameter set
  before any data source is touched.
- **Server-side transcode offset (`&timeOffset=`).** `streamUrl` sends id,
  bitrate and format only.
- **Clock/skew in `epochMs()`.** `resumeUpdatedAt` equals `bookmark.changed`
  exactly (device TZ Asia/Kolkata, values UTC).

## 5. The fix (task 6b, 2026-11-11)

Remove the read, keep the data: `ProviderStreamResolver` takes only
`MediaProviderLookup`, `resumePositionMs` is gone, `ResolvedStream` keeps its
`0L` default, and the queue snapshot stays the only auto-resume. Three resolver
resume tests became one contract test (`a resolved stream always starts at
zero`). Storage, the sync merge and the schema are untouched — the column still
feeds `albums.resumeTrackPath` and D-9 prune protection.

Device confirmation: "Jaavedaan Hai" (bookmark 1:20) and "Majboor Tu Bhi
Kahin" (bookmark 0:44) both started at ~0:03 s after selection;
`SELECT COUNT(*) FROM songs WHERE resumeMs > 0` still returned 279.

Opt-in resume (settings gate, provenance, "resumed from m:ss" cue, bookmark
delete-sync) is a separate Phase 6 task — see `resume-and-bookmarks.md` §5.
