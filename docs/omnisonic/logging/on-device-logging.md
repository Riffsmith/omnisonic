# On-device logging — `on-device-logging.md`

Purpose: pins down Omnisonic's persistent logging contract: the three rotating
streams, the one redaction pipeline every persisted line passes through, the
opt-in metadata-only network stream, the synchronous crash handler, and the
Settings > About export. No claim here changes the existing `LogLevel`,
`LogSink`, `OmnisonicLogger` or `CredentialRedactor` semantics.

How derived: the user's answers to the open questions in
`.agents/memory/findings/logging-investigation.md` (2026-11-18), implemented
against the shipped code in this repository and verified by the tests named
below. No external artifact was inspected for this document.

Confidence: `[VERIFIED code/JVM]` for every shape, default, rotation rule,
redaction path and export behaviour that a named JVM test or the compiled
graph asserts; `[UNKNOWN device]` for crash survival, release-logcat
behaviour and the receiver side of the share sheet.

Date: 2026-11-18

Links: `../decisions.md` (D-33), `../providers/credential-handling.md` (the
redaction contract reused here), `../README.md`.

---

## 1. One pipeline, three streams

Every persisted line is produced by an `OmnisonicLogger`, so it has already
passed `CredentialRedactor` before any sink or file sees it. No subsystem
writes a log file directly. `[VERIFIED code]`

| Stream | File | Sinks | Level written |
|---|---|---|---|
| Runtime | `filesDir/logs/runtime.log` | `TeeLogSink(AndroidLogSink, FileLogSink)` | `DEBUG` in debug builds, `INFO` in release |
| Network | `filesDir/logs/network.log` | `FileLogSink` only (never logcat, never the runtime file) | same level policy |
| Crash | `filesDir/logs/crash.log` | `FileLogSink(syncWrites = true, lockWaitMs = 2000)` | same level policy; a fatal trace is ERROR, so it always passes |

- The tee keeps the pre-existing logcat behaviour: a failing file writer is
  swallowed per sink (`TeeLogSink` catches each `write`), so disk errors cannot
  silence logcat. `ProviderWiringTest` still finds the `HttpClient` tag in
  `ShadowLog`. `[VERIFIED JVM]`
- The single release/debug level switch lives in `LoggingWiringModule`:
  `BuildConfig.DEBUG` → `DEBUG`, otherwise `INFO`. The app accepts that release
  logcat is INFO-only as a consequence. `[VERIFIED code]`
- The network stream has its own `@NetworkLogger` instance and never reaches
  `AndroidLogSink`, because a shareable network trace must not appear in the
  system log. `[VERIFIED code]`

## 2. Rotation

`RotatingLogFile` keeps `<name>.log` plus `<name>.1` and `<name>.2` (three files
per stream) and rotates by delete-oldest when appending a line would exceed
1 MiB, so each stream is bounded at roughly 3 MiB. `[VERIFIED JVM]`
`RotatingLogFileTest` covers the cap, the three-file bound, the newest-line
location and the `maxFiles <= 1` guard.

Age-based retention is **not** implemented: a quiet install keeps its last
3 MiB per stream indefinitely, and the export always contains whatever is
currently on disk. `[INFERRED — code review]`

## 3. Line format

`FileLogSink` writes one record per event:

```
2026-11-18T09:41:02.113 INFO  [main] Shell: navigation -> library
```

Timestamp (local zone, millisecond precision), level padded to five columns,
thread name, tag, message, then the scrubbed stack trace on following lines when
a throwable is present. Append is blocking because callers are already on a
request/transport path; the crash stream instead uses the bounded `tryLock`
described below. `[VERIFIED JVM]`

## 4. Network logging (metadata only)

`NetworkLogInterceptor` is an application interceptor registered directly after
the existing `HttpLoggingInterceptor` in `HttpClientFactory`, so one line covers
the whole call including redirects and retries. It writes:

- method, URL (credential query values redacted by the shared redactor),
- response code, elapsed milliseconds,
- body length when known and the body content type.

