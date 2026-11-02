# SESSION T16-2 — PROVIDER KINDS, ENDPOINTS, TRAITS, LOCAL PERSISTENCE

Date: 2026-11-01
Scope: Session 2 of the five-session T16 plan. Inputs read in full: `Prompt.md`
(incl. revised T16 and the T17 note), `SESSION-T16-1-AUDIT.md`, and the actual tree
at session start. Git state at start: `main`, 13 commits ahead of origin (unpushed),
working tree clean except the untracked Session 1 report
(`docs/omnisonic/session-reports/SESSION-T16-1-AUDIT.md`) — preserved untouched.

**Nothing committed.** Per instructions the working tree is left intentionally
incomplete (one unified migration comes in Session 4; the single T16 commit comes
in Session 5). Known temporary states are listed in §7 — none of them is hidden.

## 1. What was built

### 1.1 Provider kinds (entity + enum)

- `core-data/.../db/entity/ProviderKind.kt` (new): `REMOTE_SERVER`, `DEVICE`,
  `VIRTUAL`; stored by name (T18 enum-by-name precedent); unknown/null names fall
  back to `REMOTE_SERVER`, matching the column default.
- `ProviderEntity`: new column `kind` with
  `@ColumnInfo(defaultValue = "REMOTE_SERVER")` and Kotlin default
  `ProviderKind.REMOTE_SERVER.name`, so pre-existing rows load unchanged.

### 1.2 Nullable primary columns

`ProviderEntity.primaryScheme/primaryHost/primaryPort/primaryPath` are now
nullable (`String?`/`Int?`). No remote validation moved or weakened: the mapper's
`block()` keeps exactly the previous checks (blank host → null, port outside
1..65535 → null, scheme not http/https → null) with nullable-tolerant inputs.

### 1.3 Sealed `ProviderEndpoint` (chosen over nullable `primary`)

`provider-api/.../ProviderConnection.kt`:

```kotlin
sealed interface ProviderEndpoint {
    data class Remote(val primary: ConnectionBlock, val secondary: ConnectionBlock? = null) : ProviderEndpoint
    data object None : ProviderEndpoint
}
```

`ProviderConnection` replaces `primary`/`secondary` with
`endpoint: ProviderEndpoint = ProviderEndpoint.None`, plus extensions
`remoteEndpoint` (nullable view) and `requireRemote()` (fail-fast named seam).

Why sealed is safer than an unstructured nullable primary (prompt asks for the
explanation):

1. **One fail-fast seam instead of scattered guards.** With `primary:
   ConnectionBlock?`, every server-only consumer (`SubsonicClient`,
   `BlockFailover`) would each need its own null handling, and forgetting one
   surfaces as an NPE at URL-build time. With the sealed type, server clients
   call `requireRemote()` and get an immediate `IllegalStateException("provider
   <id> has no remote endpoint")` — a named, greppable, single seam.
2. **Inside `Remote`, blocks stay non-null.** `BlockFailover`'s hysteresis math
   and `SubsonicClient.preferredBlock()` keep their exact logic (`secondary ?:
   primary`); the change is mechanical (resolve `val remote = endpoint as?
   Remote` once), so behaviour and tests are preserved rather than rewritten.
3. **Serverless rows are representable without lies.** A DEVICE/VIRTUAL row
   needs no fake `ConnectionBlock("local", "localhost", 0)` placeholder that
   would look like a valid server to any consumer that does not re-check.
4. **No new failure mode for the mapper:** DEVICE/VIRTUAL map to
   `ProviderEndpoint.None` (never `null`); `REMOTE_SERVER` null only when the
   strict validation fails, exactly as before.

No repository blocker for the sealed form was found: the complete production
surface reading `primary`/`secondary` was `BlockFailover` (3 sites) and
`SubsonicClient.preferredBlock()` (2 sites), both server-only by contract;
`LocalMediaProvider` reads nothing from the connection.

### 1.4 Kind-aware mapper

