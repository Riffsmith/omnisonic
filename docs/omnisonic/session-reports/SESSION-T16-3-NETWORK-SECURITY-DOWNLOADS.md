# SESSION T16-3 — NETWORK STATUS, OPT-IN mTLS, DOWNLOAD OWNERSHIP

Date: 2026-11-02
Scope: Session 3 of the five-session T16 plan (the "behaviour layer"). Inputs read
in full: `Prompt.md` (incl. revised T16 and the T17 note), the revised T16
specification, `SESSION-T16-1-AUDIT.md`, `SESSION-T16-2-PROVIDERS.md`, and the tree
at session start.

**Git state at start:** `main`, 13 commits ahead of `origin/main` (unpushed),
working tree carried the intentional incomplete Session 2 state (modified sources +
Session 1/2 reports untracked). No user changes needed preserving; nothing was
stashed or reverted except the KSP schema drift described in §6.1.

**Nothing committed** (single T16 commit stays in Session 5). No separate migration,
no schema `2.json`, `SCHEMA_VERSION` still 1, no destructive fallback. The working
tree is again left intentionally incomplete; temporary states are in §7.

## 1. What was built

### 1.1 `ProviderStatusTracker` (core-data/network/)

- `provider-api/ProviderStatus.kt` (new): `enum ProviderStatus { OK, NEEDS_WIFI,
  AUTH_FAILED, OFFLINE }`, `fun interface ProviderRunGate { suspend check(providerId):
  ProviderStatus }`, and `AllowAllRunGate` for call sites without a tracker.
- `NetworkMonitor.kt` (new): `data class NetworkState(connected, wifi)` +
  `fun interface NetworkMonitor` + `ConnectivityManagerNetworkMonitor` reading the
  active network's `NetworkCapabilities` (`NET_CAPABILITY_INTERNET`,
  `TRANSPORT_WIFI`). Injected seam; no other class touches `ConnectivityManager`.
- `ProviderStatusTracker.kt` (new, `@Singleton @Inject`): exposes
  `StateFlow<Map<Long, ProviderStatus>>` (`statuses`), `refresh()`, `check(id)`,
  and the shared `runGate`. Status derivation: `AUTH_FAILED` (credentials marker,
  §1.4) > `OFFLINE` (not connected) > `NEEDS_WIFI` (row `wifiOnly` && connected on
  non-Wi-Fi) > `OK`. Unknown provider ids resolve to `OK` — the gate owns network
  facts only; a missing row is the locator's concern.
- DI: `DatabaseModule` now provides a `@Singleton NetworkMonitor`
  (`ConnectivityManagerNetworkMonitor`); the tracker itself is `@Inject`-constructed.
- **VPN note:** a VPN-only network reports `TRANSPORT_VPN`, and the platform
  exposes no public API for the underlying transport (`NetworkCapabilities`
  mutators/underlying lookups are not in the compileSdk 36 public stubs), so a VPN
  network is treated as **not Wi-Fi**. A Wi-Fi-only provider therefore stays
  `NEEDS_WIFI` behind a VPN; documented in the KDoc — metered data is never
  assumed to be Wi-Fi.

### 1.2 mTLS is explicitly opt-in

Columns (all on `providers`, migration defaults land in Session 4):
`wifiOnly` (default false), `mtlsEnabled` (default **false**), `mtlsKeystoreBlob`
(sealed PKCS#12 bytes), `mtlsAlias` (non-secret), `trustedCertsBlob` (nullable,
sealed JSON array of base64 DER X.509 certs).

- `CredentialCodec` gains `sealBytes`/`unsealBytes` (same AES-GCM framing and
  Android-Keystore key as `seal`/`unseal`; implementation refactored into shared
  `sealRaw`/`unsealRaw`).
- The keystore password rides the existing sealed `secretBlob` under key
  `mtlsKeystorePassword` (`ProviderConfigMapper.SECRET_MTLS_KEYSTORE_PASSWORD`) —
  secrets stay in one envelope. Never logged anywhere; `ClientCertSpec.toString`
  is not generated (plain class, explicit content-based `equals`/`hashCode`),
  so a data-class `toString` cannot leak the byte array either.
