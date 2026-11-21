# Automatic download rules — `automatic-rules.md`

Purpose: Pin down the persisted automatic-rule model added in Phase 7 task 7a,
its complete-snapshot expansion added in task 7b, the non-destructive change
reactions and wanted-set generations added in task 8a, and the destructive
pruning/replacement pass added in task 8b: rule identity, the deterministic
uniqueness key, the favorites sentinel, the provider-deletion cascade,
profile-scoped reads, the additive v2 → v3 migration, how rules become rolling
queue rows, which tables are observed, how triggers coalesce and yield to a
running sync, the stale-generation invariant, deterministic variant ownership,
queue cancellation, filesystem-first file pruning and the replacement-old
continuity rule.

How derived: own-source reading of `DownloadRuleEntity`, `DownloadRuleDao`,
`AutomaticRuleRepository`, `AutomaticRuleCatalogDao`,
`AutomaticRuleTrackSource`/`DatabaseAutomaticRuleTrackSource`,
`AutomaticRuleExpander`, `DownloadEnqueuer`, `DownloadWorkerCoordinator`,
`AutomaticRuleChangeDao`/`AutomaticRuleChangeObserver`,
`AutomaticRuleReactionCoordinator`/`AutomaticRuleReactionFactory`,
`AutomaticRulePruner`, `DownloadQueueCancellation`,
`AutomaticRuleGeneration`/`AutomaticRuleGenerationStore`,
`OfflineOwnershipGuard`, `DownloadWorkerCoordinator`
(`cancel`/`cancelIfAutomaticRolling`), `LibrarySyncStatusRepository`,
`OfflineStorage`, `ForegroundDownloadRuntime`,
`Migrations.MIGRATION_2_3`, `ProviderCascadeTriggers`, the exported Room schema
`core-data/schemas/app.omnisonic.core.data.db.OmnisonicDatabase/3.json`, and
the in-repository test runs described under Evidence. The schema shape was
approved at the task-7a decision gate on 2026-11-21; the variant-conflict rule
is D-48.

Confidence:
- [VERIFIED] The rule table, its unique index and its cascade trigger exist;
  `SCHEMA_VERSION = 3` and `Migrations.ALL` contains `1 → 2` and `2 → 3`.
- [VERIFIED] Duplicate adds are strict no-ops; identity is provider + kind +
  target external id; every rule read takes `ProfileScope`; provider deletion
  removes the provider's rules, queue rows and offline rows.
- [VERIFIED] Expansion reads rules and catalog rows through scoped SQL, writes
  only through `DownloadEnqueuer`, treats a playlist/album with any unresolved
  expected track as a typed `UNSYNCED` no-op, and never deletes or rewrites an
  existing queue row.
- [VERIFIED] The six automatic-rule input tables (rules, songs, albums,
  playlist entries, queue and offline rows) are observed directly; their
  changes and provider-sync completions are coalesced into one serialized pass,
  a running provider sync blocks a pass and its hand-off, and only a complete,
  unchanged rule snapshot is published as a verified wanted-set generation.
- [VERIFIED] Pruning consumes only a verified generation, cancels unwanted
  automatic rolling queue rows through the existing coordinator, deletes an
  automatic rolling file before its row, retains manual/permanent/exported and
  unparseable or identity-mismatched rows, and rechecks the generation, rule
  snapshot and sync before every provider delete group. It also revalidates the
  current persisted row immediately before each destructive action, under a
  shared ownership lock / conditional cancel, so a row pinned or converted
  after the candidate snapshot is never deleted.
- [VERIFIED] The newest current rule owns a conflicting automatic variant; the
  older rule does not enqueue its variant, so two rules cannot oscillate. An
  older automatic file is retained until a wanted variant of the same song is
  present offline, including after a failed replacement.
- [INFERRED] A future rule kind added to the enum will read unknown persisted
  kinds as absent until the enum is extended; `[UNKNOWN]` until such a kind is
  actually introduced. The same limitation applies to generation completeness:
  an unknown persisted kind is absent from the current rule list.
- [UNKNOWN] Behaviour on a large production database — no released database
  exists; the harness uses small fixture rows.

Date: 2026-11-21