`ProviderConfigMapper.endpointFor(row)`: `REMOTE_SERVER` → validate primary
exactly as today (and secondary when `useSecondary`) into
`ProviderEndpoint.Remote`; `DEVICE`/`VIRTUAL` → `ProviderEndpoint.None`
regardless of any leftover primary columns (kind wins — proven by test). A
`REMOTE_SERVER` row with a blank host stays unloadable (mapper returns null;
`ProviderClientRegistry` logs and returns null).

### 1.5 `ProviderTraits` and capability-check APIs (provider-api)

`provider-api/.../ProviderTraits.kt` (new):

```kotlin
data class ProviderTraits(
    val hasLibrarySync: Boolean = true,          // defaults = Subsonic family
    val supportsDownload: Boolean = true,
    val supportsServerTranscode: Boolean = true,
    val reportsUserData: Boolean = true,
)

sealed interface OutboxEnqueueResult {
    data object Enqueued : OutboxEnqueueResult
    data object SkippedNoUserDataReporting : OutboxEnqueueResult  // nothing written
}

sealed interface DownloadEnqueueResult {
    data object Enqueued : DownloadEnqueueResult
    data object SkippedDownloadUnsupported : DownloadEnqueueResult  // nothing enqueued
}
```

Resolution APIs (runtime-only, never persisted; no new provider is built):

- `MediaProviderFactory.traits(): ProviderTraits` — interface default = Subsonic
  family defaults; narrower providers override.
- `ProviderRegistry.traitsFor(apiType)` — matched factory's traits; unknown api
  → Subsonic-family defaults.
- `LocalMediaProviderFactory.traits()` override — values derived from the actual
  implementation (table in §2).

### 1.6 Server clients on the sealed endpoint (minimal)

- `BlockFailover`: resolves `remote = endpoint as? Remote` once; a None endpoint
  returns `BlockAttempt.FatalError(IllegalStateException(...))` before any block
  is contacted (covered by a new test). The three `connection.primary/secondary`
  reads became `remote.primary/secondary`; all hysteresis/probe logic unchanged.
- `SubsonicClient.preferredBlock()`: `connection.requireRemote()` then the same
  `secondary ?: primary` preference logic. Unchanged behaviour otherwise.
- `ProviderClientRegistry`: no code change needed — DEVICE/VIRTUAL rows now
  produce a connection (endpoint None) and flow into
  `providerRegistry.create(api, connection)`; genuinely unloadable REMOTE_SERVER
  rows still hit the null path with the existing warning.

## 2. Trait table with evidence

| Trait | Subsonic (`SubsonicProviderFactory`) | Local (`LocalMediaProviderFactory`) | Local evidence |
|---|---|---|---|
| `hasLibrarySync` | true (interface default) | **true** | `LocalMediaProvider` implements the full library surface: listArtists/browseDirectory/getAlbum/listAlbums/listGenres + searches (LocalMediaProvider.kt:39-183) |
| `supportsDownload` | true (interface default) | **false** | `downloadUrl(...)` returns null unconditionally (LocalMediaProvider.kt:190); files are already on the device, there is no fetch path |
| `supportsServerTranscode` | true (interface default) | **false** | No server exists; streamUrl is a MediaStore content URI (LocalMediaProvider.kt:185-186), nothing to negotiate a transcode with |
| `reportsUserData` | true (interface default) | **false** | KDoc + implementation: user-data methods are local no-ops that "report as fully accepted so the outbox drains them without retries" — nothing goes to any remote (LocalMediaProvider.kt:30-34, 192-202) |

Trait consumers (the four clean capability-check seams): sync → `hasLibrarySync`;
outbox enqueue → `reportsUserData` (variant chosen, matching Session 1 §3.3:
**write no outbox rows** and return `OutboxEnqueueResult.SkippedNoUserDataReporting`);
download enqueue → `supportsDownload` (typed `DownloadEnqueueResult`);
transcode planning → `supportsServerTranscode`.

## 3. Local provider persistence (end-to-end proof)

