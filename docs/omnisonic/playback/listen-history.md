# Listen history recorder — `listen-history.md`

Purpose: Pin down how playback turns into listen history in Phase 6 — which
events count a play, when a resume point is stored, how the event stream is
serialised into database writes, and where the write side lives.

How derived: own design decision (D-7 history controls, D-18 resume semantics)
plus own-source reading of `PlaybackEventBus`, `QueuePlayerImpl` emission order,
the clean v1 schema export, and the existing `:core-data` play counter.
Behavioural context from the study docs' listen-history description
(`DATABASE_SCHEMA.md` §4) is treated as capability evidence only.

Confidence:
- [VERIFIED] The recorder's eligibility rules, ordering and dedupe behaviour are
  covered by `PlayHistoryRulesTest` (6) and `PlayHistoryRecorderTest` (14,
  including failure isolation and position/seek ordering).
- [VERIFIED] `:core-data` `PlayCounter` writes the song counter, the
  `play_events` row, the outbox row and the album/artist aggregates in one Room
  transaction (`PlayCounterTest`, 12, including a forced failure that leaves no
  row behind).
- [INFERRED] Real-device event spacing, background/Doze behaviour and live-server
  scrobble acceptance are not measured in this task.

Date: 2026-11-18

Links: `../storage/schema-v2.md`, `../storage/outbox-contract.md`,
`controller-and-events.md`, `resume-and-bookmarks.md`,
`../decisions.md` (D-7, D-18), `../../.agents/plans/phase-6-tasks.md`
(tasks 1–2).

---

## Layers

| Layer | Owner | Responsibility |
|---|---|---|
| `ScrobblePointTracker` | `:playback` (Phase 4) | Publishes one `PlaybackEvent.ScrobblePoint` per playback at a fixed 0.95 threshold — a threshold *signal*, not a write |
| `PlayHistoryRecorder` | `:playback` (task 1) | Owns the eligibility rules, the current-playback state and the single-writer event queue |
| `PlayHistorySink` | `:playback` seam | Suspend write API; production `CoreDataPlayHistorySink` delegates to `:core-data` |
| `PlayCounter` | `:core-data` | One Room transaction per call: materialise the song row, update local state, insert the event row, enqueue the outbox delta, recompute the album and artist rows |

`CoreDataPlayHistorySink` maps the queue entry onto a minimal
`SongRepository.SongSnapshot` (title, artist, album external id, cover id,
duration), so a play from an unsynced queue item still materialises a local row.

## Rules

[VERIFIED] `PlayHistoryRules`:

- `countsAsPlay(positionMs, durationMs, thresholdFraction)`: true when the
  duration is positive and `positionMs >= duration * fraction` (truncating, same
  rounding as the Phase 4 tracker). Unknown duration never counts — only a
  natural finish can record such a track.
- `resumePositionMs(...)`: the position is stored only when it is > 0, at or above
  the larger of `minPositionMs` (default 10 s) and `duration * minFraction`
  (default 5 %), and below the play threshold. A playback that already counts as
  a play needs no resume marker.

Defaults are `0.95` play threshold, `10 s` absolute resume floor, `5 %` relative
resume floor. Only the play threshold has a studied default (95 %); the resume
floors are Omnisonic defaults until the task 9 setting lands.

## Aggregates

[VERIFIED by `PlayCounterTest`] Every play and every resume write refreshes the
rows derived from the song: the album (play count, last played, resume track)
and the artists credited on that song. The refresh runs at the end of the same
transaction, so a failed recompute rolls the whole play back — no song counter,
event row or outbox row survives on its own. A skip touches no derived value
and does not recompute.

Album play count follows the configurable rule from `PlayAggregateSettings`
(`SUM` / `AVERAGE` / `MINIMUM`, default `AVERAGE` with half-up rounding).
Artist play count and last played honour the supporting-credit toggle (default
off: only track-level credits count). Both defaults follow the studied
behaviour (per-track mean for albums, track-level credits for artists)
[INFERRED]; the holder keeps them in process memory until the history settings
store lands (task 9).

## Events

[VERIFIED] The recorder handles `Started`, `Paused`, `Resumed`, `Position`,
`TrackChanged`, `Finished`, `Skipped` and `ScrobblePoint`.

