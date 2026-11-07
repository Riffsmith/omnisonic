# Architecture and policy decisions — `decisions.md`

Purpose: the authoritative record of durable project decisions — what was decided,
when, and why. Mechanics and proofs live in the linked topic documents; this file
records the choices and their rationale so no decision depends on session history.

How derived: consolidated from the confirmed user decisions recorded during the
pre-release remediation pass (2026-10-30 – 2026-11-07) and the earlier stack
decisions, cross-checked against the shipped code and tests in this repository.

Confidence: every entry below is [VERIFIED] against the linked topic doc and the
code it names, unless marked otherwise.

Date: 2026-11-07

Links: `README.md` (documentation map); individual topic docs per section.

---

## Stack and database foundations

| Decision | Notes |
|---|---|
| Kotlin + Jetpack Compose + Media3 (ExoPlayer + MediaLibraryService) + OkHttp + Coil 3 + DataStore + Moshi (codegen) | Version pinning and rationale: `toolchain/build-stack.md` |
| DI = Hilt | |
| App id `app.omnisonic`, minSdk 29, target/compileSdk 36 | The SQLite syntax floor that follows from minSdk 29: `storage/sqlite-api29-floor.md` |
| Room with explicit migrations and cascade triggers instead of foreign keys | No FK pragma and `PRAGMA recursive_triggers` is never enabled; provider deletes cascade via `ProviderCascadeTriggers`. There are no migration steps today: the pre-release database is one clean v1 baseline (D-11). See `storage/schema-v2.md` |
| Exactly one explicit migration per schema step, never destructive fallback; the pre-release chain was reset to one clean v1 baseline (D-11 option B) | A future change appends one `Migration(1, 2)` and exports `2.json`; an unknown schema fails loudly. `storage/schema-v2.md` |
| Enum-valued persisted state is stored by NAME (string), never by ordinal | Playback settings and DB enums alike; legacy ordinal reads get a one-release fallback: `playback/settings-persistence.md` |

## Network and security

| Decision | Date | Rationale (short) | Detail |
|---|---|---|---|
| Cleartext HTTP: global allow via Network Security Config + in-app warning for http on non-private hosts | 2026-10-30 | NSC `domain-config` cannot match IP literals, so per-host allowlists would not fix the primary LAN-Navidrome case; a global allow plus an explicit warning gives informed consent instead | `network/cleartext-policy.md` |
| mTLS is opt-in and fails closed | 2026-11-02 | Stored blobs can never enable mTLS by themselves; an enabled row with an unsealable/corrupt keystore is unloadable rather than silently downgraded; custom trust anchors decode independently of the client-certificate gate | `providers/provider-connections.md` |
| Credentials that cannot be unsealed never become empty credentials | 2026-11-03 | The mapper returns a typed `ProviderLoadResult`; the registry refuses to serve the provider and the status tracker shows `AUTH_FAILED` ("re-enter password") | `providers/credential-handling.md` |
| Sealed secret columns (`secretBlob`, `mtlsKeystoreBlob`, `trustedCertsBlob`) never leave the device through backup/export | 2026-11-03 | The Keystore key is device-bound; the canonical exclusion list is `core-data` `BackupExcludes`, enforced by a test that fails on renames | `providers/credential-handling.md` |

## Data and sync

