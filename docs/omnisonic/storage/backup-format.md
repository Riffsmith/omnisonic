# Backup format — `storage/backup-format.md`

Purpose: pin the backup ZIP container the writer (Phase 6 task 6) produces and
the reader (task 7) validates and applies — entry layout, manifest fields, the
versioning rule, the restore semantics, and what is deliberately excluded.

How derived: own design decision from the Phase 6 plan architecture note
(versioned ZIP manifest, JSON entries, one event-log entry per provider,
`java.util.zip`, write through a SAF `CreateDocument` document). Exported field
names reuse the clean v1 column labels so a restore maps straight back onto
schema v1.

Confidence:
- [VERIFIED] entry layout, manifest structure, per-provider isolation, empty
  provider, value round-trip, settings/style snapshots, SAF sink and the
  credential exclusion by `BackupWriterTest` (10); validation order, merge vs
  replace counts, the conflict report, rejection-with-no-writes and the
  writer→reader round trip by `BackupRestorerTest` (23) — `:core-data`
  412 / 0 (2026-11-19).
- [UNKNOWN] ZIP size and SAF write/read time against a real large library,
  restore of ~thousands of events, and the device feel of the SAF picker
  flows — no device run in these tasks.

Date: 2026-11-19

Links: `schema-v2.md`, `outbox-contract.md`, `../playback/listen-history.md`,
`../providers/credential-handling.md` (backup exclusions), `../decisions.md`
D-36 (restore semantics) and D-37 (UI deferral),
`../../../.agents/plans/phase-6-tasks.md` (tasks 6–7).

---

## Container

A ZIP written to a user-picked SAF document (`BackupDocumentSink`;
`SafBackupDocumentSink` opens the `CreateDocument` URI through the
`ContentResolver` in truncating `wt` mode). Entries, in write order:

| Entry | Content |
|---|---|
| `omnisonic-backup.json` | Manifest — written **first**, so a restore validates it before reading anything else |
| `providers/<id>/user-data.json` | One JSON array of `SongUserData` per provider |
| `providers/<id>/events.json` | One JSON array of `PlayEventRecord` per provider — the full event log |
| `settings.json` | `BackupSettingsSnapshot` (album count rule, supporting-credit toggle, paused provider ids, play threshold, minimum resume position) |
| `style.json` | Optional raw style profile JSON; present only when the caller supplies it, and then referenced from the manifest |

All JSON entries are UTF-8, indented for inspection. `java.util.zip` only — no
streaming ZIP library.

## Manifest

`BackupManifest(formatVersion, appVersion, createdAtMs, providers,
settingsEntry, styleEntry)`; each `ProviderBackupSection(providerId, name,
userDataEntry, eventsEntry, songCount, eventCount)`. `formatVersion` starts at
`1` (`BackupFormat.FORMAT_VERSION`) and is a required field on decode; the
reader rejects any other (or missing) value before writing anything (§Restore).

## Row shapes

- `SongUserData` carries the locally-owned song columns only — `externalId`
  (identity; `filePath` for local files without one), `playCount`,
  `lastPlayed`, `skipped`, `lastSkipped`, `userRating`, `isFavorite`,
  `favoriteAt`, `resumeMs`, `resumeUpdatedAt`. v1 has no skip *counter*: the
  skip marker pair (`skipped`, `lastSkipped`) is exported as-is
  [VERIFIED by schema export]. Only songs that carry at least one of these
  values are exported — the catalog itself is not duplicated into the backup.
- `PlayEventRecord` carries one `play_events` row minus its local row id
  (restore keys on `externalId` + `playedAtMs`): `playedAtMs`, `dayStartMs`,
  `played`, `skipped`, `durationMs`, ordered by `playedAtMs`.
- `BackupSettingsSnapshot` carries all five history preferences. The task-9
  `playThresholdPercent` and `resumeMinPositionMs` fields have v1 defaults on
  decode (95 and 10 000 ms), so backups written before those fields existed
  remain readable without a format-version bump.

## Exclusions