- `provider-api/ProviderStatus.kt` also defines `ClientCertSpec(keystorePkcs12,
  keystorePassword, alias)`.
- `ProviderConnection` gains `clientCertificate: ClientCertSpec? = null` and
  `trustedServerCertificates: List<X509Certificate> = emptyList()`.
- Mapper rules (all tested):
  - `mtlsEnabled = false` → every stored blob/alias ignored; `clientCertificate`
    is null. **Stored keystore/alias data can never enable mTLS by itself.**
  - `mtlsEnabled = true` → keystore blob must unseal to non-empty bytes, else the
    row is unloadable (fail closed; the app never silently connects without the
    client identity — "no insecure fallback").
  - `trustedCertsBlob` decodes **independently of `mtlsEnabled`** (a custom trust
    anchor is server-side configuration, not client identity); present-but-
    unparseable blob → row unloadable rather than silent downgrade of server
    verification; absent → empty list = ordinary platform verification.
  - blank alias → null alias; unknown secrets → null password (password optional).
- Propagation into `ConnectionSpec` for **both** profiles:
  - `ProviderClientRegistry` now builds two specs per provider (`MEDIA` via the
    existing `mediaClientFactory`, `API` via a new `apiClientFactory`; both take
    `(ConnectionSpec) -> OkHttpClient`). Public surface: `httpClientFor` (MEDIA,
    unchanged) and `apiClientFor` (new). A corrupt (unparseable) PKCS#12 makes
    `specFor` return null and the registry drops the provider with a warning that
    names no secrets — again fail-closed.
  - `SubsonicProviderFactory.defaultClient` (the production API-path client built
    by `providerRegistry.create`) now mirrors the same mTLS/trust mapping. This
    duplication with the registry's `specFor` is deliberate and small (~8 lines;
    provider-api cannot depend on core-network to share one helper); flagged in
    §8 as a unification candidate.

### 1.3 Downloads

- `DownloadItemEntity`: added `providerId` (**required** in Kotlin — no default
  owner is ever invented; `DatabaseTestBase.seedDownloadItem(providerId)` updated),
  `tempCache` (default false), `exportTarget` (nullable name string, e.g.
  `MEDIASTORE_DOWNLOADS`), `autoRuleId` (nullable), plus a `(providerId)` index.
  The SQL NOT NULL constraint itself is part of the Session 4 rebuild.
- **`mediaKey` format inspected:** the key is `stream:<providerId>:<externalId>:
  <maxBitRateKbps|auto>:<format|auto>` — the same vocabulary as the playback
  cache key (`StreamKey.cacheKey`, playback-api/StreamResolver.kt:12), per the T2
  decision that downloads dedupe on the stream identity.
- **Backfill policy (documented for Session 4):** `DownloadKeys.providerIdFromMediaKey`
  (new) parses the numeric second segment of `stream:` keys (must be > 0). The v2
  migration backfills from this parse; rows without a reliable provider segment
  are **deleted** by the migration (documented in the migration SQL when written).
  No provider ids are guessed; no arbitrary owners assigned. Practical exposure is
  nil today: no production code writes `download_items` (only test seeds, now
  always passing a real provider id).
- `trg_provider_delete_download_items` appended to `ProviderCascadeTriggers`
  (16 → 17 statements).
- `DownloadEnqueuer` (new, `core-data/downloads/`): typed entry into the download
  pipeline. Order: traits first (`supportsDownload = false` →
  `DownloadEnqueueResult.SkippedDownloadUnsupported`, deterministic permanent
  rejection, nothing written), then the run gate (non-OK →
  `DownloadEnqueueResult.BlockedByStatus(status)`, nothing written), then
  insert-or-ignore of a `PENDING` row built on `DownloadKeys.mediaKey`.
  Re-enqueueing an existing key is idempotent and never resets progress.
  `DownloadEnqueueResult` gained the `BlockedByStatus` variant (provider-api).
- `DownloadStatus` enum (new): `PENDING/RUNNING/COMPLETED/FAILED`, persisted by
  name (T18 precedent). `DownloadDao.insertIgnoring` + `selectByProvider` added.