Links: `schema-v2.md`, `download-queue-lifecycle.md`, `download-completion.md`,
`../decisions.md` (D-47, D-48), `.agents/plans/phase-7-tasks.md`
(TODO 7/7a/7b/8/8a/8b)

---

## Scope

Task 7a persists rules. Task 7b expands the current persisted rules into queue
rows. Task 8a owns the non-destructive reaction half: direct table observers,
serialized/coalesced passes, sync yield, the all-current-rule wanted union and
the verified generation hand-off. Task 8b owns the destructive half over that
generation: queue cancellation, filesystem-first rolling-file pruning and
variant replacement. Nothing here adds UI, planner, filter language, or Phase 8
variant defaults. `download_queue.autoRuleId` already existed and remains the
enqueue-time intent; `offline_media.autoRule` stores the same decimal id.
Neither column is an ownership record: expansion computes the wanted set from
the current rules, and pruning treats the flags only as a candidate filter,
never as proof that a row is unwanted.

## Table shape

`download_rules` is a new table; no existing table or row changes [VERIFIED]:

| Column | Type | Meaning |
|---|---|---|
| `id` | `INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL` | Row handle only |
| `providerId` | `INTEGER NOT NULL` | Owning provider |
| `kind` | `TEXT NOT NULL` | `FAVORITES`, `PLAYLIST` or `ALBUM`, by NAME |
| `targetExternalId` | `TEXT NOT NULL` | Provider-scoped playlist/album external id; `''` for favorites |
| `transcodeBitrateKbps` | `INTEGER NULL` | Optional variant; null = provider default |
| `format` | `TEXT NULL` | Optional variant; null = auto |
| `createdAt` | `INTEGER NOT NULL` | Insert time |
| `updatedAt` | `INTEGER NOT NULL` | Insert or explicit variant-change time |

There is deliberately no foreign key to `providers`, `playlists`, `albums` or
`songs`, matching the rest of the schema (D-11). Variant columns reuse the
existing queue/media-key vocabulary (`transcodeBitrateKbps`, `format`); Phase 8
may supply defaults, and this task does not plan a variant.

## Identity and deterministic uniqueness