| Decision | Date | Rationale (short) | Detail |
|---|---|---|---|
| Column ownership is classified SERVER or LOCAL for every song/album/artist column, enforced by a test | 2026-10-31 | Sync must never reset locally-owned state; a new column fails the test until classified | `storage/library-sync-ownership.md` |
| Pruning uses tombstones: full sync only; an unseen song is deleted only if `playCount == 0` AND no pending outbox row exists, otherwise kept with `missingOnServer = true` | 2026-10-30 | Local play history and pending scrobbles survive server-side deletions; `isFavorite` alone does not block deletion; albums/artists are pruned when no live children remain; vanished playlists are pruned outright; diff sync never prunes | `storage/library-sync-ownership.md` |
| Resume merge: a server bookmark applies only when the song has no pending resume outbox row AND the bookmark is newer than the local resume state | 2026-10-31 | Newer local data must not be clobbered by an older server bookmark | `storage/library-sync-ownership.md` |
| No `REPLACE` upserts anywhere: DAOs resolve the existing row by natural key and use Room `@Upsert` | 2026-11-02 | SQLite `REPLACE` deletes-then-inserts: it orphans join rows on unique-key hits and would fire the provider cascade triggers if `recursive_triggers` were ever on | `storage/library-sync-ownership.md` |
| Pruning is stamp-based (D-10): each sync run mints a unique run id, stamps every row it writes (`lastSeenSyncId` on songs/albums/artists/playlists), and full-sync pruning selects stale stamps instead of passing seen-id lists through SQL; every remaining parameterized IN list is chunked at 500 behind `...Chunk` DAO methods | 2026-11-05 | Android 10 ships SQLite 3.22, which rejects statements bound with more than 999 variables, so a 5,000-song library's seen-list would crash every prune; per-row stamps move the comparison into SQL with two bound variables regardless of library size | `storage/library-sync-ownership.md` |
| Full-sync pruning is guarded (FIX-02): the walk records the known song count before it starts and collects a seen-set of reported song ids plus listed album ids (album ids count even when their fetch fails); pruning is skipped with `prune skipped: <reason>` when the seen-set is empty, the reported song count is under half the known count, or a listed album fetch failed. With the guards in place, rows with NULL stamps join the unseen set, and rated, resumed or clipped songs (userRating / resumeMs / windowStartMs / windowEndMs) are tombstoned rather than deleted (D-9, remediation); favorite alone stays non-blocking; chapters of removed songs/albums go with them | 2026-11-05 | An empty or partial server response (broken listing, failed page fetch, per-album error) used to be indistinguishable from a genuine library wipe — the prune deleted or tombstoned everything the walk did not report; the guards trade a conservative no-op for that failure mode. NULL-stamp rows (local materialisations, rows written before the stamp existed) can only be interpreted once a walk is known complete | `storage/library-sync-ownership.md` |
| Pre-release schema chain replaced by one clean v1 baseline (D-11 option B, FIX-15); pre-release column labels renamed to the durable vocabulary: latinTitle, providerPayloadJson, position, origin, rank, discNamesJson, resumeTrackPath, resumeMs, windowStartMs, windowEndMs, ruleJson | 2026-11-07 | No released database exists, so a migration chain from the discarded pre-release shape protects nothing; one exported baseline removes dead migration code and keeps the schema/harness honest. The baseline includes the FIX-01 `lastSeenSyncId` stamps, `queue_state.positionMs`, and the `play_events_daily` view | `storage/schema-v2.md` |
| Suspend-call error handling uses `runCatchingCancellable`, which rethrows `CancellationException` | 2026-11-03 | Cancelled coroutines must never be logged as task failures or retried with backoff | `protocol/cancellation-contract.md` |
| Outbox: one payload class, media types stored as `ProviderMediaType` names, due order `nextAttemptAt, createdAt, id`, per-item action order play_count → favorite → user_rating → resume_point, empty payloads park as FAILED | 2026-11-03 | Moshi ignores renamed fields, so payload drift silently dropped user data; deterministic ordering makes resume clears follow play counts | `storage/outbox-contract.md` |
| Outbox completion is conditional on the submitted payload, and progress removes only the timestamps the drain submitted | 2026-11-05 | An enqueue that merges into the row while a submission is in flight must survive; deleting by id lost concurrent plays, and rewriting the payload from the stale copy lost newer favorite/rating/resume values | `storage/outbox-contract.md` |
| Listen history is per-play: `play_events` (one row per play or skip) plus the `play_events_daily` rollup view | 2026-11-01 | Play-level history editing and exact-timestamp submissions (ListenBrainz) are impossible with merged per-day rows; the view keeps day-shaped reads for export/UI | `storage/schema-v2.md`, `storage/sqlite-api29-floor.md` |
| Queue persistence lives in the main database as snapshot rows (`queue_entries`, `queue_state`) and restores without joining `songs` | 2026-11-01 | A queue entry whose song was never synced must still restore with its metadata; the shuffle order is stored (not regenerated) because the anchor depends on the original order. This supersedes the earlier phase plan of a separate small Room DB inside `:playback` | `storage/schema-v2.md` |
| Song materialization (`SongRepository.ensureSong`) inserts a minimal row and fills ONLY null snapshot columns | 2026-11-01 | Playback/favorites/playlists work without a prior sync and never downgrade synced metadata or reset local state | `storage/library-sync-ownership.md` |
| Provider rows carry a `kind` (`REMOTE_SERVER` / `DEVICE` / `VIRTUAL`); endpoints are the sealed `ProviderEndpoint.Remote`/`None` | 2026-11-01 | Serverless providers become representable without fake `"localhost:0"` rows; server-only clients fail fast at one named seam (`requireRemote()`) | `providers/provider-connections.md` |
| Provider capabilities are runtime traits (`ProviderTraits`), never persisted, consulted as polite typed skips | 2026-11-01 | Sync, outbox enqueue, download enqueue and (future) transcode planning must degrade gracefully on providers lacking the capability | `providers/provider-connections.md` |
| Availability gating through one shared run gate (`ProviderStatusTracker`: `OK / NEEDS_WIFI / AUTH_FAILED / OFFLINE`) | 2026-11-02 | Sync, drain and download consult a single seam instead of duplicating network checks; blocked work stays due; a VPN network conservatively counts as not-Wi-Fi | `providers/provider-connections.md` |
| Downloads are owned by a provider: `download_queue.providerId` is NOT NULL, backfilled from the stream-key parse, never invented | 2026-11-02 | Provider deletion must cascade its download rows; enqueue is idempotent and never resets progress | `providers/provider-connections.md`, `storage/schema-v2.md` |