- `Started` / a `TrackChanged` with a current entry begin a playback.
- A play is recorded when a `Position` while `isPlaying` crosses the threshold,
  when `ScrobblePoint` carries a position that satisfies the *recorder's* rule,
  or on `Finished` (a natural finish is a full play even below the threshold).
  The counted flag is per playback, so hints and finishes cannot double count.
- A `Position`/`Paused`/`Resumed` event only advances an already open span and
  stores the **latest** reported position. A restart or seek backwards moves
  the resume candidate back with the listener; a tick stamped with the next
  queue entry (the engine is still on the previous item while the next one
  resolves) can neither open that entry nor count it early.
- `ScrobblePoint` is deliberately re-checked: a threshold configured below 95 %
  counts earlier than the tracker; a threshold above it never counts early on
  the hint.
- `Skipped` records a skip when the playback had progressed (> 0 ms). An early
  change above the minimum position also stores the resume point.
- A plain `TrackChanged` closes the playback and stores a resume point when the
  position is early; it never records a skip (the skip signal is `Skipped`).
- Repeat-one is `Finished` → `TrackChanged` with the same entry, which closes and
  re-opens the playback: each pass can count.
- Radio entries (`isRadioStation`) are ignored entirely — no stable library
  identity.

**Position trap** [VERIFIED by reading `QueuePlayerImpl`]: `Finished` and
`Skipped` pass the last playback position in their `playedAtMs` field, not a
wall-clock stamp. The recorder stamps timestamps from its clock and treats the
field only as a position hint.

## Serialisation

[VERIFIED by test] The bus is collected into an unbounded `Channel` and drained
by one coroutine, so writes are applied in emission order and two transactions
can never interleave. Eligibility state (`open`, `positionMs`, `counted`) is
owned by that single writer; there are no locks and no shared maps.

[VERIFIED by `a failed write does not stop later history`] A write failure is
logged and the drain continues with the next event. Without that guard a single
failed transaction killed the only drain coroutine while the collector kept
filling the unbounded channel, so listen history stopped silently for the rest
of the process.

## Wiring

`PlaybackWiringModule` provides `PlayHistorySink` bound to
`CoreDataPlayHistorySink` and starts `PlayHistoryRecorder` with the engine scope
at playback-stack assembly, so it runs for the process lifetime. Threshold and
resume floors are constructor parameters (task 9 injects the settings values).

## Read side (task 4)

[VERIFIED by `RecentPlaysQueryTest` (5) + `PlayHistoryRepositoryTest` (3)] The
recent-plays list reads `play_events` directly — newest first on the absolute
`playedAtMs` stamp with row-id tie-break so LIMIT/OFFSET windows stay stable —
through `PlayEventDao.recentPlaysRaw`/`recentPlays` (ProfileScope convention: the
raw query projects `excludedProviderIdsCsv`, the wrapper takes the scope), LEFT
JOINed to `songs`/`albums` for title/artist/album/cover. A pruned song row never
hides its events; the row falls back to `externalId`. Pages are LIMIT/OFFSET
windows over `PlayHistoryRepository.recentPlays`, using the same `fetchPage`
one-past-the-window probe as browse (limit clamped to the shared 1..200 bounds),
plus `recentCount` for the header. A debounced `play_events` invalidation feed
(`PlayHistoryRepository.changes`, same 250 ms quiet period as
`LibraryInvalidation`) drives the live refresh of an open list.

[VERIFIED by `HistoryViewModelTest` (6) + `HistoryScreenTest` (4)] The UI is a
destination-scoped `HistoryViewModel` (one `SectionPager` over the `HistorySource`
seam, keyed on event id) rendered by `HistoryRoute`/`HistoryScreen` at the
`history` route (route table extended, never renamed), entered from the library
hub's "Recent plays" row. Header shows the title and total count; rows show
cover, song title (or external id), a skipped marker plus artist · album, the
absolute played-at and the duration; loading/empty/error states and the load-more
footer follow the section-screen contract. Rows carry the D-7 controls in an
overflow menu (see §Controls). Device feel `[VERIFIED device 2026-11-19 moto g32
Android 13, debug APK fresh install]`: hub → "Recent plays" renders the paged
newest-first list live (header count 3→4→5 as plays/skips landed; rows show
title, artist · album, absolute played-at, duration, "Skipped" marker, per-row
"More options" button); empty state after a wipe.

