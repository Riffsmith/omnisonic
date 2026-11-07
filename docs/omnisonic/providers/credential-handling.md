# Credential handling and logging — `providers/credential-handling.md`

Purpose: pins how sealed credentials behave when they cannot be unsealed, how
throwables are handed to log sinks, and which columns never leave the device
through backup/export.

How derived: audit finding in the pre-release review (raw throwable
passed to sinks; mapper substituting empty credentials for unreadable blobs).

Confidence: all behaviours [VERIFIED] by the tests listed below.

Date: 2026-11-06

Links: `core-model` `OmnisonicLogger`/`CredentialRedactor`; `core-data`
`ProviderConfigMapper` (`ProviderLoadResult`), `ProviderClientRegistry`,
`ProviderStatusTracker`, `BackupExcludes`; `../decisions.md`; `provider-connections.md`.

---

## Unreadable credentials are explicit

- `ProviderConfigMapper.from` returns `ProviderLoadResult`:
  `Loaded(connection)` | `CredentialsUnreadable(providerId)` | `Unloadable`.
  A secret blob that exists but cannot be unsealed is NEVER surfaced as empty
  credentials; the row does not load. [VERIFIED by mapper tests]
  - A row with NO secret blob still loads as a connection without secrets
    (legacy/credential-free rows keep working). [VERIFIED]
- `ProviderClientRegistry` refuses to serve a provider whose credentials are
  unreadable and reports the provider id through its
  `onCredentialsUnreadable` callback, which the app wiring connects to
  `ProviderStatusTracker.markCredentialsUnreadable`. [VERIFIED by
  `unreadable credentials leave the provider unserved` — red before the fix,
  which served an empty-password client.]
- `ProviderStatusTracker` shows `AUTH_FAILED` ("re-enter password") for
  marked providers until cleared. [VERIFIED, pre-existing test
  `unreadableCredentialsMarkAuthFailedUntilCleared`]
- The same `AUTH_FAILED` marker is set for a server authentication rejection
  reported as `ProviderAuthException` (Subsonic codes 40–44, HTTP 401/403).
  It clears when the provider row is edited (`updatedAt` changes) or when a
  later provider request succeeds (the Subsonic client reports success to the
  factory, which clears the tracker). Network outages and server errors never
  set it. [VERIFIED by `ProviderStatusTrackerTest`, `SubsonicClientTest`,
  `SubsonicLibrarySyncerTest`, `OutboxDrainerTest`, and
  `OutboxDrainEndToEndTest`]

## Sinks receive scrubbed throwables

- `OmnisonicLogger.write` passes a scrubbed wrapper to the sink: redacted
  message, ORIGINAL stack trace, and a redacted cause chain (depth-capped).
  The raw throwable never reaches a sink. [VERIFIED by the three
  `scrubbedThrowable`/`sinksReceiveAScrubbedThrowable` tests — red before the
  fix.]
- The log MESSAGE no longer concatenates `error.toString()`; the scrubbed
  throwable carries the detail (Logcat prints message + throwable).

## AndroidLogSink

- `:app` ships `AndroidLogSink` mapping DEBUG/INFO/WARN/ERROR onto
  Log.d/i/w/e so release logs are not lost to println. It only forwards
  already-redacted content. [VERIFIED by `AndroidLogSinkTest` under
  Robolectric]
- `LoggingWiringModule` provides exactly one `LogSink` (`AndroidLogSink`) and
  one `OmnisonicLogger` as Hilt singletons; `ProviderClientRegistry` and its
  default API/MEDIA client factories reuse that injected logger (no
  production `ConsoleLogSink`). [VERIFIED by `ProviderWiringTest` in `:app`,
  which pins the shared instance and a registry warning reaching `ShadowLog`]

## Backup exclusions

- `core-data.BackupExcludes.providerColumns` lists every provider column that
  must never leave the device: `secretBlob`, `mtlsKeystoreBlob`,
  `trustedCertsBlob` (all sealed with the device-bound Keystore key).
  [VERIFIED by `BackupExcludesTest`, which also asserts every entry names a
  real `ProviderEntity` column, so renames fail the test.]
- Phase 6 backup rules and any export/share feature must consult this list.