## Playback

| Decision | Date | Rationale (short) | Detail |
|---|---|---|---|
| Stable exposed player: one `ForwardingPlayer` exterior over a stable `SimpleBasePlayer` bridge; engines swap behind it; the exposed timeline is the FULL effective queue (metadata-only items) | 2026-10-31 | Session/UI identity survives engine swaps (a session bound to a raw engine player would hold a released object); queue-derived next/previous commands stay correct; hot engine swaps and future crossfade are preserved; a full queue beats a 2-item window once arbitrary jumps and queue-length semantics are included | `playback/exposed-player.md` |
| Gapless handoff: `prepareNext` holds a `[current, next]` engine window; auto-advance is verified against the queue's expected next and adopted without replay | 2026-10-31 | `STATE_ENDED` remains the no-prepared-next case; a mismatched advance resyncs instead of being accepted | `playback/exposed-player.md` |
| Sink processors stay active after first configure and apply neutral transforms when off; each engine gets its own chain from a factory that re-applies the latest config | 2026-11-01 | Measured: Media3's `AudioProcessingPipeline` snapshots `isActive()` at configure/flush, so active-toggling mid-stream changes were inaudible (0 output bytes); per-engine chains keep two engines from sharing stateful processors | `playback/sink-processors.md` |
| Offload is disabled automatically whenever a sink processor is wired, and `EngineInfo` reports `offloadEffective` / `extensionAvailable` as measured facts | 2026-11-01 | Offloaded output bypasses audio processors entirely; the manual `sink.setOffloadMode` call was a capability declaration, never an engagement | `playback/offload-and-extensions.md` |
| Decoder-extension support deferred; the `DecoderPreference` knob removed until a decoder module is actually scheduled | 2026-11-01 | No decoder extension is on the classpath, so the preference silently did nothing | `playback/offload-and-extensions.md` |
| Engines default to `WAKE_MODE_NETWORK` (per-engine override stays available) | 2026-10-31 | `WAKE_MODE_LOCAL` holds no Wi-Fi lock, so screen-off streaming over Wi-Fi can stall | `playback/wake-mode.md` |
| API and MEDIA client profiles are separate; media reads use the provider's MEDIA-profile OkHttp client | 2026-10-30 / 2026-10-31 | Media must not be content-encoded and needs real Range/Content-Length semantics; provider headers, mTLS and timeouts must apply to audio reads | `network/client-profiles.md`, `playback/per-provider-media-clients.md` |
| Stream and cover cache identity is provider-stable (`StreamKey` / cover keyer), independent of rotating auth parameters | 2026-10-30 | Per-call salts would otherwise make the cache never hit | `playback/stream-cache-identity.md` |
| Stale/failed streams re-resolve deterministically (expiry skew, one 401/403/410 restart per stream) and transport selection is explicit (`PROVIDER_HTTP` / `DIRECT_URI` / `CUSTOM`) | 2026-11-01 | Signed URLs expire mid-session without surfacing errors or loops | `playback/stream-cache-identity.md` |