### 1.4 Gates consult the tracker (single seam)

- **Outbox drain:** `OutboxDrainer` gained `runGate` (default permissive).
  `drain(providerId)` returns `DrainReport(..., blockedBy: ProviderStatus?)` when
  blocked — tasks untouched, no backoff, stay due. A global `drain()` checks the
  gate per task and skips blocked providers politely (no counters bumped).
- **Sync:** `SubsonicLibrarySyncer` gained `traits: ProviderTraits` and
  `runGate`. At the top of `runSync`: `hasLibrarySync = false` →
  `Outcome(..., skippedNoLibrarySync = true)`; non-OK gate →
  `Outcome(..., blockedBy = status)`. In both cases no reads, no writes, no
  `sync_status` row. `Outcome` gained `blockedBy` and `skippedNoLibrarySync`.
- **Outbox enqueue:** `OutboxQueue` gained a `ProviderTraitsSource` (new seam in
  core-data/providers; default = Subsonic-family defaults). Both enqueue methods
  now return `OutboxEnqueueResult`; when the persisted row's api resolves to
  `reportsUserData = false`, nothing is written and the caller gets
  `SkippedNoUserDataReporting`. A row that already vanished resolves to the same
  defaults the registry applies to unknown apis (documented judgement call).
  **Note:** the Hilt binding for `ProviderTraitsSource`/`ProviderRegistry` (full
  factory list) lands with the first production consumer in :app — nothing injects
  `OutboxQueue` today, so the graph still compiles.
- **Download:** via `DownloadEnqueuer` (§1.3).
- **AUTH_FAILED marker:** `ProviderStatusTracker.markCredentialsUnreadable/
  clearCredentialsUnreadable` is the reserved T21 seam (the mapper's null-unseal
  path will call it); the state is derivable and tested now.

### 1.5 `SongRepository.ensureSong` (T16 §C)

- `SongDao.fillSnapshotFields` (new): `COALESCE`-based fill-NULL-only update of
  `albumId`, `title`, `displayArtist`, `imagePath`, `durationMs`. Locally-owned
  columns (playCount, favorites, resume, ratings, replay gain, clips) are never
  in the SET list; fully-synced metadata is never downgraded by a snapshot.
- `SongRepository.ensureSong(providerId, externalId, SongSnapshot)` (new,
  `core-data/repository/`): single transaction — insert-or-ignore a minimal row
  (`updatedAt = 0` marks "never synced"), best-effort album-link resolution by
  `(providerId, albumExternalId)`, fill-NULL update, id lookup. Idempotent.
- Session 1 §3.7 semantics kept; **T17 obligation recorded**: the prompt says T17
  must "ALSO cover `ensureSong` and use `@Upsert`" — the insert inside `ensureSong`
  is on T17's list (current form already avoids REPLACE).

## 2. Files changed (this session)

Production:
- provider-api: `ProviderStatus.kt` (new: enum, gate, `ClientCertSpec`),
  `ProviderConnection.kt` (+clientCertificate, +trustedServerCertificates),
  `ProviderTraits.kt` (+`DownloadEnqueueResult.BlockedByStatus`), `Outbox.kt`
  (runGate + `DrainReport.blockedBy`)
- provider-navidrome: `SubsonicProviderFactory.kt` (mTLS/trust into default client)
- core-data: `db/entity/ProviderEntity.kt`, `db/entity/DownloadItemEntity.kt`,
  `db/entity/DownloadStatus.kt` (new), `db/DownloadKeys.kt` (new),
  `db/ProviderCascadeTriggers.kt` (+download trigger), `db/dao/DownloadDao.kt`,
  `db/dao/SongDao.kt`, `db/queue/OutboxQueue.kt`, `db/DatabaseModule.kt`
  (NetworkMonitor binding), `providers/ProviderConfigMapper.kt`,
  `providers/ProviderClientRegistry.kt`, `providers/ProviderTraitsSource.kt` (new),
  `security/CredentialCodec.kt`, `sync/SubsonicLibrarySyncer.kt`,
  `network/NetworkMonitor.kt` (new), `network/ProviderStatusTracker.kt` (new),
  `downloads/DownloadEnqueuer.kt` (new), `repository/SongRepository.kt` (new)