[VERIFIED] The identity is `(providerId, kind, targetExternalId)`, enforced by
the unique index `index_download_rules_providerId_kind_targetExternalId`:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS `index_download_rules_providerId_kind_targetExternalId`
ON `download_rules` (`providerId`, `kind`, `targetExternalId`)
```

- The leftmost `providerId` prefix also serves provider-scoped reads, so no
  second provider index is created.
- `AutomaticRuleRepository.add` inserts with `OnConflictStrategy.IGNORE`. A
  duplicate is a strict no-op: the existing variant, `createdAt` and
  `updatedAt` are untouched and the outcome is `DUPLICATE`.
- Changing a variant is the explicit `setVariant` command, which bumps
  `updatedAt` and is itself profile-scoped. A duplicate add can never act as an
  implicit edit.
- The same external id on another provider, or under another kind, is a
  distinct rule.
- Removing a rule and re-adding the same target mints a new `id` but keeps the
  same identity tuple. Nothing keys durable ownership off the row id.

### Why favorites use the empty-string sentinel

SQLite treats `NULL` values as distinct in a UNIQUE index, so a nullable
`targetExternalId` would allow unlimited duplicate favorites rules for one
provider. The non-null `''` sentinel keeps one total uniqueness key and one
favorites rule per provider without a partial index, which Room's `Index`
annotation cannot declare. Provider external ids are non-blank, and
`AutomaticRuleRepository` rejects blank targets for `PLAYLIST`/`ALBUM`, so the
sentinel cannot collide with a real target. A caller-supplied favorites target
is normalised to the sentinel.

## Deleted or recreated targets

[VERIFIED] A rule survives catalog deletion because no foreign key ties it to
`playlists`/`albums`/`songs`. If a provider deletes and later recreates a
playlist or album with the same external id, the existing rule still identifies
it and a re-add is a duplicate. Recreation with a different external id is a
different rule. A deleted rule may leave `download_queue.autoRuleId` or
`offline_media.autoRule` referencing an id that no longer exists; that is
harmless by design because those columns are intent hints and task 8 recomputes
the wanted union from the current rules.

Persistence takes no catalog read and no destructive action on a missing
target. Expansion (below) owns typed stale/skipped results, and still takes no
destructive action.

## Provider deletion cascade

[VERIFIED] `ProviderCascadeTriggers.CREATE_STATEMENTS` now includes
`download_rules`, so deleting a `providers` row deletes that provider's rules in
the same database operation as its queue and offline rows. The factory installs
triggers in both `onCreate` and `onOpen`, so a database upgraded to v3 gains the
rule trigger on its next open. `PRAGMA recursive_triggers` remains off.

Rule deletion itself deletes no queue rows or files. A user-facing rule removal
is a persistence change; pruning unwanted automatic downloads and files is task
8, after a freshly verified wanted set.

## Profile scope

[VERIFIED] Every rule DAO read is a Raw+wrapper pair taking `ProfileScope`:
`selectAll`, `selectByProvider`, `selectByKind`, `selectByTarget` and
`selectById`, each with an `instr(...)` provider-exclusion filter. Every new
catalog read follows the same convention: provider existence, favorite songs,
playlist target/entry-count/songs, and album target/songs. Internal expansion
passes `ProfileScope.Unrestricted`; UI reads use the real scope. Tests prove a
hidden provider's rules and catalog rows disappear from scoped reads while
`Unrestricted` still sees them.

## Expansion into queue rows (task 7b)

[VERIFIED] `AutomaticRuleExpander.expand(scope)` reads the current rules in the
repository's deterministic `providerId, kind, targetExternalId, id` order and
resolves each against the local catalog through `AutomaticRuleTrackSource`:

| Kind | Target | Current tracks |
|---|---|---|
| `FAVORITES` | provider only (`''` sentinel) | `songs` for the provider with `isFavorite = 1`, `missingOnServer = 0`, a non-blank `externalId`, ordered by id |
| `PLAYLIST` | provider + playlist external id | the playlist's `playlist_items` in position order, joined to local songs by `songId` or stable external id, deduped by external id |
| `ALBUM` | provider + album external id | the album's local `songs`, disc/track ordered with unknown numbers last, then title and id |

Resolution is a complete snapshot or nothing. A playlist whose summary count or
actual entry rows exceed the matched song rows, or an album whose usable local
songs are fewer than `songCount`, returns `UNSYNCED` and enqueues nothing. This
deliberately avoids a partial wanted set that task 8 could later mistake for
deleted tracks. An entry that cannot be resolved because its song row has not
arrived is therefore retried by a later explicit expansion, not by an internal
loop. A genuinely empty playlist/album (`entryCount`/`songCount` zero and no
rows) is a valid empty wanted set; favorites has no separate expected count, so
an empty or fully-tombstoned favorites rule is also valid and empty.

Each resolved track is submitted through `DownloadEnqueuer.enqueue` with
`tempCache = true`, the rule's optional `transcodeBitrateKbps`/`format`, and the
rule id as `autoRuleId`. Consequently the existing capability check, run gate,
global-pause insert, and strict media-key insert-if-absent all apply without a
second gate implementation. Expansion does not claim, start, retry, cancel or
finish a transfer; it only commits queue rows for the service's normal loop.

When two current rules resolve the same song at **different** variants, the
newest rule (`updatedAt`, then row `id`) owns that song's identity; the older
rule's variant is not enqueued. Its `wantedMediaKeys` therefore excludes that
key, while `wantedCount` still reports the resolved tracks for diagnostics.
Overlapping rules with the same variant still call the strict duplicate enqueue,
so both report an accepted no-op. This selection is automatic-only: a manual or
permanent offline/queue row never changes which automatic variant wins, and it
is never deleted by the resulting replacement.

### Typed outcomes

| Outcome | Meaning | Writes |
|---|---|---|
| `EXPANDED` | Resolution succeeded; `wantedCount` plus gate/destination counts describe the batch | One insert-or-ignore per owned track; a conflicting older variant is skipped |
| `STALE_TARGET` | Provider, playlist or album is absent locally | None |
| `UNSYNCED` | Target exists but its expected songs are not all locally usable | None |
| `READ_FAILED` | The catalog read threw | None |

`blockedBy` records the provider status per refused enqueue
(`OFFLINE`, `NEEDS_WIFI`, `AUTH_FAILED`); `unsupportedCount` records permanent
capability refusal and `destinationConflictCount` records an existing same-key
row for another export destination. A blocked or unsupported pass is a normal
typed result, not a failure state; repeating the pass is an explicit caller
action, never an internal retry.

## Deterministic variant ownership

[VERIFIED] Two current rules can resolve the same song with different quality
or format. Expansion picks one automatic owner per `(providerId, externalId)`:
the rule with the later `updatedAt`, then the larger row `id` as the tie-break.
Only that rule's variant enters the generation's wanted union and only that
variant is enqueued. A later explicit `setVariant` bumps `updatedAt` and moves
ownership; adding a rule gives it a fresh timestamp. This prevents the
oscillation in which rule A prunes rule B's variant and B's next pass prunes
A's.

Manual rolling and permanent rows sit outside this automatic contest: they are
protected from pruning, and the automatic winner may still download its own
variant. When ownership changes, the previous automatic variant remains
playable until the new wanted variant has an offline row (below).

## Destructive pruning (task 8b)

[VERIFIED] `AutomaticRulePruner` is invoked by
`AutomaticRuleReactionCoordinator` immediately after it publishes a generation,
inside the same pass mutex. It never runs from a retained stale generation:
`AutomaticRuleGenerationState.verified` must be true and a generation must be
present, otherwise the result is `NOT_VERIFIED` and nothing is touched.

### Guards before every destructive group

- **Same generation:** the store must still be verified with the same
  generation `sequence`. A trigger that invalidated or replaced it stops the
  remaining groups.
- **Same rule snapshot:** the current rules are re-read with
  `ProfileScope.Unrestricted` and must equal the generation's `expansions`
  rules. An add, remove, variant edit or provider cascade between publication
  and pruning stops the pass as `RULES_CHANGED`; a thrown rule read stops it as
  `RULE_READ_FAILED`.
- **No running sync:** `LibrarySyncStatusRepository.isRunning` is checked for
  every generation provider plus every candidate row's provider before the
  pass and again before each provider group. A sync that starts mid-pass
  returns `SYNC_RUNNING` with the involved providers; the coordinator marks the
  hand-off stale and the provider's completion event runs the next pass.
  Groups already completed remain applied and are safe to retry.

Groups are per provider, queue rows first and offline rows second. A stop keeps
the counts of earlier groups; the next pass recomputes rather than resuming
from a partial assumption.

### Candidate ownership

| Row | Is an automatic candidate? |
|---|---|
| `download_queue` | `autoRuleId != null` **and** `tempCache = true` **and** `exportTarget IS NULL` **and** key not wanted |
| `offline_media` | `autoRule != null` **and** `tempCache = true` **and** `sourceKey` not wanted |

Everything else is protected [VERIFIED]:

- a manual rolling row (`autoRuleId`/`autoRule = null`, `tempCache = true`);
- every permanent row (`tempCache = false`), whether automatic or manual;
- an automatic row converted to another export destination (`exportTarget`
  non-null), because public artifacts have user-owned lifetime;
- a wanted row, including a hidden provider's row in the unrestricted internal
  set;
- an offline row whose `sourceKey` and `externalId` cannot recover a song
  identity, including a parsed `sourceKey` that names another provider or
  states a different external id (retained as unknown rather than borrowing
  another provider's identity);
- any file with no `offline_media` row. Pruning never scans the filesystem, so
  the task-5b orphan/quarantine pass remains the only owner of unknown files.

### Queue cancellation

Unwanted candidates in every known state (`PENDING`, `PAUSED`, `USER_PAUSED`,
`ACTIVE`, `FAILED`) are cancelled through the process-wide
`DownloadWorkerCoordinator` exposed as `DownloadQueueCancellation`; pruning does
not add a second delete path. `ACTIVE` receives the existing one-shot `CANCEL`
interruption, so the transfer cancels its call and removes its `.part`; the
cancel is not a counted failure and never increments `errorCount`. A row already
gone is counted as gone; an unknown raw status or a failed cancellation is left
for retry. The queue observer then requests a fresh pass.

### Revalidation before every destructive action

The candidate list is read once per pass, so ownership is revalidated against
the current persisted row immediately before each destructive action [VERIFIED]:

- a queue candidate is re-read and must still satisfy
  `autoRuleId != null && tempCache && exportTarget == null` and still not be
  wanted;
- an offline candidate is re-read and must still satisfy
  `autoRule != null && tempCache` and still not be wanted, and its identity and
  `offlinePath` come from that fresh row;
- the current cancellation seam is conditional: the production coordinator's
  `cancelIfAutomaticRolling` re-reads the row under the same roster lock that
  `convertDestination` takes, so a row converted to an export destination after
  the snapshot is never deleted;
- offline pin/unpin (`DownloadWorkspaceRepository.setManualOwnership`) and the
  pruner share `OfflineOwnershipGuard`, so a pin cannot interleave between the
  row revalidation and the file delete. Revalidation alone would leave that
  window open.

A row that changed ownership is skipped, not counted as a failure; a row that
is simply gone counts as already gone. Replacement protection is evaluated
against fresh `offline_media` presence, so a wanted variant that disappears
mid-pass retains the old playable file.

### Filesystem-first file deletion

An unwanted automatic offline row is removed in this order [VERIFIED]:

1. resolve the fresh row's `offlinePath` under the active root through
   `OfflineStorage.resolveStoredPath`;
2. if the file is absent, treat deletion as complete;
3. otherwise require a regular file and a successful `File.delete()`;
4. only then delete the `offline_media` row by id.

An unavailable root, path escape, non-regular file or failed delete increments
`offlineFileDeleteFailures` and leaves the row (and file) intact, so a later
pass retries. If the file was deleted but the row delete failed, the row is
left and the next pass treats the missing file as already removed. No database
row is removed before its file, and no unknown file is touched.

### Replacement-old continuity

For an unwanted automatic row, the pruner groups wanted keys by
`(providerId, externalId)`. If a wanted variant of the same song is not present
in `offline_media`, the old row is retained
(`replacementRowsRetained`) even though its own key is no longer wanted. This
covers a variant change, a queue row that is pending/active, and a replacement
that has parked `FAILED`; playback never loses its last playable file. Once the
wanted variant's offline row exists, present-key lookup satisfies the
replacement and the old automatic file is pruned on that or the next pass.

Manual and permanent rows are never replacement-old candidates: they are
protected regardless of the automatic wanted union. The newest-rule ownership
above is what keeps two automatic rules stable while this retention runs.

### Device acceptance

[UNKNOWN] Deleting a rule on real hardware and observing only its unshared
rolling files disappear is a device check; no device was attached for this
task. JVM tests use real temporary files and the production coordinator signal,
but filesystem fault timing, volume removal and process death during a delete
remain device-gated.

### Idempotence, overlap and ownership

[VERIFIED] Overlapping rules rely only on the existing media-key identity. The
first rule in deterministic order inserts; later rules receive the strict
`Enqueued` duplicate result and change nothing. One row therefore survives, and
its `autoRuleId` is the first rule's hint, not an ownership record. Different
variants of the same song have different media keys and remain distinct rows.

An existing manual, active, paused, failed or completed row with the same media
key is never rewritten: expansion preserves its ownership (`autoRuleId = null`
for manual), status, progress, error count, timestamps and destination. This is
the same whole-row no-op already proven by `DownloadEnqueuerTest`; task 7b adds
the rule-expansion path over it.

### Large snapshots and SQLite limits

[VERIFIED] Catalog SQL binds only provider/target ids and scope text; it uses no
`IN (...)` list, so a playlist or album of any practical size stays below
SQLite 3.22's 999-variable floor. The 505-track album test expands every row in
one pass. Enqueue remains one insert per track, bounded by the existing queue
and coordinator contracts rather than a bulk insert list.

### No pruning

Expansion is additive. Removing a playlist entry, album track or favorite does
not delete the corresponding queue row or offline file in this task; a changed
collection only adds newly wanted rows. Task 8b consumes a verified current
generation and performs queue cancellation and filesystem pruning, including
retaining the old playable file until a replacement succeeds.

## Change reactions (task 8a)

### Observed tables and why

[VERIFIED] `AutomaticRuleChangeDao` exposes six read-only Room `Flow<Int>`
counts, and `AutomaticRuleChangeObserver` merges them:

| Source | Table | Why it matters |
|---|---|---|
| `RULE_ROWS` | `download_rules` | Rule add/remove and variant edits (`updatedAt` bumps) |
| `SONG_ROWS` | `songs` | Favorite toggles and song/catalog edits that change a resolved set |
| `ALBUM_ROWS` | `albums` | Album summary/track-count changes used by album rules |
| `PLAYLIST_ENTRIES` | `playlist_items` | Playlist entries, positions and removals |
| `QUEUE_ROWS` | `download_queue` | Enqueue, claim, settle, cancel and completion |
| `OFFLINE_ROWS` | `offline_media` | Publication, deletion and provider cascades |

The existing library invalidation flow covers catalog screens, not these six
tables. Room re-runs each query on any write to its table, so the count is only
a cheap emission token: an update or delete that keeps the count unchanged
still emits. The initial emission at subscription is deliberately treated as a
trigger so a session start reconciles the current state. No schema object is
added by these observers.

Favorite toggles, song edits and album changes are therefore first-class
triggers, not something that only surfaces at the next session. A sync's own
`songs`/`albums` writes also emit; the sync gate below keeps those passes from
publishing a partial generation.

### Trigger ordering and coalescing

[VERIFIED] `AutomaticRuleReactionCoordinator.run()` collects the merged table
flow, `LibrarySyncStatusRepository.completions` and a public `requestPass()`
into one `Channel.CONFLATED` request channel. A single loop consumes requests
and `runPass()` is additionally guarded by one mutex, so:

- observer emissions and explicit requests are serialized; two callers can
  never expand concurrently;
- a burst of any number of changes while a pass is running leaves at most one
  pending pass, never one pass per change;
- a request arriving between passes collapses into the next receive, so a
  quiet database eventually gets one pass, not a replay of every write;
- trigger order across tables is not meaningful (they are level signals); the
  rules themselves are always read in the repository's deterministic
  `providerId, kind, targetExternalId, id` order.

The loop does not retry a failed or incomplete pass internally. It waits for
the next trigger; a rule change during a pass requests one fresh pass explicitly,
and a sync that blocked a pass is retried by the sync-completion trigger below.

### Sync yield and completion

[VERIFIED] `LibrarySyncStatusRepository.isRunning(providerId)` is consulted for
the providers of the current rule snapshot twice: before expansion and again
immediately before hand-off. If a sync is running, the state becomes stale with
`SYNC_RUNNING` and the pass returns without publishing. It does not poll: the
repository emits the finished provider id on `completions`, which requests a
fresh pass, so a pass that yielded to a sync can never depend on an unrelated
table event to run again. A pass therefore never publishes while an involved
sync is running, and a sync that starts mid-pass is retried as soon as it
finishes. Cancellation of the service session cancels the loop and any pass.

The gate covers the providers named by the current rules (none when no rules
exist), so a sync for an unrelated provider does not mark the state stale. It
is a yield, not a sync driver: task 8a does not start a sync. Completion of any
provider requests one coalesced pass; with no rules that pass publishes an empty
verified generation, which is harmless.

### Wanted union and the offline-presence skip

[VERIFIED] Each rule contributes the media keys of its complete resolved
snapshot. The generation's `wantedMediaKeys` is the union across all current
rules, so overlap is resolved by the same `DownloadKeys.mediaKey` identity the
queue uses; `autoRuleId` is never consulted for ownership.

Before enqueueing, the coordinator reads the `offline_media.sourceKey` set for
the rule providers. Expansion still counts those tracks as wanted (so task 8b
must not prune them) but does not enqueue them, because completion of an
automatic download deletes its queue row and publishes the offline row with the
same key. Without this skip, the queue/offline observers would immediately
re-enqueue the completed track forever. The skip is by exact variant key and by
row presence only; file verification is task 5b's sweep, and replacement
retention/pruning is task 8b's. Deleting the offline row makes the next pass
enqueue the track again.

Visibility scope is UI-only: every rules, catalog and offline read in the
reaction path uses `ProfileScope.Unrestricted`, so hiding a provider cannot
shrink the internal wanted set.

### Generations and the stale-generation invariant

[VERIFIED] `AutomaticRuleGenerationStore` holds one process-wide
`StateFlow<AutomaticRuleGenerationState>`. A generation is published only when
all of the following hold in one pass:

1. the rule read succeeded;
2. every rule expanded to `EXPANDED` (a genuinely empty favorites/playlist/
album is complete and empty);
3. the rule list re-read after expansion is exactly equal to the snapshot
   (add, remove or variant change during the pass is `RULES_CHANGED`);
4. no involved provider sync is running at the final check.

A published generation carries a monotonically increasing `sequence`, the
verification time, the rule count, the wanted media-key union, the provider set
and the per-rule expansions. Otherwise the store keeps the last generation for
diagnostics but marks the state `verified = false` with one of
`RULE_READ_FAILED`, `INCOMPLETE_EXPANSION`, `SYNC_RUNNING` or `RULES_CHANGED`.
`PASS_FAILED` covers an unexpected exception in the pass.

[VERIFIED] The invariant task 8b inherits: **only a state with
`verified = true` may authorize cancellation, deletion or replacement.** A
stale state's retained generation is not authority to prune; it may be older
than the database. An empty verified generation is different from a stale one:
zero current rules really means nothing is wanted. `STALE_TARGET`, `UNSYNCED`
and `READ_FAILED` are incomplete expansions, so they mark the state stale rather
than publishing a smaller wanted set.

### Production ownership

[VERIFIED `:app:assembleDebug`] `AutomaticRuleReactionFactory` builds the
coordinator around the foreground session's one
`DownloadWorkerCoordinator`, the production `ProviderRunGate` and
registry-backed `ProviderTraits`; `ForegroundDownloadRuntime` starts the loop
only after startup recovery and stops it with the session. The generation store
is a Hilt singleton, so a verified generation survives a later session for
task 8b. Observers therefore run while the download service session runs; a
rule edit or UI commit wakes the service through the existing
`DownloadWorkStart` seam (task 9) rather than through a second trigger bus.

A session also runs one initial pass inline (`runInitialPass`) before its
worker loop, deliberately awaited: a rule-triggered service start may have no
queue work yet, and letting the loop idle first could stop the session before
its rules expanded. The runtime's connectivity callback both wakes workers and
requests a pass, because waking a worker cannot create the rule rows a blocked
expansion never committed.

## The v2 → v3 migration

[VERIFIED] `Migrations.MIGRATION_2_3` is additive and creates only this table
and index; `OmnisonicDatabase.SCHEMA_VERSION` is now `3` and
`Migrations.ALL = listOf(MIGRATION_1_2, MIGRATION_2_3)`. Room exported
`3.json`; `1.json` and `2.json` are untouched. The migration uses the exact
create SQL and index SQL Room generated, so the harness validates the migrated
database against the export.

`MigrationHarnessTest` covers:

1. the v3 baseline and the two ordered steps;
2. a v1 seed upgraded through `1 → 2 → 3` with the offline read model and
   queue rows intact;
3. a seeded v2 database upgraded to v3: existing offline and queue rows keep
   their values, the rule table is writable, the unique index rejects a
   duplicate, and the favorites sentinel row is stored as `''`;
4. the production factory reopening a v1 database and installing every
   cascade trigger, including the rule trigger.

## Evidence

[VERIFIED JVM 2026-11-21] Task 8a (delivery): `:core-data` **748/0/0/0**,
including `AutomaticRuleReactionTest` **16** (up from 732); `:feature-downloads`
**22/0/0/0**, including the runtime wiring case (`ForegroundDownloadRuntimeTest`
**17**); `:core-data:lintDebug`, `:feature-downloads:lintDebug` and
`:app:assembleDebug` exit 0. The earlier task-7b baseline was `:core-data`
**732/0/0/0** with `AutomaticRuleRepositoryTest` **9**, `MigrationHarnessTest`
**5** and `AutomaticRuleExpanderTest` **16**.

Task-8a tests cover every watched table's insert/update/delete emission
(including `songs`/`albums` after the audit), favorites add/change/remove;
playlist entry addition/removal; album track addition and rule removal;
overlap; the offline-presence skip and its release when the offline row
disappears; sync running at start and starting mid-pass with fresh recompute; a
sync completing without any table write; a rule added mid-pass; empty verified
versus failed-read stale; hidden providers; a 505-track snapshot; a 20-request
burst producing one follow-up pass; concurrent-pass serialization; and
cancellation of an in-flight pass. The runtime tests prove a rule added during a
live download session expands with `tempCache = true`, a rule-only session
expands and claims before it can idle, and connectivity re-expands an automatic
rule blocked by the provider gate.

[VERIFIED JVM 2026-11-21] Task 8b (delivery): `:core-data` **764/0/0/0**,
including `AutomaticRulePruningTest` **15** and `AutomaticRuleExpanderTest`
**17** with the conflict-ownership case; `:feature-downloads` **22/0/0/0**,
where the live session test adds a rule, observes its rolling row and removes
the rule so the row is cancelled. Task-8b tests cover no/stale generation, all
five queue statuses, the active cancel signal, manual/permanent/export/wanted/
other-provider protection, an unavailable root, a failed file delete followed
by a successful retry, a missing file, replacement-old retention through a
failed replacement then pruning after publication, unparseable identity
retention, sync at pass start and starting between provider groups, a rule
change after publication, provider deletion/cascade, and cancellation
propagation.

[VERIFIED JVM 2026-11-21] Task 7–9 audit gate (fix-and-stop path):
`:core-data` **781/0/0/0** (`AutomaticRuleReactionTest` **18**,
`AutomaticRulePruningTest` **19**, `DownloadWorkerCoordinatorTest` **43**),
`:feature-downloads` **31/0/0/0** (`ForegroundDownloadRuntimeTest` **19**),
`:app` debug **169/0** + release **41/0**. The audit added RED-confirmed
regressions for: a favorite edit, an album track and a sync completion each
starting a fresh pass without a manual request; a queue row converted to an
export destination and an offline row pinned during the same pass both
surviving; an offline row whose parsed key names another provider being
retained; a rule-only session expanding before it can idle; and removing one of
two overlapping rules pruning only that rule's unshared queue/offline rows.

[VERIFIED JVM 2026-11-21] Full serial gate: `./gradlew --no-parallel
:app:assembleDebug test lint` exit 0. XML totals: **1,777** debug results
(1,777 tests, 4 skipped, 0 failures, 0 errors) and **1,649** release results
(4 skipped, 0 failures, 0 errors); the 128-result difference is the documented
`app` release exclusion of `**/shell/**`. The four skipped cases per variant
are the gated live Navidrome acceptance tests. Banned-name scan over all 79
touched files exit 0; no new public declaration has an exact theme-key or
distinctive `THEMING.md` §4 kept-model/config-name collision (only permitted
generic property words overlap); schema remains v3 with `3.json` exported
and `1.json`/`2.json` untouched. TODO 10 reproduced the same gate and consolidated the overlap/cancellation evidence boundaries in `download-acceptance.md`.

Task-7a tests cover all three kinds, round-trip, duplicate no-op,
cross-provider/cross-kind identity, blank-target and invalid variant rejection,
scoped variant updates, `ProfileScope` on every read, catalog
deletion/recreation, remove/re-add identity, and the provider cascade.

Task-7b tests cover favorites/track filters and variant, playlist position
order and duplicate entries, album disc/track order, empty valid collections,
missing provider/playlist/album, incomplete playlist/album as a whole-snapshot
`UNSYNCED` skip, overlapping rules, a repeated pass, manual active-row progress
preservation, an existing export-destination conflict, unsupported providers,
offline/auth gates, catalog read failure
with the prior queue intact, changed collections, different songs keeping their
own variant identities, a hidden provider under a real scope, and a 505-track
album snapshot. No device behaviour is involved; automatic reactions and pruning
are tasks 8a/8b.
