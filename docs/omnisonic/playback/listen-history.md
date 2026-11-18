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
footer follow the section-screen contract. Rows are display-only until the
history-controls task. Device feel [UNKNOWN] — no device attached in the task-4
session.

## Deferred

- The album rule and the supporting-credit toggle are process-memory defaults;
  task 9 adds the persisted settings store and the UI.
- Resume points do not yet enqueue an outbox change; task 3 wires that drain.
- Per-provider recording pause and the threshold setting are task 9.
- Real-device event spacing and live-scrobble acceptance stay [UNKNOWN].