- Provider credential columns (`secretBlob`, `mtlsKeystoreBlob`,
  `trustedCertsBlob`) are never read by the writer — they are sealed with the
  device-bound Keystore and unreadable after a restore anyway; see
  `BackupExcludes` and `providers/credential-handling.md`. Pinned by the
  `sealed provider credential blobs never enter the backup` test.
- The outbox is transient pending state, not user data — not exported.
- Downloads/offline files are out of scope for Phase 6 (plan note, task 6).

## Writer behaviour

`BackupWriter` collects the whole snapshot inside one read transaction (a
consistent point-in-time view), then streams the entries into the ZIP. The
manifest is serialized before the data entries so it can be written first.
App version and style profile come in as call parameters (the app layer owns
`BuildConfig` and the style store); the settings snapshot is read from
`PlayAggregateSettings` + `HistoryRecordingGate`. Device feel / SAF behaviour
of a real export: [UNKNOWN] — logged, not marked VERIFIED.

## Restore (task 7, `BackupRestorer`, D-36)

`BackupRestorer.readBackup` parses and validates the whole ZIP **before any
database write** and rejects with a `BackupRestoreException`:

- not a ZIP (`PK` sniff, `ZipException`), or a missing/truncated
  end-of-central-directory record → `MalformedBackup`;
- no manifest entry, or the manifest is not the **first** entry →
  `MissingManifest` / `MalformedBackup`;
- manifest JSON undecodable or `formatVersion` missing (the field is required
  on decode) → `MalformedEntry`; `formatVersion != 1` →
  `UnsupportedFormatVersion`;
- a referenced provider/settings/style entry absent → `MissingEntry`;
  undecodable, non-canonical provider entry names, section counts that do not
  match the decoded arrays, rows without identity (`externalId`/`filePath`,
  `playedAtMs`), an unknown `albumCountRule`, or a non-JSON style entry →
  `MalformedEntry`.

`preview(parsed)` is read-only and reports per provider section the resolved
local provider id, backup song/event counts, merge-only `newEvents` /
`duplicateEvents` (key: `externalId` + `playedAtMs`), matched/unmatched songs,
the local event count and the conflict list. `restore(parsed, mode)` applies
everything in **one Room transaction**, then applies the settings snapshot to
`HistoryPreferencesStore`, `PlayAggregateSettings` and `HistoryRecordingGate`;
paused source-provider ids are remapped through the same applied-provider
results. It returns the style profile JSON for the caller (the app layer owns
the style store).

Semantics (user-confirmed 2026-11-19, D-36):

- **Provider matching:** manifest `providerId` first, then one unambiguous
  `name` match; unmatched or ambiguous sections are reported
  (`applied = false`) and skipped. Sections that would resolve to the same
  local provider are all skipped rather than applied sequentially.
- **MERGE:** inserts only events whose (`externalId`, `playedAtMs`) is not
  already local or earlier in the same backup; preview uses the same rule, so
  its new/duplicate counts match apply. The backup wins user-data conflicts,
  which are reported per song with the differing field names.
- **REPLACE:** per matched provider, deletes all local `play_events`, resets
  play state on all its songs, drops pending `play_count` outbox rows (same
  scope as the task-5 wipe), then inserts the backup events and applies the
  backup user data (all nine locally-owned columns). Duplicate event rows in
  the backup are all restored in this mode, preserving writer round-trip
  fidelity. Songs absent from the backup lose play counters but keep
  rating/favorite/resume.
- Events are always inserted even when the song row is not local yet (the
  history read LEFT JOINs songs); their user data is reported as
  `songsSkipped`. `dayStartMs` is recomputed in the local zone — a backup can
  come from another time zone. Album/artist aggregates are recomputed
  count-only with the snapshot's rule after the apply.
- The pending outbox is otherwise untouched: restore is a local data
  operation and never re-submits scrobbles [decision recorded, D-36].

SAF read seam: `BackupDocumentSource` + `SafBackupDocumentSource`
(`OpenDocument` URI → `ContentResolver.openInputStream`), the mirror of the
writer's sink. Device feel of a real restore / SAF read: [UNKNOWN] — no device
run.