New `core-data/src/test/.../providers/LocalProviderPersistenceTest.kt` (Robolectric,
real Room via `OmnisonicDatabaseFactory.openInMemory()`): a persisted
`ProviderEntity` row (api `local`) is inserted through `ProviderDao`, then loaded
through `ProviderConfigMapper` and `ProviderClientRegistry` with a registry
containing `LocalMediaProviderFactory` (+ `SubsonicProviderFactory` so a null
result can only come from the mapper, never from a missing factory):

- **DEVICE row maps and loads end-to-end**: `providerFor(7)` returns a real
  `LocalMediaProvider`; `streamUrl("501")` equals the MediaStore content URI for
  id 501; `listRadios()` returns empty. No `ConnectionBlock` is constructed
  anywhere in the test.
- **VIRTUAL row maps and loads end-to-end** (same chain; `coverArtUrl` null).
- **`REMOTE_SERVER` with blank host stays unloadable**: `mapper.from(row) ==
  null` and `registry.providerFor(7) == null`.
- Trait profile of the loaded api resolved via `ProviderRegistry.traitsFor`
  (row `api="local"` → §2 Local column).
- `core-data/build.gradle.kts`: `testImplementation(project(":provider-local"))`
  added (test-only dependency; no cycle — provider-local depends only on
  provider-api/core-model).

The fake `ConnectionBlock("local", "localhost", 0)` construction in
`LocalMediaProviderTest` is gone; the factory test now uses
`ProviderConnection(providerId = 1)` (endpoint None).

## 4. Files changed

Production:
- `provider-api`: `ProviderConnection.kt` (ProviderEndpoint + extensions +
  endpoint field), `ProviderTraits.kt` (new), `MediaProvider.kt`
  (factory `traits()` + registry `traitsFor`), `BlockFailover.kt`
- `provider-navidrome`: `SubsonicClient.kt` (preferredBlock via requireRemote)
- `provider-local`: `LocalMediaProvider.kt` (factory traits override)
- `core-data`: `db/entity/ProviderKind.kt` (new), `db/entity/ProviderEntity.kt`,
  `providers/ProviderConfigMapper.kt`, `build.gradle.kts` (test dep only)

Tests:
- Updated (mechanical construction/assert changes only):
  `BlockFailoverTest`, `ProviderRegistryTest`, `SubsonicClientTest`,
  `OutboxDrainEndToEndTest`, `NavidromeLiveAcceptanceTest`,
  `ProviderConfigMapperTest`, `LocalMediaProviderTest`
- New: `LocalProviderPersistenceTest` (core-data), BlockFailoverTest
  `serverless endpoint is a fatal error...`, ProviderRegistryTest traits tests
  (2), LocalMediaProviderTest traits test, ProviderConfigMapperTest DEVICE/
  VIRTUAL/kind-wins tests (3)
- `MigrationHarnessTest`: see §7.1.

## 5. Test results

`./gradlew :app:assembleDebug` — BUILD SUCCESSFUL (all main sources compile).

Focused runs, all green (JUnit XML tallies):