Tests:
- New: `network/ProviderStatusTrackerTest.kt` (8 tests, fake `NetworkMonitor`),
  `network/ConnectivityManagerNetworkMonitorTest.kt` (6 tests, Robolectric shadow +
  fake capabilities objects), `providers/MtlsEndToEndHandshakeTest.kt` (2),
  `downloads/DownloadEnqueuerTest.kt` (7), `repository/SongRepositoryTest.kt` (6)
- Extended: `ProviderConfigMapperTest` (+7 mTLS/trust tests, row helper widened),
  `ProviderClientRegistryTest` (+3: both-profile propagation disabled/enabled,
  corrupt-keystore fail-closed), `OutboxDrainerTest` (+3 gate tests),
  `SubsonicLibrarySyncerTest` (+3 gate/trait tests), `OutboxQueueTest` (+2 trait
  gate tests), `ProviderCascadeTriggerTest` (download_items now seeded + asserted)
- Mechanical: `DatabaseTestBase.seedDownloadItem(providerId)`; `OutboxQueueTest`
  call sites name the `moshi` argument (constructor gained a parameter before it)

## 3. Test results

Commands (from the project root):

```
./gradlew :core-data:testDebugUnitTest :provider-api:test        # BUILD SUCCESSFUL
./gradlew :app:assembleDebug test                                # BUILD SUCCESSFUL in 1m 22s
```

Aggregate across all modules (`test` task, JUnit XML tallies): **392 tests,
0 failures, 0 errors** (skips: the `@Ignore`d v1→v1 migration harness placeholder —
Session 4 replaces it with the real 1→2 test — and the env-gated
`NavidromeLiveAcceptanceTest`). Highlights:

- Wi-Fi gating: tracker OK/NEEDS_WIFI/OFFLINE/AUTH_FAILED matrix (fake monitor),
  shadow-based monitor mapping with fake capabilities (Wi-Fi / cellular / VPN /
  no-INTERNET / missing caps / no active network), sync blocked before any read or
  write, drain blocked with `blockedBy` and tasks left due.
- mTLS: disabled ignores stored blobs; enabled builds the spec from unsealed
  bytes + sealed password + alias; missing/unsealable blob and corrupt keystore →
  provider unloadable (fail closed); both profiles receive the identity (spec
  capture); **real handshakes for BOTH profiles** through
  persistence → mapper → registry → default client factories against a
  client-cert-demanding MockWebServer; the disabled path fails that handshake
  (custom trust anchor retained, client identity absent — the failure is the
  missing client certificate).
- Downloads: typed `SkippedDownloadUnsupported` (also when offline),
  `BlockedByStatus`, mediaKey format round-trip + never-invented-owner parsing,
  idempotent re-enqueue, pending row shape.
- Cascade: deleting a provider removes its `download_items` (trigger active on
  in-memory DBs via the open callback; migration-time presence is Session 4 §D).
- `ensureSong`: materialise without a synced row, same id twice, playback state
  never reset, fill-NULL-only on existing rows, album link when the row exists,
  provider delete cascades the materialised row.

## 4. Migration dependencies (for Session 4)

The following entity/column changes exist in code but **not** in any migration;
`MIGRATION_1_2` must cover all of them (one explicit 1→2 step):

1. `providers` rebuild: primary block columns already nullable (Session 2) +
   this session's `wifiOnly`, `mtlsEnabled` (both `DEFAULT 0`), `mtlsKeystoreBlob`,
   `mtlsAlias`, `trustedCertsBlob`. Existing rows keep `mtlsEnabled = false`.
2. `download_items` rebuild with `providerId INTEGER NOT NULL` (backfill via
   `DownloadKeys.providerIdFromMediaKey` parse; unattributable rows deleted —
   documented), `tempCache INTEGER NOT NULL DEFAULT 0`, `exportTarget TEXT NULL`,
   `autoRuleId INTEGER NULL`, plus the `(providerId)` index and preservation of
   the `mediaKey` UNIQUE and `status` indices.
3. New `queue_entries` + `queue_state` tables (Session 1 §3.6) and the T22-(b)
   `play_events` DDL + per-day rollup view — not started this session.
