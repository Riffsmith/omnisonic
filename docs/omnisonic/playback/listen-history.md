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
  covered by `PlayHistoryRulesTest` (6) and `PlayHistoryRecorderTest` (11).
- [VERIFIED] `:core-data` `PlayCounter` writes the song counter, the
  `play_events` row and the outbox row in one Room transaction
  (`PlayCounterTest`, 5).
- [INFERRED] Real-device event spacing, background/Doze behaviour and live-server
  scrobble acceptance are not measured in this task.

Date: 2026-11-18

Links: `../storage/schema-v2.md`, `../storage/outbox-contract.md`,
`controller-and-events.md`, `resume-and-bookmarks.md`,
`../decisions.md` (D-7, D-18), `../../.agents/plans/phase-6-tasks.md` (task 1).

---

## Layers

| Layer | Owner | Responsibility |
|---|---|---|
| `ScrobblePointTracker` | `:playback` (Phase 4) | Publishes one `PlaybackEvent.ScrobblePoint` per playback at a fixed 0.95 threshold — a threshold *signal*, not a write |
| `PlayHistoryRecorder` | `:playback` (task 1) | Owns the eligibility rules, the current-playback state and the single-writer event queue |
| `PlayHistorySink` | `:playback` seam | Suspend write API; production `CoreDataPlayHistorySink` delegates to `:core-data` |
| `PlayCounter` | `:core-data` | One Room transaction per call: materialise the song row, update local state, insert the event row, enqueue the outbox delta |

`CoreDataPlayHistorySink` maps the queue entry onto a minimal
`SongRepository.SongSnapshot` (title, artist, album external id, cover id,
duration), so a play from an unsynced queue item still materialises a local row.

## Rules

[VERIFIED] `PlayHistoryRules`:

- `countsAsPlay(positionMs, durationMs, thresholdFraction)`: true when the
  duration is positive and `positionMs >= duration * fraction` (truncating, same
  rounding as the Phase 4 tracker). Unknown duration never counts — only a
  natural finish can record such a track.
- `resumePointMs(...)`: the position is stored only when it is > 0, at or above
  the larger of `minPositionMs` (default 10 s) and `duration * minFraction`
  (default 5 %), and below the play threshold. A playback that already counts as
  a play needs no resume marker.

Defaults are `0.95` play threshold, `10 s` absolute resume floor, `5 %` relative
resume floor. Only the play threshold has a studied default (95 %); the resume
floors are Omnisonic defaults until the task 9 setting lands.

## Events

[VERIFIED] The recorder handles `Started`, `Paused`, `Resumed`, `Position`,
`TrackChanged`, `Finished`, `Skipped` and `ScrobblePoint`.

- `Started` / a `TrackChanged` with a current entry begin a playback.
- A play is recorded when a `Position` while `isPlaying` crosses the threshold,
  when `ScrobblePoint` carries a position that satisfies the *recorder's* rule,
  or on `Finished` (a natural finish is a full play even below the threshold).
  The counted flag is per playback, so hints and finishes cannot double count.
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

## Wiring

`PlaybackWiringModule` provides `PlayHistorySink` bound to
`CoreDataPlayHistorySink` and starts `PlayHistoryRecorder` with the engine scope
at playback-stack assembly, so it runs for the process lifetime. Threshold and
resume floors are constructor parameters (task 9 injects the settings values).

## Deferred

- Album/artist aggregates and the album-count rule are task 2 (the DAO and rule
  exist but the play path does not call them yet).
- Resume points do not yet enqueue an outbox change; task 3 wires that drain.
- Per-provider recording pause and the threshold setting are task 9.
- Real-device event spacing and live-scrobble acceptance stay [UNKNOWN].