## Controls (task 5, D-7 confirmed 2026-11-19)

[VERIFIED by `HistoryEditorTest` (12) + the `PlayCounterTest` pause case +
`HistoryViewModelTest` (10) + `HistoryScreenTest` (8)] The write side is
`HistoryEditor` (`:core-data` `history/`), one Room transaction per mutation,
exposed to the UI through the `:feature-library` `HistoryControls` seam
(`RepositoryHistoryControls`, bound in `HistoryModule`); `HistoryViewModel`
delegates and the change feed reloads the open window after every mutation.

- **Remove this play** (per-row menu item, confirm dialog): the event row is
  gone; a played event also decrements `songs.playCount` (clamped at 0,
  `lastPlayed` re-derived from the remaining play events) and removes that
  event's `playedAtMs` from any unsubmitted outbox play-count row
  (`OutboxQueue.removePlayTimestamp`; the row is deleted when nothing
  remains). A skip event only loses its row — the skip marker is state, not a
  count. Server-side correction of an already accepted scrobble is out of
  reach — Subsonic has no un-scrobble [INFERRED] — so deletes are local +
  pending-outbox only.
- **Edit play count** (per-row menu item, numeric dialog prefilled from
  `RecentPlayRow.playCount`): sets an explicit value clamped at 0 (zero clears
  `lastPlayed`) and adjusts the unsubmitted outbox delta by the same amount,
  floored at 0 (`adjustPlayCountDelta` consumes the newest queued timestamps
  for negative deltas, appends synthesised ones for positive). Event rows are
  untouched.
- **Clear all history for this provider** (per-row menu item, confirm dialog):
  deletes the provider's `play_events`, resets its songs'
  `playCount`/`lastPlayed`/`skipped`/`lastSkipped`, drops its unsubmitted
  `play_count` outbox rows and recomputes album/artist counts for that
  provider. Ratings, favorites, resume points and other providers are
  untouched.
- **Pause/Resume recording for this provider** (per-row menu item):
  `HistoryRecordingGate` (in-memory `pausedProviders` StateFlow) is read by
  `PlayCounter` on every record call; a paused provider writes nothing (null
  returns) and keeps its existing rows. Persistence + the settings UI land in
  task 9.
- **Aggregates after a control mutation are count-only**:
  `AggregateDao.recompute*Counts*` refresh `playCount`/`lastPlayed` only and
  never touch the locally-owned `albums.resumeTrackPath`. The sync-side sweep
  that re-derives aggregates after a move/prune stays deferred (findings),
  now with the count-only variants available.

Device feel `[VERIFIED device 2026-11-19 moto g32 Android 13, fresh debug
install, real Subsonic provider synced 1527 songs]` — all four overflow items
present and working, exercised end to end with DB + screenshot evidence:
menu opens with Remove this play / Edit play count / Clear all history for
this provider / Pause recording for this provider; pause relabels the item to
Resume and a skip while paused wrote **no** `play_events` row (count stayed 3),
a skip after resume wrote one; Remove this play → confirm dialog → event row
gone and the song's `playCount` 1→0 with `lastPlayed` cleared; Edit play count
→ dialog prefilled with the row's count → set 5 → `playCount`=5; Clear all
history for this provider → confirm dialog → `play_events` 0, all songs' play
state reset, `resumeMs` preserved (110270 kept), outbox empty, list fell back
to the empty state; a post-wipe skip recorded normally. Note: the pending
outbox rows drained to the server within seconds (server reachable), so the
unsubmitted-row edit was observed via the local counter, not a lingering row.

## Deferred

- The album rule and the supporting-credit toggle are process-memory defaults;
  task 9 adds the persisted settings store and the UI.
- Resume points do not yet enqueue an outbox change — the drain already
  supports the `resume_point` action, but the recorder's resume write stays
  local until the task 9 settings store feeds it.
- Per-provider recording pause is in-memory (task 5); its persisted settings
  surface and the threshold setting are task 9.
- Real-device event spacing and live-scrobble acceptance stay [UNKNOWN].