4. Trigger battery: the migration must run `ProviderCascadeTriggers.install(db)`
   (now 17 statements). Note for Session 4: dropping `playback_events` (T22-b)
   auto-drops its trigger; the `playback_events` entry in `CREATE_STATEMENTS`
   must be replaced with `play_events` as part of the same migration work, since
   fresh v2 databases have no `playback_events` table.
5. `MigrationHarnessTest`'s `@Ignore`d placeholder is replaced by the real test.

## 5. Remaining work

- **Session 4 (migration):** `Migrations.MIGRATION_1_2`, schema `2.json`, the full
  §D test battery (real v1→v2 test with seeded provider/songs/outbox/download/
  playback-event rows, trigger count, `recursive_triggers` still off,
  DaoSqlCompatibilityTest, provider-delete-cascades-download-rows after migration,
  wifiOnly blocking, mTLS end-to-end re-run at schema v2).
- **Session 5 (finalize):** docs stubs (`docs/omnisonic/storage/`: schema v2,
  provider kinds, song materialization; plus a provider-security stub for mTLS/
  tracker), banned-name scan over `git ls-files`, full `./gradlew clean test
  :app:assembleDebug`, the single T16 commit.
- **Assigned follow-ups (not lost):** T17 (`@Upsert` pass incl. `ensureSong`),
  T21 (`CredentialsUnreadable` typed mapper result + tracker wiring +
  `BackupExcludes` incl. mTLS blobs), Hilt `ProviderTraitsSource`/`ProviderRegistry`
  bindings in :app with the first production consumer, potential unification of
  the registry/`SubsonicProviderFactory` spec mapping (§8).

## 6. Known temporary states / honesty notes

1. **Schema drift discipline (Session 2 §7.2, again):** every `:core-data`
   compile rewrites `schemas/.../1.json` to the new entity shape. It was restored
   from git after testing this session (`git status` on `core-data/schemas/` is
   clean; the file contains none of `kind`/`wifiOnly`/`mtlsEnabled`). Session 4
   must restore it at session start and never treat a drifted file as v1.
2. `MigrationHarnessTest.createdV1SchemaThenRealRoomOpenValidatesAndServesData`
   remains `@Ignore`d (unchanged from Session 2) — replaced by the real 1→2 test
   in Session 4. `fileBuilderInstallsCascadeTriggersOnOpen` stays green with the
   new 17-statement trigger list.
3. A VPN-only network is conservatively not-Wi-Fi (§1.1); if the user runs a
   Wi-Fi-only provider behind a VPN, sync/drain/download enqueue will report
   `NEEDS_WIFI`. Revisit only if real-world usage complains — data safety wins.
4. `trustedCertsBlob` is decoded independently of `mtlsEnabled` (§1.2): reading
   is that trust anchors are server-verification configuration, not client
   identity; the opt-in contract governs the client certificate. Recorded here
   so the reviewer can veto.
5. Outbox enqueue for an already-deleted provider row resolves traits to the
   Subsonic-family defaults (proceeds); degenerate case, documented in §1.4.
6. `./gradlew lint` and `clean` builds were not run this session (focused runs
   per the session plan); the full gate belongs to Session 5.

## 7. Clean-room

`scripts/check-banned-names.sh` over every changed and new `.kt` file (and a
positive control to prove the scanner works): clean. No studied-app identifiers,
comments, or paths were introduced.

## 8. Design notes for review

- `ProviderRunGate` lives in provider-api (next to `ProviderStatus`) so the
  drainer stays core-data-free; the tracker lives in core-data (Android
  `ConnectivityManager`). Single check seam, no duplicated network logic.
- `DrainReport.blockedBy` extends the report instead of changing its type —
  existing consumers/tests unaffected.
- The mTLS spec mapping exists twice (registry + `SubsonicProviderFactory`,
  §1.2). Sharing would require core-network → provider-api dependency (currently
  neither direction exists); flagged as a follow-up rather than settled silently.
- `DownloadEnqueuer` consults traits before the gate so the permanent capability
  rejection is deterministic regardless of transient network state.
