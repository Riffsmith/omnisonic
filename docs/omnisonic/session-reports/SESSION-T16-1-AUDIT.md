# SESSION T16-1 — AUDIT, DEPENDENCY MAP, AND DESIGN

Date: 2026-11-01
Scope: Session 1 of the five-session T16 plan. Investigation and design only. No
production code, schema files, or migrations were touched. No commit. Working tree was
clean at session start (`git status`: nothing to commit; branch `main`, 13 commits
ahead of `origin/main`, not pushed) — no user changes needed preserving.

Decision received during this session: **T22 = (b) — per-play table + per-day rollup
view** (recorded verbatim in §4.3 at the user's request).

Inputs read in full: `Prompt.md` (incl. the revised T16 spec, lines 355–453 and the
T17 note at line 454), the prior session reports
(`SESSION-2-DECISIONS.md`, `SESSION-3.md`, `SESSION-4-DECISION.md`, `SESSION-5.md`),
`.agents/memory/open-questions.md`, and the source/tests cited below.

## 0. Standing instructions acknowledged

- Same branch/worktree; no push without permission.
- Exactly ONE explicit v1→v2 migration in the final result; no partial migrations.
- No T16 commits before Session 5; intermediate sessions may leave the working tree
  temporarily incomplete and must say so.
- T17 remains a separate task and must ALSO cover `ensureSong` and use Room `@Upsert`
  for applicable REPLACE-style upserts (Room 2.6.1 is in the catalog —
  `gradle/libs.versions.toml:8` — so `@Upsert` is available).

## 0.1 Prior-decision verification: T13 is explicitly recorded

- `docs/omnisonic/session-reports/SESSION-4-DECISION.md` records the full T13 decision
  brief (Option A: stable `ForwardingPlayer` exterior + stable inner
  `SimpleBasePlayer` bridge, full effective-order queue timeline, `prepareNext` +
  auto-advance handoff, §0.3 roadmap table edit deferred to T24).
- `SESSION-5.md:9-11` records the user's approval ("I agree with the recommended
  option made in the last session") and `SESSION-5.md:251-255` the applied outcome.
- Verified in code: `playback/src/main/java/app/omnisonic/playback/player/
  OmniForwardingPlayer.kt`, `QueueAwareBridge.kt`, `QueueCommandPolicy.kt` exist and
  `OmniPlayerImpl` holds a stable exposed identity. [VERIFIED]

## 1. Claim-by-claim verification of the revised T16 spec

Legend: [VERIFIED] = checked directly against this tree; [MISSING] = required thing
does not exist yet; [PRESENT] = already implemented.

### §A — changes carried over from the original T16

| Claim | Status | Evidence |
|---|---|---|
| `DownloadItemEntity` has no `providerId` | [VERIFIED] | `core-data/.../entity/DownloadItemEntity.kt` — columns: id, mediaKey, settingsJson, status, title, imagePath, progressPercent, errorCount, lastError, transcodeBitrateKbps, createdAt, updatedAt |
| `ProviderCascadeTriggers` has no `download_items` trigger | [VERIFIED] | `ProviderCascadeTriggers.kt:7-23` — 15 provider-delete triggers + 1 playlist trigger; `download_items` absent |
| `providers` has no mTLS / wifiOnly fields | [VERIFIED] | `ProviderEntity.kt` — none of `wifiOnly`, `mtlsKeystoreBlob`, `mtlsAlias`, `trustedCertsBlob` |
| `ProviderConnection` has no `clientCertificate` | [VERIFIED] | `provider-api/.../ProviderConnection.kt:23-38` |
| mTLS client-capable network layer already exists | [PRESENT] | `core-network/.../ConnectionSpec.kt:17-25` already carries `clientKeyStore`, `clientKeyStorePassword`, `trustedCertificates`; `TlsContexts` builds key/trust managers; real-handshake scaffolding exists (`core-network/.../TlsHandshakeTest.kt`, `playback/.../MediaDataSourceFactoryProviderTest.kt` mtls test). Only the persistence→mapper→client plumbing is missing |
| `ProviderClientRegistry` builds MEDIA clients without mTLS fields | [VERIFIED] | `ProviderClientRegistry.kt:78-86` — `ConnectionSpec(...)` passes profile/headers/timeouts only |
| T10 fields `resumeUpdatedAt`, `missingOnServer` | [PRESENT] | `SongEntity.kt:43-44`; also present in exported `core-data/schemas/.../1.json` (T10 redefined pre-release v1) |
| `ProviderStatusTracker` (OK/NEEDS_WIFI/AUTH_FAILED/OFFLINE) | [MISSING] | No `ConnectivityManager`/`NetworkCapabilities` usage anywhere in `app`/`core-data`/`core-network`/`playback` (grep) |
| Sync/drain/download consult the tracker | [MISSING] | Sync entry `SubsonicLibrarySyncer.runFullSync/runDiffSync` and `OutboxDrainer.drain` have no production callers yet; `feature-downloads` is a `ModuleMarker.kt` stub; no download-enqueue production path exists |
| Fold T22's answer into the migration if given | [PENDING] at audit time → **answered (b) at session close** (§4.3) | Not answered when the audit ran; the user answered before the session ended |

### §B — providers without a server connection

| Claim | Status | Evidence |
|---|---|---|
| `ProviderEntity` requires non-null `primaryScheme/Host/Port/Path` | [VERIFIED] | `ProviderEntity.kt:14-17`; `1.json` confirms `NOT NULL` on all four |
| `ProviderConfigMapper.block()` rejects non-http/https schemes and blank hosts | [VERIFIED] | `ProviderConfigMapper.kt:56-67` — blank host → null, port outside 1..65535 → null, scheme not http/https → null; `from()` returns null → row unloadable |
| Local provider test builds `ProviderConnection` by hand with `ConnectionBlock("local","localhost",0)` | [VERIFIED] | `provider-local/src/test/.../LocalMediaProviderTest.kt:312` |
| A real DB row for the local provider cannot load through the mapper | [VERIFIED by derivation] | scheme `"local"` fails the http/https check and port `0` fails the port check, so `block()` → null → `from()` → null. `LocalMediaProviderFactory` (`provider-local/.../LocalMediaProvider.kt:432`) never reads the connection block, but nothing can construct its row from persistence today |
| No `kind` column, no `ProviderEndpoint`, no `ProviderTraits` | [MISSING] | grep over the whole tree: zero matches |
| `LocalMediaProvider` reads no `connection.primary` | [VERIFIED] | grep of `LocalMediaProvider.kt` for `connection.`/`primary`: no matches — the sealed-endpoint change is nearly free for it |

### §C — queue and history rows independent of a synced `songs` row

| Claim | Status | Evidence |
|---|---|---|
| `PlaybackQueueEntry` carries the snapshot fields with nullable `songRowId` | [VERIFIED] | `playback-api/.../queue/QueueModels.kt:7-18` — providerId, externalId, songRowId?, title, artistName?, albumExternalId?, coverImageId?, durationMs?, isRadioStation |
| No queue persistence table exists | [VERIFIED] | `1.json` tables: providers, library_sources, artists, albums, songs, genres, album_artists, song_artists, playlists, playlists_entries, internet_radios, chapters, playback_events, outbox_items, download_items, offline_files, saved_filters, sync_status (18; none queue-related) |
| `SongDao.addPlayCount/setResumePoint/setFavorite` keyed by `songs.id` | [VERIFIED] | `SongDao.kt:114-137` |
| No `ensureSong` | [MISSING] | grep: zero matches; no repository layer exists in `core-data` |
| Provider delete cascades via triggers | [VERIFIED] | `ProviderCascadeTriggerTest` asserts every owned table is cleaned; triggers installed on create AND every open (`OmnisonicDatabaseFactory.kt:27-36`) |

### §D — migration quality bar

| Claim | Status | Evidence |
|---|---|---|
| `SCHEMA_VERSION = 1` today; `Migrations.ALL` empty | [VERIFIED] | `OmnisonicDatabase.kt:91`; `Migrations.kt:7` |
| Only `1.json` exported | [VERIFIED] | `core-data/schemas/.../` contains `1.json` only |
| No `fallbackToDestructiveMigration` anywhere | [VERIFIED] | `OmnisonicDatabaseFactory` adds only migrations + callbacks; `DatabaseModule` uses the factory |
| `MigrationHarnessTest` is currently a v1→v1 harness, not a real migration test | [VERIFIED] | It creates a DB at the current `SCHEMA_VERSION` and reopens it with `Migrations.ALL` (empty). `MigrationHarnessScaffoldTest` only proves Robolectric context |
| `MigrationTestHelper` infra works under Robolectric sdk 34 | [VERIFIED] | existing harness + `@Config(sdk = [34])` pattern repo-wide |
| `DaoSqlCompatibilityTest` scans `ON CONFLICT`/`RETURNING`/`OVER(` | [VERIFIED] | `DaoSqlCompatibilityTest.kt:37-41`; auto-covers any new DAO SQL |
| Trigger-count proof pattern exists | [VERIFIED] | `MigrationHarnessTest.fileBuilderInstallsCascadeTriggersOnOpen` compares `sqlite_master` triggers to `ProviderCascadeTriggers.CREATE_STATEMENTS.size` |
| `DatabaseTestBase` seeds with named constructor args | [VERIFIED] | `DatabaseTestBase.kt:87-99` — adding defaulted columns is source-compatible |

## 2. Dependency map (what T16 touches)

Modules: **core-data** (primary), **provider-api**, **provider-navidrome**,
**core-network** (likely zero — already mTLS-ready), **provider-local** (tests only),
**app** (DI registration only). `playback`/`playback-api` are NOT touched: the queue
entry model already matches the snapshot spec.

Production classes and call sites:

- `ProviderEntity` (+kind, wifiOnly, mtls columns; primary block columns become
  nullable → table rebuild in migration) — consumers: `ProviderDao`,
  `ProviderConfigMapper`, `DatabaseTestBase.newProvider` (named args, safe),
  `MigrationHarnessTest` (raw SQL insert, must be updated).
- `ProviderConnection` (primary/secondary → sealed endpoint; +clientCertificate) —
  direct `connection.primary`/`connection.secondary` readers:
  `SubsonicClient.kt:405-410` (`preferredBlock`), `BlockFailover.kt:38,52` + `:39,53`
  (`connection.secondary`). That is the complete production surface (grep-verified).
- `ProviderConfigMapper` — kind-aware validation, endpoint construction, mTLS unseal.
  Also the future T21 seam (`CredentialsUnreadable` typed result — out of T16 scope,
  but the tracker design reserves the AUTH_FAILED state).
- `ProviderClientRegistry` — pass mTLS spec into BOTH profiles; wording of the
  "no usable connection block" warning changes for endpoint-None rows.
- `OutboxQueue.enqueueMediaChange/enqueuePlayCountDelta` — trait gate
  (`reportsUserData`); zero production callers today, so the gate is test-only for now.
- Download enqueue path — does not exist; the `supportsDownload` typed rejection is
  delivered as the trait + a typed result type + unit test, to be consumed by
  `feature-downloads` in Phase 7.
- `SubsonicLibrarySyncer` — `hasLibrarySync` gate at `runSync`; zero production
  callers today.
- New: `ProviderStatusTracker`, `SongRepository.ensureSong`, queue snapshot entities +
  DAO, `Migrations.MIGRATION_1_2`, `play_events` DDL (T22 answered (b) — §4.3).
- Tests affected: `MigrationHarnessTest` (rewrite as real migration test),
  `ProviderConfigMapperTest`, `ProviderClientRegistryTest`, `OutboxQueueTest`,
  `ProviderCascadeTriggerTest`, `LocalMediaProviderTest` (end-to-end persisted row),
  new tracker/traits/repository tests.

## 3. Proposed minimal design

### 3.1 Provider kinds (`kind` column)

- `providers.kind TEXT NOT NULL DEFAULT 'REMOTE_SERVER'` (name-stored string per the
  T18 enum-by-name precedent). Values: `REMOTE_SERVER`, `DEVICE`, `VIRTUAL`.
- Rule enforced in code (mapper), not SQL CHECK: `REMOTE_SERVER` requires a valid
  primary block; `DEVICE`/`VIRTUAL` do not. Old rows migrate to `REMOTE_SERVER`
  automatically via the column default.

### 3.2 Sealed `ProviderEndpoint` (provider-api) — chosen over nullable primary

```kotlin
sealed interface ProviderEndpoint {
    data class Remote(val primary: ConnectionBlock, val secondary: ConnectionBlock? = null) : ProviderEndpoint
    data object None : ProviderEndpoint
}
```

`ProviderConnection` replaces `primary`/`secondary` with `endpoint: ProviderEndpoint`
(`lastWorkingBlock: Int?` stays). Helpers keep server-only call sites one-liners:

```kotlin
fun ProviderConnection.remoteOrNull(): ConnectionBlock? =
    (endpoint as? ProviderEndpoint.Remote)?.primary
fun ProviderConnection.requireRemote(): ConnectionBlock  // IllegalStateException for None
```

Why sealed (per the spec's "pick one and explain"):
1. Server-only clients fail fast at a named seam (`requireRemote`) instead of
   scattering `requireNotNull(primary)` through `SubsonicClient`/`BlockFailover`.
2. `ConnectionBlock` stays non-null inside `Remote`, so `BlockFailover`/URL math and
   its tests change mechanically, not logically: `BlockFailover.attempt` resolves
   `val remote = endpoint as? Remote` once; a None endpoint is a programmer error
   (registry/mapper guarantee server kinds only reach server clients).
3. DEVICE/VIRTUAL rows are representable without fake `"local","localhost",0` rows.
4. Minimal churn: exactly `SubsonicClient.preferredBlock()` and `BlockFailover`
   read the blocks today (verified above); `LocalMediaProvider` reads nothing.

Mapper rules: `REMOTE_SERVER` → validate primary exactly as today (null only when
unloadable); `DEVICE`/`VIRTUAL` → `ProviderEndpoint.None` (never null).

### 3.3 `ProviderTraits` (provider-api, not persisted)

```kotlin
data class ProviderTraits(
    val hasLibrarySync: Boolean,
    val supportsDownload: Boolean,
    val supportsServerTranscode: Boolean,
    val reportsUserData: Boolean,
)
```

Sourced by api string (registry keys unchanged): `subsonic` → all true; `local` →
`hasLibrarySync=true`, others false; unknown api → Subsonic defaults. Delivered as
`ProviderTraits.forApi(api)` with the factory list passed in (no new provider built).

Gating call sites, each a typed polite skip (no exception):
- Sync: gate before `SubsonicLibrarySyncer.runSync` (skip result, no sync row written).
- Outbox enqueue: chosen variant = **write no outbox rows** and return a typed
  `OutboxEnqueueResult { Enqueued, Skipped(reportsUserData=false) }` (documented; the
  alternative "write-and-complete" burns an outbox row and a drain cycle for nothing).
  OutboxQueue resolves the row's api via the database it already holds.
- Download enqueue: typed `DownloadEnqueueResult { Enqueued, Unsupported }` — defined
  now (provider-api), consumed by Phase 7's enqueue path.
- Transcode planner: consulted when Phase 8 builds it; trait is available.

### 3.4 mTLS + Wi-Fi gating on `providers`

Columns: `wifiOnly INTEGER NOT NULL DEFAULT 0`; `mtlsKeystoreBlob TEXT NULL` (sealed
PKCS#12 bytes); `mtlsAlias TEXT NULL` (non-secret key alias); `trustedCertsBlob TEXT
NULL` (sealed list of base64 DER X.509 certs). The keystore password rides the
existing sealed `secretBlob` as an optional `SecretPayload` key
(`mtlsKeystorePassword`) — secrets stay in one sealed envelope.

`CredentialCodec` gains `sealBytes/unsealBytes` (same AES-GCM framing, version byte)
so binary blobs use the identical keystore key; JSON payloads keep `seal/unseal`.

`ClientCertSpec` (provider-api): `data class ClientCertSpec(keystorePkcs12: ByteArray,
keystorePassword: String?, alias: String?)`. Mapper fills
`ProviderConnection.clientCertificate`; `ProviderClientRegistry` maps it into
`ConnectionSpec(clientKeyStore, clientKeyStorePassword, trustedCertificates)` for
**both** API and MEDIA profiles (it currently builds only MEDIA; the API-profile
client construction joins the same path).

`ProviderStatusTracker` (core-data, `@Singleton`): `StateFlow<Map<Long,
ProviderStatus>>`, `ProviderStatus { OK, NEEDS_WIFI, AUTH_FAILED, OFFLINE }`.
Inputs: `ConnectivityManager`/`NetworkCapabilities` (Robolectric-shadowable),
provider rows (`wifiOnly`), and — reserving T21's seam — a credentials-unreadable
signal (today the mapper's null-unseal path; AUTH_FAILED wiring completes with T21).
Sync, drain, and download consult a small gate (`statusFor(providerId)`) before work;
proof is unit-tested with a fake network until Phase 4/6/7 consumers exist.

### 3.5 Download ownership (`download_items`)

- `providerId INTEGER NOT NULL` (rebuild table in the migration — SQLite cannot drop
  NOT NULL-less→NOT NULL via ALTER), backfill policy: parse a numeric provider prefix
  from `mediaKey` when present; rows that cannot be attributed are **deleted** and the
  deletion is documented in the migration. Nothing in production writes
  `download_items` yet (no callers of `DownloadDao` writes outside tests), so the
  practical data loss is nil; the policy must still be explicit.
- `tempCache INTEGER NOT NULL DEFAULT 0`, `exportTarget TEXT NULL` (enum name, e.g.
  `MEDIASTORE_DOWNLOADS`; null = app-private storage), `autoRuleId INTEGER NULL`
  (future auto-offline rules table; trigger-covered like other provider children).
- Trigger `trg_provider_delete_download_items` appended to
  `ProviderCascadeTriggers.CREATE_STATEMENTS` (count 16 → 17). The migration executes
  `ProviderCascadeTriggers.install(db)` at its end (idempotent, `IF NOT EXISTS`) so
  trigger presence does not depend on the open callback path
  (`MigrationHarnessTest`'s direct `Room.databaseBuilder` has no callback — verified).

### 3.6 Queue snapshot tables

- `queue_entries`: `id`, `queueItemId INTEGER NOT NULL UNIQUE`, `position INTEGER NOT
  NULL`, plus the §C snapshot: `providerId`, `externalId`, `songRowId INTEGER NULL`,
  `title`, `artistName`, `albumExternalId`, `coverImageId`, `durationMs`,
  `isRadioStation`. Index `(providerId)`. Restore needs no join to `songs`.
- `queue_state` (single row): `playIndex`, `repeatMode` TEXT, `shuffleSeed INTEGER
  NULL` (order recomputed from seed via `ShuffleOrders`), `speed`, `pitch`,
  `sourceType`, `sourceData`.
- Judgment call flagged for approval: §C mandates only the entries table; `queue_state`
  is the minimal extra that makes a restore actually reproduce the queue (order +
  current index + repeat/shuffle). Schema-only in T16 — the saver/loader is Phase 4
  work and must not be started here.
- Table names use our own vocabulary (`queue_entries`, `queue_state`) so T24's rename
  pass does not need to touch them.

### 3.7 `ensureSong`

New `SongRepository` in core-data:

```kotlin
suspend fun ensureSong(providerId: Long, externalId: String, snapshot: SongSnapshot): Long
// SongSnapshot(title, artistName?, albumExternalId?, coverImageId?, durationMs?)
```

Implementation (single `@Transaction`): `insertOrIgnore` a minimal row, then
`selectIdByProviderAndExternal`, then a fill-only-NULL update (`COALESCE`) for
`title`, `displayArtist`, `imagePath`, `durationMs` — columns the T10
SERVER/LOCAL classification owns; `playCount`, `isFavorite`, resume and replay-gain
columns are never in the SET list. Chosen semantics: **fill NULLs only** (a queue
snapshot must not downgrade fully-synced metadata); overwrite-on-existing is the
one-line alternative if you prefer it. Idempotent: second call returns the same id.
Provider delete cascades materialized rows through the existing triggers.

PlayCounter/favorites/playlist-add adopt it when those pipelines exist (Phase 4/5);
T16 delivers repository + tests. T17 will cover it in the REPLACE→`@Upsert` pass
(no `onConflict = REPLACE` anywhere in this work).

### 3.8 T10 fields

`SongEntity.resumeUpdatedAt` + `missingOnServer` already exist [PRESENT]. The
migration only preserves them (copy during any table rebuild; songs are not rebuilt).

### 3.9 Unified migration (one explicit step 1→2)

`SCHEMA_VERSION = 2`; `Migrations.ALL = [MIGRATION_1_2]`; exported `2.json` committed;
no destructive fallback. DDL outline:

1. Rebuild `providers`: create `providers_new` (all existing columns with
   primaryScheme/Host/Port/Path now NULL-able + `kind`, `wifiOnly`, `mtlsKeystoreBlob`,
   `mtlsAlias`, `trustedCertsBlob`), `INSERT INTO providers_new SELECT ...` (new
   columns take defaults), drop old, rename, recreate indices (providers currently
   has none — verified in `1.json`).
2. Rebuild `download_items` with `providerId NOT NULL` (backfill/parse-or-delete) +
   `tempCache`, `exportTarget`, `autoRuleId`; preserve `mediaKey` UNIQUE and `status`
   indices; add `(providerId)` index.
3. Create `queue_entries` + `queue_state` (+ indices).
4. T22 DDL — **answered (b)** (see §4.3): create `play_events` + indices + per-day
   rollup view, derived-copy rows from `playback_events`, then drop `playback_events`.
5. `ProviderCascadeTriggers.install(db)`.

### 3.10 T22 history shape — answered (b), recorded in §4.3

Whatever is answered must be folded into step 4 above BEFORE `MIGRATION_1_2` and
`2.json` are written (one-migration rule).

## 4. T22 evidence gathered (as instructed, before asking)

- `playback_events` is per-day: unique `(providerId, externalId, date)`
  (`PlaybackEventEntity.kt:10`); writes via `recordDayEvent` = `INSERT OR IGNORE` +
  bitwise `UPDATE` merge (the T5 API-29-safe shape, `PlaybackEventDao.kt:11-52`).
- `recordDayEvent` has **zero production callers** (tests only:
  `PlaybackEventDaoTest`, `DatabaseTestBase:228`). `playback_events` has **zero
  production readers** (`selectByProvider`/`selectByProviderAndExternal` uncalled).
  `OutboxQueue.enqueuePlayCountDelta` likewise has no production callers. The entire
  listen-history pipeline is unwired Phase 4/6 work.
- `PlaybackDay.dayStart(playedAtMs)` exists for day bucketing (`PlaybackDay.kt`).
- ROADMAP (reference) tension, verified by reading `reference/ROADMAP.md`:
  - §1.1 line 77: "Per-play logging | One row per track per day (option a —
    `playback_events` unique on provider+external_id+date; reference shape)" —
    recorded as confirmed.
  - Phase 6 line 226: "one row per track per day means two same-day plays merge...
    **Decide before Phase 6 starts** whether Omnisonic keeps one row per day or logs
    every play (needed if ListenBrainz submissions later want exact timestamps).
    Recommend every play, with a daily rollup view. **Needs your OK.**"
  - D-7 (line 95): delete-one-play / edit-play-count — Phase 6.
  - ListenBrainz (line 375): "scrobbles from the event bus with **real timestamps**".
- `.agents/memory/open-questions.md` still lists "per-play logging = one row per
  track per day" under *Resolved (no longer pending)* — the older Phase-2 record.

### 4.1 When the T22 decision is required (exact checkpoint)

**Before `MIGRATION_1_2` DDL and `schema 2.json` are written** — i.e., before the
migration-implementation session starts. Reasons:
- The one-migration rule: if the answer is (b) or (c), the per-play table (+ rollup
  view) must ride migration 1→2; a later migration is forbidden.
- Everything else in T16 (kinds, endpoint, traits, mTLS, wifi, download ownership,
  queue snapshot, ensureSong) is independent of T22 and can be designed — and even
  implemented — without it.
- There is no production history code yet, so no runtime behavior forces the answer
  earlier.

Checkpoint cleared: answered **(b)** on 2026-11-01 — see §4.3.

### 4.2 Options, implications, and the question

| | (a) keep per-day | (b) per-play table + per-day rollup view | (c) both (per-play + maintained per-day table) |
|---|---|---|---|
| Schema | unchanged | new `play_events` (id, providerId, externalId, playedAtMs, played, skipped, durationMs, submit state for outbox correlation); indices `(providerId, externalId)`, `(providerId, playedAtMs)`; per-day becomes a `@DatabaseView` (`GROUP BY` day) for export/UI compat | (b) + keep `playback_events` maintained in the same write path (or trigger) |
| D-7 delete-one-play / edit-play-count | impossible at play granularity (same-day rows are merged bitmasks; a "play" no longer exists as a row) | natural: delete/update one `play_events` row; rollup re-derives | natural, but the per-day copy must be repaired on edit |
| ListenBrainz real timestamps | cannot (only newest play per day is representable) | yes | yes |
| Integrity | simplest; today's unique-index shape | needs a small write path (insert-per-play; no upsert semantics) | two sources of truth; drift risk |
| Storage growth | smallest | ~1 row per play; at human rates (≤ 10⁵ rows/yr) ≪ 10 MB; negligible | 2× (b) |
| Query performance (stats) | per-day aggregation trivial | rollup view aggregates on read; with the proposed indices this is O(matches/day) and fine at the above volumes; materialize later only if measured | best read, worst write |
| Migration cost in v2 | zero | create table + indices + view; copy existing `playback_events` rows with `played=true` into `play_events` at `playedAtMs = date` (day-precision; flagged as derived), then drop `playback_events` | create both; same copy |
| Risk | silent capability ceiling discovered in Phase 6 after v2 shipped → would need a v3 migration (the thing this gate exists to prevent) | new write path to test (small; no production callers yet) | most code for no capability gain over (b) |

Recommendation: **(b)** — justified because both ROADMAP-committed consumers (D-7 and
ListenBrainz Track A) require per-play timestamps that (a) physically cannot
represent, because the prompt's own analysis reaches the same conclusion, and because
deciding now is what keeps the single-migration guarantee intact. Option (c) adds a
second source of truth for no extra capability. Note this **supersedes** the
Phase-2 memory line ("per-play logging = one row per track per day"), which I will
update in `.agents/memory/` once you answer.

> **T22 QUESTION (blocking only for migration DDL): (a) keep per-day, (b) per-play
> table + per-day rollup view (recommended), or (c) both?**
> If (b)/(c): confirm the derived-timestamp copy of existing per-day rows
> (`playedAtMs = date`) is acceptable for the (unreleased, test-only) v1 data.
> If (a): the v2 migration ships without history changes and the per-play question
> returns as a future v3 decision before Phase 6.

### 4.3 DECISION RECEIVED (2026-11-01) — logged in this report at the user's request

**T22 = (b): per-play table + per-day rollup view.** The user answered at the close
of Session 1 and asked that the decision be recorded here rather than waiting for the
next session. No next session was started.

Consequences locked by this answer:

1. Migration step 4 (§3.9) is now fixed as the (b) DDL:
   - new `play_events` table (own vocabulary): `id`, `providerId`, `externalId`,
     `playedAtMs`, `played`, `skipped`, `durationMs`, plus a submit-state column for
     outbox correlation (exact shape finalized when the migration is written);
   - indices `(providerId, externalId)` and `(providerId, playedAtMs)`;
   - a per-day rollup as a Room `@DatabaseView` (`GROUP BY` day) so export/UI
     consumers keep day-shaped reads;
   - existing `playback_events` rows with `played = 1` are copied into `play_events`
     as derived events (`playedAtMs = date`, day-precision, explicitly derived), then
     `playback_events` is dropped.
2. **Sub-question not explicitly answered:** acceptance of that derived-timestamp
   copy. Recorded as open; the plan includes the copy because `playback_events`
   currently holds only test-seeded rows (zero production callers and readers — §4)
   and nothing is released, so the step is trivially removable at Session 4 review if
   vetoed. Either way no real data is at risk.
3. This supersedes the Phase-2 memory line ("per-play logging = one row per track per
   day"); `.agents/memory/open-questions.md` updated the same day.
4. D-7 (delete-one-play / edit-play-count) is now representable at play granularity;
   the per-day rollup view preserves export compat.
5. ROADMAP text reconciliation (per-play logging wording) remains a T24 deliverable.

## 5. Session plan (proposed, five sessions)

1. **This session** — audit, dependency map, design, T22 question (asked above);
   **T22 answered (b) before the session closed** and logged in §4.3.
2. **Entities/DAOs/API layer** — provider columns, `ProviderEndpoint`,
   `ClientCertSpec`, `ProviderTraits`, download columns, queue snapshot entities/DAO,
   mapper/registry/client changes, status tracker. No migration yet; working tree
   temporarily incomplete (Room schema warning is expected and reported honestly).
   No commit.
3. **Behavior layer** — `ensureSong`/`SongRepository`, trait gates (outbox/sync/
   download-result), tracker wiring seams, all non-migration unit tests green.
   No commit.
4. **Migration** — write `MIGRATION_1_2` + schema `2.json` + the full §D test battery
   (T22 answered — (b) DDL per §4.3 — so no schema blocker remains). No commit.
5. **Finalize** — docs stubs (`docs/omnisonic/storage/`: schema v2, provider kinds,
   song materialization), banned-name scan, full `./gradlew test :app:assembleDebug`,
   **single T16 commit** in Session 5 as instructed.

## 6. Risks

1. **T22 answer outstanding** — RESOLVED 2026-11-01: the user chose **(b)** (§4.3).
   The migration-DDL blocker is gone. Residual: the derived-timestamp copy
   sub-question (§4.3 item 2) remains open with the copy included by default and
   trivially removable at Session 4 review; and a future (a)-style reversal would now
   require a v3 migration before Phase 6 — accepted by choosing (b).
2. **Table rebuilds** (`providers`, `download_items`) must reproduce Room's expected
   v2 schema exactly; `runMigrationsAndValidate` is the gate. `providers` has no
   indices to lose; `download_items` must preserve its two.
3. **Trigger installation path** — migration must self-install triggers because
   `MigrationHarnessTest` builds without `OpenCallbacks` (verified).
4. **Backfill policy honesty** — `download_items.providerId` parse-or-delete must be
   documented in the migration even though no production rows exist.
5. **Gates without consumers** — trait/wifi gates have no production callers until
   Phase 4/6/7; proofs are unit-level at the seams (stated in the spec's own proofs).
6. **ProviderConnection signature change** ripples into `BlockFailoverTest`,
   `SubsonicClientTest`, `ProviderConfigMapperTest`, `ProviderClientRegistryTest`,
   `LocalMediaProviderTest` (mechanical; logic unchanged).
7. **Fill-NULL-only `ensureSong`** is the conservative reading of "upserts a minimal
   row"; flagged in §3.7 for a one-line change if you prefer overwrite.
8. Intermediate sessions leave a **temporarily incomplete working tree** (no commits
   until Session 5) — per your instruction, reported each session.
9. mTLS password rides `secretBlob`; backup-exclusion of these blobs is T21's
   `BackupExcludes` deliverable (already in the prompt) — tracked, not lost.

## 7. Test plan (maps to §D proofs)

| Proof (Prompt §D) | Test |
|---|---|
| Real migration: v1 seeded (provider with secondary block, songs, outbox, download item, playback event) → migrate → data intact, triggers counted, columns defaulted, `kind='REMOTE_SERVER'` | rewritten `MigrationHarnessTest` via `MigrationTestHelper.runMigrationsAndValidate(2)` |
| T22 (b): a v1 `playback_events` row becomes a derived `play_events` row (`playedAtMs = date`); the per-day rollup view reproduces the old per-day shape | part of the rewritten `MigrationHarnessTest` (copy assertions + view aggregation check) |
| `PRAGMA recursive_triggers` still off; provider edit keeps data | same test, extended assertions |
| DaoSqlCompatibilityTest green | existing scanner (auto-covers new DAO SQL) |
| Deleting a provider removes its `download_items` | `ProviderCascadeTriggerTest` + seed helper |
| DEVICE/VIRTUAL rows load; REMOTE_SERVER blank host unloadable | `ProviderConfigMapperTest` |
| Outbox enqueue with `reportsUserData=false` writes no rows | `OutboxQueueTest` |
| Download enqueue with `supportsDownload=false` → typed result | new traits test |
| Real `LocalMediaProvider` loads from a persisted row end-to-end | `LocalMediaProviderTest` extension |
| wifiOnly blocks sync/drain when not on Wi-Fi (fake network) | new `ProviderStatusTrackerTest` (Shadow ConnectivityManager/NetworkCapabilities) |
| mTLS mapper → ConnectionSpec → real handshake, both profiles | new core-data test in `TlsHandshakeTest` style (HeldCertificate + MockWebServer, both in test deps) |
| Queue entry without a `songs` row restores with title/artist/cover | new queue DAO test |
| `ensureSong` twice → same id, playCount/isFavorite untouched; provider delete cascades | new `SongRepositoryTest` |

## 8. What this session did NOT do (per instructions)

No production code, schema, or migration edits; `SCHEMA_VERSION` unchanged; nothing
committed. The only file written is this report.
