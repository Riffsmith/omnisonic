# Resume points, bookmarks and start position — `resume-and-bookmarks.md`

Purpose: the contract for where a play starts. Pins the start-position
precedence, what a Subsonic-family bookmark means, what Omnisonic reads and
writes today, and where the opt-in resume feature will land.

How derived: own-source reading of `ProviderStreamResolver`,
`QueuePlayerImpl` / `QueuePersistence`, `SubsonicLibrarySyncer.syncBookmarks`,
`SongDao` (resume columns, D-9 prune gate) and `AggregateDao`
(`albums.resumeTrackPath`); the resolver/queue tests
(`ProviderStreamResolverTest`, `QueuePlayerImplTest` snapshot-restore cases);
the OpenSubsonic bookmark surface (`getBookmarks`, `createBookmark`,
`deleteBookmark`, `Child.bookmarkPosition`); and the device + server
measurement recorded in `start-position-investigation.md`.

Confidence:
- [VERIFIED — code] A resolved stream starts at **0**: `ProviderStreamResolver`
  builds `ResolvedStream` without a start position, and it has no song-row
  dependency at all (it takes only a `MediaProviderLookup`). The default in
  `playback-api` (`startPositionMs = 0L`) is the single source of truth — no
  call site writes a literal.
- [VERIFIED — code] The queue owns auto-resume: `QueuePlayerImpl.startCurrent`
  copies the resolver's position unless a pending position is armed for the
  *same* `queueItemId` (`QueuePlayerImpl.kt`, `restore` → `pendingPositionMs` /
  `pendingPositionId`). A fresh selection cannot match a stale pending id
  because entry ids come from a monotonic counter reseeded past a restored
  snapshot.
- [VERIFIED — code + tests] Snapshot restore starts an idle queue at the saved
  position without playing (`restorePositionFor`: below
  `minResumePositionMs` = 10 s, at/past the duration, or unknown entry → 0);
  the `QueuePlayerImplTest` snapshot cases are the regression guard.
- [VERIFIED — code] Radio stations always start at 0 (live streams have no
  resumable position), and `content://`/`file://` entries follow the same rule.
- [VERIFIED — device, 2026-11-11] Never-played, bookmarked songs now start at
  0:00 — "Majboor Tu Bhi Kahin" (server bookmark at 0:44) played from 0:03
  seconds after selection; the stored value stayed in the DB.
- [INFERRED] Subsonic bookmark positions are per user and personal to the
  account, so a value synced into `songs.resumeMs` usually describes another
  client's listening, not ours.

Date: 2026-11-11

Links: `stream-resolution.md` (resolver contract), `controller-and-events.md`
(position persistence), `start-position-investigation.md` (the measured
record behind D-18), `../storage/library-sync-ownership.md` (resume merge rule
and column ownership), `../decisions.md` (D-18)

---

## 1. Start-position precedence

| Priority | Source | Applies when |
|---|---|---|
| 1 | Queue snapshot (`queue_state.positionMs`) | An idle, restored queue starts its current entry; position must be ≥ 10 s and strictly inside the duration |
| 2 | Explicit resume intent (future, Phase 6) | Only with the opt-in setting on and a visible "resumed from m:ss — start over" affordance |
| 3 | **Zero** | Every other case: a new selection, a queue jump, radio, a restored position that fails the guards |

A song-level resume point is **never** an implicit start instruction. That is
the whole of D-18: `songs.resumeMs` is data, and playback does not read it.

## 2. What a bookmark is

- `getBookmarks` returns `{entry, position (milliseconds), username, changed}`
  per user; `createBookmark` **overwrites** the existing bookmark for the same
  entry (there is no update call), and `deleteBookmark` removes it.
- `Child.bookmarkPosition` exposes the same number on song listings, but the
  syncer's merge path uses the bookmark list (it carries `changed`, which the
  merge gate needs).
- A bookmark at position 0 is meaningless; deleting is the honest "no resume
  point" representation.
- Positions are milliseconds end to end — protocol → `ProviderBookmark.positionMs`
  → `songs.resumeMs` → (previously) `startPositionMs`. No unit conversion
  exists anywhere in the chain.

## 3. What Omnisonic does today

**Reads:** nothing, in playback. The column feeds two non-playback consumers:

- `albums.resumeTrackPath` — derived by `AggregateDao`, whose statements gate on
  `songs.resumeMs > 0`; it drives album resume art/track selection.
- D-9 prune protection — rows with `resumeMs IS NOT NULL` are tombstoned, never
  deleted, on a full sync.

**Writes:** nothing of its own. `SubsonicLibrarySyncer.syncBookmarks` is the
only production writer: server bookmark → `SongDao.applyServerResumePoint`,
gated on (a) no pending `resume_point` outbox row, (b) a non-null bookmark
timestamp, (c) `bookmark.changed` newer than `songs.resumeUpdatedAt`. The
merge rule itself is unchanged and still correct — see
`../storage/library-sync-ownership.md`.

**Deletion:** not propagated, in either direction. A bookmark removed on the
server leaves the local value in place, and Omnisonic never uploads a resume
point, so it never deletes one either. Now that playback ignores the column,
staleness is harmless; delete-sync belongs with the resume feature.

## 4. The defect this contract replaces

Between Phase 4 and task 6b the resolver applied `songs.resumeMs` as the start
position of every fresh play. On a measured library 279 of 1389 songs (20 %)
carried a value, and **279/279** were byte-identical to a live server bookmark
in both position and timestamp, every one on a song with `playCount = 0` and no
`play_events` row — i.e. 100 % foreign data, applied deterministically, with no
path that ever cleared it. The fix removed the read rather than the data:
deleting the values would also have erased the album aggregates and the prune
protection, and the values are legitimate on the server. Magnitude table and
the full evidence: `start-position-investigation.md`.

## 5. Opt-in resume (Phase 6, not built)

Only meaningful once Omnisonic writes its own resume points (play counter +
outbox `RESUME_POINT` → `createBookmark`, and clear the bookmark or delete it on
completion). Then:

- `PlaybackSettings` key `resumeSongsFromLastPosition` (default OFF) and a
  `resumeSongMinPositionMs` threshold (~10 s).
- A `ResumePointPolicy` seam so the resolver (or the queue) asks for a
  decision instead of reading a raw column — the resolver must stay free of
  song-row access.
- Provenance: either a `resumeSource` column (`LOCAL` | `SERVER_BOOKMARK`, a
  schema change and a user decision) or the cheaper rule "apply only when our
  own outbox/timestamp write exists".
- A visible "resumed from m:ss — start over" affordance, radio always 0.
- Bookmark deletion sync (and the decision between clearing at 0 and deleting —
  deleting is the Subsonic convention).

## 6. Open questions

- Honour another client's bookmark by default? Proposal: no — silent playhead
  moves are indistinguishable from bugs.
- `resumeSource` column vs. inferred provenance (schema change → user
  decision).
- A one-shot "clear resume points from this device" action (server-side
  deletion, so it would call `deleteBookmark` per id; deferred).