## Scrobbling

| Decision | Date | Rationale (short) | Detail |
|---|---|---|---|
| Backdated scrobbles are hint-gated: `backdateScrobbles: auto\|on\|off` per provider; `auto` sends `time` only for known-honoring servers (initially `navidrome`), unknown/absent hints stay OFF | 2026-10-30 | The earlier extension-name-based rule (`formPost`/`transcodeOffset`) had no connection to the scrobble `time` parameter; the hint rule needs no probe round-trip and keeps a per-provider kill switch | `protocol/scrobble-timestamps.md` |
| One queued play = one timestamp; submissions carry strictly increasing times (+1 ms on ties); the drainer probes capabilities once before its first play-count task | 2026-10-31 | N offline plays must reach the server as N correctly backdated scrobbles, also on a cold start | `protocol/scrobble-timestamps.md` |
| Play-count payloads keep `playCountDelta == playTimestamps.size`; the drainer submits timestamps chronologically in 100-play chunks and records progress after each accepted chunk | 2026-11-05 | An uncapped delta with a capped timestamp list silently dropped plays; chunk progress bounds a failure to the current chunk while preserving the FIX-03 accepted-prefix contract | `storage/outbox-contract.md`, `protocol/scrobble-timestamps.md` |

## Shell and onboarding

| Decision | Date | Rationale (short) | Detail |
|---|---|---|---|
| First-run landing: fresh installs land on **provider setup**, then **home**; every later launch lands on **home** directly | 2026-11-07 | A library app without a provider is an empty shell — the first-run gate mirrors the studied app's behavior (a `first.run` bool picks its Welcome/setup destination over the main screen, StartActivity.java:214-216), but with Omnisonic names and mechanics | The gate is an **ordered onboarding-steps list** (today: `[providerSetup]`) in the shell, not a hardcoded branch: a tutorial or any future step inserts between setup and home without touching the gate logic, and `onboardingComplete` (shell preferences DataStore) is set only when the list is exhausted. Task 2 ships the gate + flag + a placeholder; the provider-setup screen itself is task 2b (add-connection form over the Phase 3 provider/credential machinery + initial sync trigger + set flag + navigate home). Route: `providerSetup` (UI route table, UI_SCREENS.md §5). Open sub-question for 2b: whether to offer a "continue without a server" skip (not decided; v1 default = setup completes). Naming: Omnisonic coinage only (`onboardingComplete`, `providerSetup`) — never the studied key/destination spellings |

## Toolchain

| Decision | Date | Rationale (short) | Detail |
|---|---|---|---|
| AGP 8.9.1 / Gradle 8.11.1 / Kotlin 2.2.10 / KSP 2.2.10-2.0.2 (`ksp.useKSP2=false`) / Hilt 2.57.2 / media3 1.11.1 | 2026-11-03 | compileSdk 36 requires AGP ≥ 8.9.1; Room 2.6.1 fails under KSP2; Hilt 2.60+ requires AGP 9; media3 1.11.1 forces stdlib 2.2 | `toolchain/build-stack.md` |
| Release builds are minified + resource-shrunk with Moshi codegen adapters (no runtime reflection); broad native `pickFirsts` are not used | 2026-11-03 | Codegen adapters survive R8 via Moshi's consumer rules; explicit packaging avoids hiding real native conflicts | `toolchain/build-stack.md` |
| `:baseline-profile` is a `com.android.test` producer targeting `:app` | 2026-11-03 | The profile producer module type must be `com.android.test` | `toolchain/build-stack.md` |