It **never** writes request or response headers, query values are not
reconstructed, and it never reads the body: no `string()`, `bytes()` or
`peekBody()`. An `IOException` writes one WARN line with the exception class and
message, then rethrows the same exception. `[VERIFIED JVM]`
`NetworkLogInterceptorTest` asserts the absence of `Authorization`/`Bearer`
material and of the probe body/header secrets.

The setting is `ShellPreferences.networkLoggingEnabled` (DataStore key
`shell.network_logging`, default `false`). `SettingsNetworkLogGate` keeps a
volatile copy of the flow and is consulted **per call** inside `intercept`, so
turning the switch on or off affects the next request without rebuilding any
client. `[VERIFIED code]`

## 5. Crash logging

`CrashLogInstaller` is installed first in `OmnisonicApplication.onCreate()`. Its
handler writes through a dedicated crash logger on the crashing thread:

1. `crash.log` append, fsynced, with a 2 s `tryLock` (a lock held by a dying
   thread loses the line instead of hanging the process),
2. the same entry mirrored into `runtime.log`,
3. delegation to the previously installed handler exactly once in `finally`,
   with `printStackTrace` as the fallback when no handler existed.

There is no coroutine or executor hop. Each write is wrapped so a storage
failure cannot skip the delegation. `[VERIFIED code/JVM]`
`CrashLogInstallerTest` covers redaction, the runtime mirror, exactly-once
delegation, restoration on `uninstall`, and a throwing crash writer.

**Limits:** native crashes (SIGSEGV), the low-memory killer and OOM kills never
reach a JVM uncaught-exception handler; a crash loop appends one trace per
process death with no deduplication. Crash survival itself has not been tested
on a device or emulator — only the handler's code path is JVM-tested.
`[UNKNOWN device]`

## 6. Export

Settings > About has a **Share logs** row. It calls `LogExport.shareIntent`,
which:

- zips every file currently under `filesDir/logs` (runtime, network, crash and
  their rotated siblings) plus `export-info.txt` (format version, app version,
  build type, export time) into `cacheDir/log_exports/`,
- deletes stale zips in that directory first,
- exposes the zip through the `content://<applicationId>.fileprovider/...`
  provider declared in the manifest with `res/xml/log_file_paths.xml`,
- returns an `ACTION_SEND` chooser for `application/zip` with
  `FLAG_GRANT_READ_URI_PERMISSION` on both the send intent and the chooser.

No new Android permission is added; the read grant is per-share. If the zip
cannot be written the row does nothing rather than crash. `[VERIFIED JVM]`
`LogExportTest` asserts the zip entries, the metadata file, the provider URI
prefix, the grant flag and stale-export cleanup.

## 7. What the runtime stream contains

Beyond the pre-existing provider/sync/outbox/HTTP lifecycle lines:

- **Navigation and user actions** — `AppEventLog` records `navigation -> <route>`
  and `user action: <name>` for tab taps, the player sheet's open/collapse/
  queue/library actions, queue back, and the mini-player transport buttons.
  Routes and action names only; never media titles, search text or queue
  contents.
- **Playback transport** — `PlaybackControllerImpl` records play/stop/pause/
  resume, seek target, speed/pitch, skip, jump, repeat/shuffle, queue edits and
  snapshot restore. IDs, indexes and counts only; never song metadata.

`[VERIFIED code]` The instrumentation is shell-level and controller-level; the
queue, search and notification feature screens are untouched.

## 8. Verification status

- `:core-model:test`, `:core-network:test` — green, including the new
  `TeeLogSinkTest` and `NetworkLogInterceptorTest`.
- `:app:testDebugUnitTest` for `app.omnisonic.log.*`, `ProviderWiringTest` and
  `NotificationSettingsRowTest` — green.
- `:app:assembleDebug` — green.
- Not performed: any device or emulator run, a real crash, a real share-sheet
  target, and release-build logcat inspection. `[UNKNOWN device]`
