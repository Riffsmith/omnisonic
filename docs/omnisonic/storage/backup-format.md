# Backup format — `storage/backup-format.md`

Purpose: pin the backup ZIP container the writer (Phase 6 task 6) produces —
entry layout, manifest fields, the versioning rule, and what is deliberately
excluded.

How derived: own design decision from the Phase 6 plan architecture note
(versioned ZIP manifest, JSON entries, one event-log entry per provider,
`java.util.zip`, write through a SAF `CreateDocument` document). Exported field
names reuse the clean v1 column labels so a restore maps straight back onto
schema v1.

Confidence:
- [VERIFIED] entry layout, manifest structure, per-provider isolation, empty
  provider, value round-trip, settings/style snapshots, SAF sink and the
  credential exclusion by `BackupWriterTest` (10) — `:core-data` 361 / 0
  (2026-11-19).
- [UNKNOWN] ZIP size and SAF write time against a real large library, and the
  device feel of the SAF picker flow — no device run in this task.

Date: 2026-11-19

Links: `schema-v2.md`, `outbox-contract.md`, `../playback/listen-history.md`,
`../providers/credential-handling.md` (backup exclusions),
`../../.agents/plans/phase-6-tasks.md` (task 6).

---

## Container

A ZIP written to a user-picked SAF document (`BackupDocumentSink`;
`SafBackupDocumentSink` opens the `CreateDocument` URI through the
`ContentResolver`). Entries, in write order:

| Entry | Content |
|---|---|
| `omnisonic-backup.json` | Manifest — written **first**, so a restore validates it before reading anything else |
| `providers/<id>/user-data.json` | One JSON array of `SongUserData` per provider |
| `providers/<id>/events.json` | One JSON array of `PlayEventRecord` per provider — the full event log |
| `settings.json` | `BackupSettingsSnapshot` (album count rule, supporting-credit toggle, paused provider ids) |
| `style.json` | Optional raw style profile JSON; present only when the caller supplies it, and then referenced from the manifest |

All JSON entries are UTF-8, indented for inspection. `java.util.zip` only — no
streaming ZIP library.

## Manifest

`BackupManifest(formatVersion, appVersion, createdAtMs, providers,
settingsEntry, styleEntry)`; each `ProviderBackupSection(providerId, name,
userDataEntry, eventsEntry, songCount, eventCount)`. `formatVersion` starts at
`1` (`BackupFormat.FORMAT_VERSION`).

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