| Module | Tests | Failed | Skipped |
|---|---|---|---|
| provider-api | 47 | 0 | 0 |
| provider-navidrome | 26 | 0 | 4 (`NavidromeLiveAcceptanceTest`, env-gated as designed) |
| provider-local | 12 | 0 | 0 |
| core-data (full suite) | 89 | 0 | 1 (the @Ignore'd migration harness test, §7.1) |
| playback | 128 | 0 | 0 |
| playback-api | 10 | 0 | 0 |
| app | 4 | 0 | 0 |

Subsonic/BlockFailover behaviour preserved: the only SubsonicClientTest change
is the shared `testConnection` helper body; every failover/scrobble/probe test
passes unchanged in content. Clean-room scan
(`scripts/check-banned-names.sh` over all changed `.kt` files): clean.

## 6. Remaining work (assigned, not lost)

### 6.1 Enforcement — Session 3 (per prompt: "If enforcement is assigned to Session 3, record the exact remaining work")

1. **Outbox gate:** `OutboxQueue.enqueueMediaChange` / `enqueuePlayCountDelta`
   must resolve the row's api (they already hold the database), ask
   `ProviderRegistry.traitsFor(api)` (registry must be injected; there is no
   Hilt provider for `ProviderRegistry` yet — wire the factory list in
   `DatabaseModule` or a providers module), and when `reportsUserData == false`
   write nothing and return `OutboxEnqueueResult.SkippedNoUserDataReporting`
   (currently both methods return Unit — signatures change). OutboxQueue has no
   production callers today, so the gate is test-provable at the seam
   (T16 §B proof: "outbox enqueue for a reportsUserData=false provider writes no
   outbox rows").
2. **Sync gate:** before `SubsonicLibrarySyncer.runFullSync/runDiffSync` (or at
   its top), return a typed skip when `hasLibrarySync == false` (no sync_status
   row written). The syncer takes a `MediaProvider` today, not an api string —
   the api must come from the caller/row; decide the exact seam in Session 3.
3. **Download gate:** no download-enqueue path exists (feature-downloads is a
   stub); the typed result `DownloadEnqueueResult` is defined and
   registry-resolvable; the actual rejection belongs to Phase 7's enqueue path
   (recorded in Session 1 §2 and unchanged).
4. **Transcode planning:** no planner exists (Phase 8); `supportsServerTranscode`
   is available at the seam; nothing to enforce yet.
5. `ProviderStatusTracker` (wifiOnly/mTLS/AUTH_FAILED etc.) and the remaining
   §A/§C schema items are Sessions 3–4 work per the plan; untouched here.

### 6.2 Migration — Session 4

This session changed `ProviderEntity` (kind + nullable primaries) with
SCHEMA_VERSION deliberately still 1 and **no** `Migrations.ALL` entry, **no**
schema `2.json`, **no** destructive fallback — per the one-unified-migration
rule. The v1→v2 rebuild of `providers` (with `kind`/`wifiOnly`/mTLS columns),
`download_items`, queue snapshot tables, T22 (b) `play_events` DDL, and the
full §D test battery all land in Session 4 as `Migrations.MIGRATION_1_2`.

## 7. Known temporary failures / honest states

1. **`MigrationHarnessTest.createdV1SchemaThenRealRoomOpenValidatesAndServesData`
   is `@Ignore`d.** With the entity changed but the version still 1, this test
   cannot tell the truth in either direction: building `:core-data` makes Room's
   KSP regenerate `schemas/.../1.json` with the NEW shape (it exports the current
   schema under the current version), so `createDatabase(name, 1)` would create
   a fake "v1" whose identity hash matches the new entities and the test would
   pass vacuously; with the true committed 1.json restored, the real Room open
   fails the identity check. Rather than a fake green or a cryptic permanent red
   for three sessions, the test is ignored with a reason string that names this
   report and the Session 4 obligation (Session 4 replaces it with the real
   1→2 migration test per T16 §D). `fileBuilderInstallsCascadeTriggersOnOpen`
   (fresh DB) stays active and green.
2. **Schema-drift discipline:** every `:core-data` compile rewrites
   `core-data/schemas/app.omnisonic.core.data.db.OmnisonicDatabase/1.json` to the
   new shape. The committed file is the only true v1 baseline and is required to
   create v1 for the Session 4 migration test. I restored it after testing
   (`git status` on `core-data/schemas/` is clean; verified the file has no
   `kind` column). **Sessions 3–5 must restore this file at session start if
   git shows it modified, and Session 4 must not treat a drifted 1.json as v1.**
3. No `1→2` migration, no `2.json`, no `fallbackToDestructiveMigration`,
   SCHEMA_VERSION untouched at 1 — all as instructed.
4. `docs/omnisonic/` doc stubs (provider kinds, endpoint model, traits) are not
   written this session; per the five-session plan they are a Session 5
   deliverable together with the schema-v2 and song-materialization stubs.
5. `./gradlew lint` and a full `./gradlew test` were not run this session
   (focused testing only, per instructions); the full gate belongs to Session 5.

## 8. Nothing committed

As instructed: no commit in this session; the single T16 commit happens in
Session 5. The tree now intentionally contains the incomplete state described
above; the Session 1 audit report remains untracked alongside this report.
