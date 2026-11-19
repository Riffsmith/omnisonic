# CSV export of play events — `storage/csv-export.md`

Purpose: pin the CSV document `PlayEventCsvExporter` (Phase 6 task 8) writes
for outside tools — the header, the quoting rules, the encoding, and the SAF
document path it is written through.

How derived: own design decision from the Phase 6 plan architecture note
(RFC 4180 quoting, UTF-8 with BOM optional, exported through the same SAF
`CreateDocument` path as the backup ZIP). Column names reuse the clean v1
`play_events` labels; the ISO-8601 column is additive.

Confidence:
- [VERIFIED] header and column order, row content + ordering, RFC 4180
  escaping of commas/quotes/newlines, CRLF records, empty export, optional BOM
  bytes, SAF sink through the content resolver by `PlayEventCsvExporterTest`
  (6) and `CsvFormatTest` (6) — `:core-data` 412 / 0 (2026-11-19).
- [VERIFIED] The settings entry point wiring (picker → `exportCsv` → exporter,
  running/done/failed states) by `HistorySettingsViewModelTest` (6) and
  `HistorySettingsSectionTest` (2, renders the button and pauses a provider
  through the section) — `:feature-settings` 63 / 0, `:app` 157 / 0
  (2026-11-19).
- [UNKNOWN] CSV size and SAF write time against a real large event log, and
  the device feel of the `CreateDocument` picker for a `.csv` — no device run.

Date: 2026-11-19

Links: `backup-format.md` (the SAF sink seam + backup container),
`../playback/listen-history.md` (what a `play_events` row means),
`../../../.agents/plans/phase-6-tasks.md` (task 8).

---

## Format

- One UTF-8 CSV document, written through `BackupDocumentSink`
  (`SafBackupDocumentSink` over a `CreateDocument` URI in truncating `wt`
  mode) — the same seam as the backup ZIP. Since Phase 6 task 9 the in-app entry point is the "Export
  history as CSV" button in the shell settings "Listen history" section
  (`HistorySettingsSection`), which launches `CreateDocument("text/csv")`
  with the suggested name `omnisonic-history.csv` and writes with the default
  no-BOM flavour.
- Optional UTF-8 BOM (`EF BB BF`) as the very first bytes, controlled by the
  `includeBom` parameter (default off) so spreadsheet tools can be offered a
  BOM'd copy.
- RFC 4180: a field containing a comma, quote, CR or LF is wrapped in quotes
  with embedded quotes doubled; every record — header included — ends in
  CRLF.

## Header

```
providerId,externalId,playedAtUtc,playedAtMs,played,skipped,durationMs
```

`providerId`/`externalId`/`playedAtMs`/`played`/`skipped`/`durationMs` are the
v1 column labels; `playedAtUtc` is the same instant as `playedAtMs` rendered
UTC ISO-8601 (`Instant.toString()`, e.g. `2023-11-14T22:13:20Z`) for tools that
do not parse epochs. Booleans are `true`/`false`.

## Rows

One row per `play_events` row, all providers, ordered by `providerId`,
`playedAtMs`, then row id. The whole log is read in one Room transaction, so
the file is a consistent point-in-time snapshot. An empty log still produces
the header record. `dayStartMs` stays internal (local-day bucket) and is not
exported.

## Writer behaviour

`PlayEventCsvExporter.writeCsv(sink, includeBom)` opens the sink and streams
header + rows, returning the exported event count. The `OutputStream`
overload is the testable core. Device feel / SAF write behaviour of a real
export: [UNKNOWN] — logged, not marked VERIFIED.
