# Style loader — `style-loader.md`

Purpose: pin how Omnisonic stores and loads style profiles — the file-backed repository over built-in assets and app-private `styles/<id>.json` files, its atomic write, its read path through the migration chain and decoder, its deterministic handling of duplicate, unreadable and unparsable documents, how a profile merges over the built-in it derives from, the three reset levels, the fallback when the active profile cannot be used, and the persisted active profile that observation, switching and delete-active share.

How derived: own design for Phase 9 TODOs 2a–2c (`.agents/plans/phase-9-tasks.md` TODO 2a–2c, §Architecture "Merge order" and "Persistence"), constrained by the schema v1 document contract in `style-schema.md` §1/§2/§6/§7 (D-64/D-67/D-68/D-69/D-70) and the processing-boundary table in `style-format.md` §4. The repository, merger, resolver and active-profile store reuse `:core-model`'s `StyleMigrator`, `StyleDecoder`, `StyleWriter` and `StyleNormalizer`; they introduce no second parser and no style value of their own.

Confidence: `[VERIFIED]` against the repository source and the `StyleRepositoryTest` cases for the read/write/delete/list paths, atomic replacement, temp-file leftovers, typed failures, duplicate resolution, shadow removal and id validation, against `StyleMergerTest`/`StyleProfileResolverTest` for the merge, reset, base-identity and fallback behavior, and against `StyleProfileStoreTest` for persistence, single emission, merge-based switching, write observation, operation serialization, delete-active and read-failure handling, and against the TODO 4R2 asset-failure cases for throwing/null enumeration and reads in `StyleRepositoryTest`/`StyleProfileResolverTest`. `[INFERRED]` for the production asset delivery form (TODO 4a picks it), for the app-layer wiring (`context.filesDir`, one DataStore instance per file) and for the caller-supplied derivation base. `[UNKNOWN]` for every on-device result, for backup-ZIP interaction (TODO 5e) and for how TODO 5b persists a non-default derivation base (§2.1 risk).

Date: 2026-11-24

Links: `style-schema.md` (§1 Model, §2 Defaults, §6 Normalized form, §7 Versioning), `style-format.md` (§3.4, §4), `style-editor.md` (§Session core), `decisions.md` D-64/D-67/D-68/D-69/D-70/D-71/D-72/D-73/D-82/D-83/D-85/D-86/D-103/D-106/D-108; `.agents/plans/phase-9-tasks.md` TODO 2a–2c, TODO 4R2, TODO 5a0.

---

## 1. Storage

### 1.1 Where documents live

| Kind | Location | Owner | Evidence |
|---|---|---|---|
| Built-in style | read-only app assets, one JSON document per asset | TODO 4a ships them | [VERIFIED — plan §Persistence] |
| User style | `<app files dir>/styles/<id>.json` | this repository | [VERIFIED — plan §Persistence, D-71] |

- [VERIFIED — source] `StyleRepository(stylesDir, assets, logger, migrator, replace)` takes its directory and its asset source as constructor arguments. It holds no `Context`, writes no DataStore key and references no Android type, so it is exercised on the JVM against a temporary directory.
- [VERIFIED — source] `StyleRepository.STYLES_DIRECTORY = "styles"` is the directory name; the app layer resolves it under its private files directory (`[INFERRED]` wiring: `File(context.filesDir, StyleRepository.STYLES_DIRECTORY)`, TODO 2c/4a). Nothing outside that directory is written.
- [VERIFIED — source] Built-ins are matched by the **document `id`**, not by asset file name, and the asset list is loaded lazily once per repository instance. TODO 4a therefore keeps its freedom over asset naming; a name repeated in `assets.names()` is not an error, but two assets declaring one id are resolved deterministically (§1.4).
- [VERIFIED — source/test] An asset that is missing (a `null` read), unreadable (an `IOException` or `SecurityException` from the read) or invalid is skipped with one warning per asset; it never prevents the remaining built-ins from loading and never crashes a list or a read.
- [VERIFIED — source/test] Enumeration sits inside the same boundary (TODO 4R2): `assets.names()` throwing `IOException` or `SecurityException` yields the **empty built-in set with exactly one warning**, so `list()` still returns every usable user style and `read()`/`readText()` resolve a user id or return `NotFound` instead of throwing. A degraded built-in set is a logged partial set, never a crash and never a hidden user style.

### 1.2 Identity

- [VERIFIED — source] A user file's identity is its **file name**: `styles/<id>.json` is document `id`. A `read(id)` that finds a document declaring another `id` returns `Rejected` with `id: does not match the file name '<id>'`, and `list()` skips the file with one warning. [INFERRED] Silently honouring the declared id would let two paths claim one style and would make a list entry unopenable.
- [VERIFIED — source] `id` must be a single path segment: `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`, and neither `.` nor `..`. Every read, write and delete validates it first and returns `InvalidId`, so an id-shaped path (`../escape`, `/tmp/evil`, `a/b`, `..\evil`) can never reach a file outside `styles/`. This is the only traversal guard, and it is checked before any filesystem call.
- [VERIFIED — source] Integration through the schema's identity rule (D-67/D-68: non-blank required `id`) is by containment: every id the repository accepts is a valid file name, and a document the decoder rejects is never stored.

### 1.3 Read path

[VERIFIED — source] `read(id)` is: validate the id → look for `styles/<id>.json` → if absent, look for a built-in whose document id matches → otherwise `NotFound`. A present user file always wins over a built-in with the same id, including when the user file is invalid: a corrupt file is reported, never silently replaced by the built-in. (Fallback policy for the *active* profile is TODO 2b.)

Stored text goes through exactly one pipeline:

1. `StyleMigrator.migrate(text)` — the chain reads `schemaVersion` and runs ordered steps up to this build's version; a failure returns `Failed` and no text.
2. `StyleDecoder.decode(migratedText)` — the only parser; a rejection returns `Rejected` with every path-qualified message.

| Result | Meaning |
|---|---|
| `Loaded(document, warnings, origin)` | valid document; `origin` is `USER` or `BUILT_IN`; `warnings` are the decoder's unknown-key warnings |
| `Rejected(id, errors)` | migration or validation failed; `firstMessage` is the editor line |
| `Unreadable(id, detail)` | the file exists but could not be read, or is not a regular file — distinct from a validation rejection because nothing about the content was learned |
| `InvalidId(id)` | the id cannot address a file in `styles/` |
| `NotFound(id)` | neither a user file nor a built-in carries that id |

- [VERIFIED — source/test] A future `schemaVersion` reaches the migrator's loud failure (`schemaVersion: unsupported schema version N; this build reads version 1`) and is reported as `Rejected`; the repository adds no version branch and no fallback (`style-schema.md` §7).
- [VERIFIED — source/test] `StyleRepositoryTest.migrated text is decoded after the chain runs` proves the order: a synthetic 1→2 step with `targetVersion = 2` rewrites the version, and the repository then rejects the output with the version named — evidence that migrated text, not the original file, reaches the decoder.
- [VERIFIED — source] The repository performs no normalization on read: the document is returned as decoded.

### 1.4 Listing

[VERIFIED — source] `list(): List<StyleSummary>` is built-ins first in asset-name order, then user files in file-name order; each entry carries the id, the document name and the origin.

- [VERIFIED — source/test] A user file with a built-in's id **shadows** it: its built-in summary is removed **before** the user file is read or decoded, so the list holds one entry with origin `USER` when the file is valid, and **no** entry when the file is corrupt, unreadable, declares another id or otherwise fails — never a stale built-in summary for a file `read` would reject. One warning names the skipped file.
- [VERIFIED — source/test] Two built-in assets declaring one id keep the first in asset-name order and log one warning; the list never contains the same id twice.
- [VERIFIED — source/test] A user file that cannot be read or decoded is skipped with **one** warning naming the file, and the rest of the list still renders. Listing is therefore total: it always returns a list.
- [INFERRED] Files under `styles/` that are not `*.json` are ignored without a warning; they are not styles. `*.json.tmp` never matches the suffix, so a temp leftover is not listed either.

### 1.5 Write path

[VERIFIED — source] `write(document)` is: validate the id → `StyleWriter.write(document)` → create `styles/` if needed → write the text to `styles/<id>.json.tmp` in the **same** directory → `flush()` and `FileDescriptor.sync()` → rename the temp over `<id>.json`.

| Result | Meaning |
|---|---|
| `Written(text)` | the normalized text is the file's complete content; `text` is the same bytes export would carry |
| `Rejected(errors)` | the writer refused the document (out-of-range field, blank name, non-finite number); nothing was created |
| `Unwritable(id, detail)` | the directory, the temp file or the replacement failed; the previous document is intact |
| `InvalidId(id)` | the id cannot address a file in `styles/` |

- [VERIFIED — source/test] The temp file lives in the target directory, so the rename never crosses a filesystem; the rename is the commit point. A failure before it leaves the previous `<id>.json` untouched and deletes the temp file.
- [VERIFIED — source/test] `StyleRepositoryTest.a failed replace keeps the previous file intact` injects a failing replacement and asserts the old document is still readable and only `alpha.json` remains — the interrupted-write case.
- [VERIFIED — source/test] The stored bytes are byte-identical to `StyleWriter.write(document).text`, i.e. the canonical normalized form (`style-schema.md` §6). The repository never writes input text verbatim, and unknown keys cannot survive a write.
- [VERIFIED — source] `replace` is a constructor function (`(temp, target) -> Boolean`) defaulting to `renameTo`, mirroring the existing injected-probe convention in `:core-data`; it exists so the commit step is testable without a real interruption.
- [VERIFIED — source] Writes are not serialized across threads by this type. [INFERRED] Callers are the editor's save path (TODO 5) and the app-layer import/restore (TODO 5e), which are single-writer; TODO 2c owns sequencing the active-profile switch.

### 1.6 Delete path

- [VERIFIED — source/test] `delete(id)` removes exactly `styles/<id>.json` and returns `Deleted`, `NotFound`, `Unwritable` or `InvalidId`. No other file, temp leftover or built-in is touched — a built-in is never deletable through this call.
- [VERIFIED — source] The directory itself is never removed, so a delete cannot make a later read fail for a different reason than "not found".

### 1.7 Duplicate and leftover handling

| Situation | Behaviour | Evidence |
|---|---|---|
| User file shadows a built-in id | user wins in `list` and `read`; built-in asset untouched; the built-in summary is removed even when the user file fails | [VERIFIED — test] |
| Two built-in assets, one id | first asset name wins; one warning | [VERIFIED — test] |
| Two user files, one id | impossible as a *file name*; a file declaring another id is rejected in `read` and skipped in `list` | [VERIFIED — test] |
| `*.json.tmp` leftover | ignored by `read` and `list`; removed by the next write of that id | [VERIFIED — test] |
| Unparsable user file | `list` skips with one warning; `read` returns `Rejected` | [VERIFIED — test] |
| Non-regular file at `<id>.json` | `read` returns `Unreadable`; `list` skips it | [VERIFIED — test] |
| Asset enumeration throws `IOException`/`SecurityException` | empty built-in set, one warning; user styles still list and read | [VERIFIED — test] |
| Built-in asset read returns `null` or throws `IOException`/`SecurityException` | that asset is skipped with one warning; the rest load | [VERIFIED — test] |

## 2. Merge

[VERIFIED — source] `StyleMerger.merge(base, overlayText)` deep-merges the profile's JSON tree onto the built-in document's tree and decodes the result once. `base` is a fully resolved [VERIFIED — source] `StyleDocument` (the built-in the profile derives from); `overlayText` is the profile text as returned by `StyleRepository.readText`.

| Rule | Behaviour | Evidence |
|---|---|---|
| Object at every depth | keys merge one by one; a profile key wins, an absent key keeps the base value | [VERIFIED — test] |
| Array or scalar | replaces the base value wholesale; `home.sections` and `nowPlaying.*.blocks`/`buttons` are units, never element-merged | [VERIFIED — test] |
| Envelope keys | `schemaVersion`/`id`/`name` in the profile override the base | [VERIFIED — source] |
| Unknown profile key | reaches the decoder, which warns (`unknown key dropped`) and drops it; the merge still succeeds | [VERIFIED — test] |
| Wrong type / range / token | the whole merged text is rejected with the path-qualified message | [VERIFIED — test] |

- [VERIFIED — source/test] Merging happens **before** decoding because the decoder resolves an absent nested field to the model default (D-68), which is not the base style's value. On the tree, `{"app":{"cornerScale":"MEDIUM"}}` over a base with `darkMode: DARK` yields `MEDIUM` + `DARK`, and a profile that names only one orientation of a pair (`pages.albums.columns.portrait`) keeps the base's other orientation instead of failing the decoder's "a present pair needs both" rule.
- [VERIFIED — source] `StyleRepository.readText(id)` returns the same migrated text `read` would decode (same id validation, file-or-built-in resolution and migration chain; only the decode step is skipped), so a merge input and a read can never disagree about which bytes a profile has.
- [VERIFIED — source/test] `read` is unchanged: it still returns the document exactly as stored, with no merge and no reset.

### 2.1 Reset levels

| Level | Function | Result | Evidence |
|---|---|---|---|
| Field | `resetField(profile, base, path)` | the base document's node at a dotted path replaces the profile's node, then the text is re-decoded; other profile overrides stay | [VERIFIED — test] |
| Section | `resetSection(profile, base, StyleSection)` | that one section (`APP`/`HOME`/`PAGES`/`NOW_PLAYING`) becomes the base's; other sections stay | [VERIFIED — test] |
| All | `resetAll(profile, base)` | `app`/`home`/`pages`/`nowPlaying` become the base's; `id`, `name` and `schemaVersion` stay the profile's | [VERIFIED — test] |

- [VERIFIED — source/test] `resetField`'s path uses the decoder's vocabulary (`app.effects.overlayAlpha`, `pages.albums.columns.portrait`, `home.sections`), so a field reset can also restore one orientation or a whole list section. A path the base does not carry, a blank path, or an envelope key (`schemaVersion`/`id`/`name`) returns `UnknownPath` and changes nothing.
- [VERIFIED — source] A reset is pure: each function returns a new `StyleDocument`, and no function reads or writes the filesystem.
- [INFERRED — plan §Merge order] "Reset all restores the built-in the profile derives from" is expressed by the caller passing that built-in as `base`. Schema v1 has no derivation pointer, so the repository/resolver never guesses one; TODO 5b picks the base when it copies a built-in into a user style.
- [KNOWN RISK — D3] `StyleProfileResolver` today derives every non-null active id over the configured default built-in and reports that base id. Activating another **built-in** is safe in practice because shipped built-ins are complete documents (their keys replace the default's on merge), but a user profile derived from a non-default built-in cannot restore that base after process death: neither schema v1 nor the `omnisonic_style` pointer stores the derivation. TODO 5b must either store the derivation outside the schema or copy the whole resolved base into the user document; until then this is a documented compatibility risk, not a TODO 1–3 defect.

## 3. Fallback

[VERIFIED — source] `StyleProfileResolver(repository, defaultStyleId, logger).resolve(activeId)` returns `Resolved(document, baseId, warnings, fellBack)` or `Unavailable(errors)`.

| Active-profile state | Result | Warning | Evidence |
|---|---|---|---|
| `activeId == null` (nothing chosen yet) | default built-in, `fellBack = false` | none | [VERIFIED — test] |
| Named profile file absent (`NotFound`) | default built-in, `fellBack = true` | exactly one | [VERIFIED — test] |
| File unreadable, or migration rejected | default built-in, `fellBack = true` | exactly one | [VERIFIED — test] |
| Merged document rejected (invalid value) | default built-in, `fellBack = true` | exactly one | [VERIFIED — test] |
| Document declares another id | default built-in, `fellBack = true` | exactly one | [VERIFIED — test] |
| Default built-in declares another id (a same-id user shadow) | `Unavailable(errors)`; null active id and named profiles both fail | exactly one | [VERIFIED — test] |
| Default built-in itself unavailable | `Unavailable(errors)`, no crash | exactly one | [VERIFIED — test] |
| Built-in asset source cannot be enumerated (default included) | `Unavailable(errors)`, no crash | one for the enumeration failure plus one for the unavailable default | [VERIFIED — test] |

- [VERIFIED — test] The asset source is allowed to fail at the JVM boundary: an `IOException`/`SecurityException` from enumeration leaves the built-in set empty, so a default read becomes `NotFound` and `resolve` returns typed `Unavailable(errors)` instead of propagating. A single failing asset read leaves the other built-ins and every user style usable.
- [VERIFIED — source/test] The warning names the active id, the machine-readable reason (first path-qualified message or typed read detail) and the default id it fell back to. One `resolve` call logs at most one warning for the profile and one for an unavailable default.
- [VERIFIED — source] `resolveCandidate(id)` applies the same base read, identity check, merge and decode as `resolve`, but never falls back: a candidate that cannot be applied returns `Unavailable(errors)`. The active-profile store uses it for switching, so acceptance cannot disagree with what observation would show.
- [VERIFIED — source] The base read applies the identity contract to itself: text read for `defaultStyleId` must decode to a document declaring that id. A same-id user file that declares another id makes the base unavailable instead of silently becoming the base.
- [VERIFIED — test] A corrupt profile file is never deleted, renamed or overwritten: the fallback only returns the built-in document, so the file stays on disk for inspection and the editor can show its messages.
- [VERIFIED — source] A successful resolve merges the profile over the built-in and reports the base id in `baseId`; decoder warnings (unknown keys in either document) are passed through.
- [VERIFIED — source] This component holds no DataStore key and no observation: `StyleProfileStore` (§4) owns the persisted active id and feeds it to `resolve`.

## 4. Active profile

[VERIFIED — source] `StyleProfileStore(dataStore, repository, resolver, logger)` owns the active-profile pointer in the `omnisonic_style` preferences DataStore (`DATASTORE_NAME`) under `style.active_id`. `create(context, …)` resolves the file with `preferencesDataStoreFile`, `createAt(file, …)` and `inMemory(…)` mirror the other `:core-data` stores; the class holds no `Context` field and no style value of its own.

| Behaviour | Result | Evidence |
|---|---|---|
| Observe | `activeId: Flow<String?>`; absent key is null | [VERIFIED — source/test] |
| Resolve | `activeProfile = combine(activeId, repository.changes) { id, _ -> resolver.resolve(id) }.distinctUntilChanged()` — §3 on every selection or successful repository change | [VERIFIED — source/test] |
| Switch | `switch(id)` resolves the candidate through the resolver's merge/identity contract under the operation mutex, then writes the id | [VERIFIED — source/test] |
| Delete | `delete(id)` delegates to `StyleRepository.delete` under the same mutex and clears the pointer only for a gone active file | [VERIFIED — source/test] |
| Mutation snapshot | `snapshotActiveId()` is a raw DataStore read with **no** observation fallback, returning `ActiveIdSnapshot.Known(id)` or `ActiveIdSnapshot.Unreadable(detail)`; `delete(id, activeAtStart)` clears the pointer based on a snapshot captured **before** the repository deletion | [VERIFIED — source/test, D-113] |
| Read failure | one warning, then the default built-in, collection continues | [VERIFIED — source/test] |

- [VERIFIED — source/test] `activeProfile` is observation through the same resolver every other reader uses: a successful switch emits the merged document (§2), while a missing, unreadable or invalid active file emits the default built-in with §3's exactly one warning. The store adds no fallback of its own.
- [VERIFIED — source/test] A successful `StyleRepository.write` or `delete` bumps the repository's change signal, which re-runs §3 for the persisted id: editing the **active** file re-emits the changed document, and editing an inactive file re-resolves to an equal state that `distinctUntilChanged` suppresses. A failed write bumps nothing.
- [VERIFIED — source/test] `switch(id)` uses `StyleProfileResolver.resolveCandidate(id)`, which applies the same base read, declared-id check, merge and decode as observation. A candidate that only becomes valid through the merge (for example a partial orientation pair inheriting the other orientation from the base) is accepted; a candidate observation would reject is rejected with its path-qualified errors and leaves the pointer, the active profile and every file untouched. A valid built-in id can be made active the same way.
- [VERIFIED — test] The switch emits exactly one new resolved document and the id survives a new store instance over the same preferences file; a later switch does not rewrite or delete the previous profile file.
- [VERIFIED — source/test] `delete(id)` calls `StyleRepository.delete(id)` and, only when the outcome is `Deleted` or `NotFound` **and** the deleted id is active, removes `style.active_id`. The next emission therefore resolves the default built-in (`fellBack = false`, no warning), which is the plan's delete-active return. An `Unwritable` or `InvalidId` delete keeps the pointer, and deleting a non-active style never touches it.
- [VERIFIED — source/test] `switch` and `delete` hold one operation-level `Mutex` for their complete compound operation, so a switch cannot validate a candidate that a concurrent delete is removing, and a delete cannot clear an id a concurrent switch just wrote. The gated concurrency test proves the switch/delete ordering leaves no deleted style active.
- [VERIFIED — source/test] A DataStore read failure is caught once, logged as one warning and replaced by `emptyPreferences`, so the id reads as null and `activeProfile` resolves the default built-in instead of failing the collector. An unavailable default still surfaces as §3's `Unavailable`, never a crash. This observation fallback is unchanged by D-113: it applies only to observation, never to a destructive mutation.
- [VERIFIED — source/test, D-113] Mutation reads the pointer differently. `snapshotActiveId()` collects the raw DataStore flow under the operation mutex and returns `ActiveIdSnapshot.Known(id)` — or `ActiveIdSnapshot.Unreadable(detail)` when the read throws, with no fallback to null. `delete(id, activeAtStart)` takes that pre-captured snapshot and clears `style.active_id` only when the deleted id was active at snapshot time; the coordinator calls it only after a `Known` snapshot, so a pointer read failure aborts the delete with `Failed` **before** the repository deletion, and before the derivation entry is touched. The single-argument `delete(id)` keeps the observation-fallback read for non-coordinator callers and existing tests; the coordinator never uses it.
- [INFERRED] The pointer is cleared after the file is gone (never before), so an emission interleaved with a delete falls back through §3 rather than naming a file that no longer exists. Sequencing is single-writer through the operation mutex.
- [INFERRED] `create` builds the DataStore; the app layer must call it once per process for `omnisonic_style` (DataStore forbids two active instances over one file). Hilt binding is left to the child that has both the files directory and the asset source (TODO 4a).

## 5. Boundaries

- [VERIFIED — source] No active-profile key or observation existed in TODOs 2a/2b; TODO 2c adds them in `StyleProfileStore` (§4) without changing `read`/`write`/`delete`, merge or reset. TODO 2d adds the per-key settings facade in `app.omnisonic.core.data.settings.SettingsFacade` (`settings-facade.md`) — it reads the five settings DataStores only and carries no style value, so nothing here changes. No `StyleTokens` wiring (TODO 4), no UI and no `:style` change. `read` still returns the document exactly as stored.
- [VERIFIED — source] No schema v1 widening, no change to `StyleDecoder`/`StyleWriter`/`StyleMigrator`/`StyleNormalizer` contracts (D-68/D-69/D-70), and no Room change. `:core-data` gains a new package `app.omnisonic.core.data.style` and no dependency.
- [VERIFIED — source] No Hilt module is added: the repository needs the app's files directory and its asset source, so binding it belongs to the child that first has both (TODO 2c/4a). Every dependency is a constructor parameter.
- [VERIFIED — test] Evidence: `StyleRepositoryTest` — 17 cases: write→read round-trip, canonical bytes on disk, replacement leaves one file, failed replace keeps the previous document, temp leftovers ignored, unreadable file typed failure, delete one id, unparsable listing with one warning, hostile id rejection, built-in list/read, user shadowing, duplicate built-in ids, declared-id mismatch, migration-before-decode, writer rejection without a file, plus corrupt-shadow and wrong-id-shadow summary removal. `StyleMergerTest` 12 covers precedence, fall-through, list replacement, warnings/rejection and the three reset levels; `StyleProfileResolverTest` 9 covers the silent default, each fallback reason, the kept corrupt file, an unavailable default, the base declared-id mismatch, and the candidate merge path; `StyleProfileStoreTest` 13 covers fresh default, persistence, single-emission switch, invalid and missing rejection, delete-active/inactive/failed, active-write observation, inactive-write suppression, partial-pair merge acceptance, gated switch/delete serialization and read-failure fallback. Serial gate results are recorded in `phase-9-task-1-3-audit-gate.log` / `phase-9-task-1-3-audit-xml-totals.txt`.
- [UNKNOWN] On-device read/write timing, DataStore size/startup behavior, backup-ZIP restore interaction (TODO 5e) and the production asset source implementation (TODO 4a). The `omnisonic_style` key and observation are JVM-verified in §4.

## 6. Mutation and ordering

[VERIFIED — source/test] `StyleMutationCoordinator(repository, profileStore, derivations, logger, defaultStyleId, clock, newId)` (`core-data/.../style/StyleMutationCoordinator.kt`, D-108) is the one ordered boundary for every style write and active-pointer change. One `Mutex` covers each command from its read through its commit and its companion steps, so a save, reset, rename, duplicate, delete, switch, import write and restore write cannot interleave. The coordinator adds no second parser, validator or fallback: it reuses `StyleRepository` for bytes, `StyleProfileStore` for the pointer and `StyleMerger` for resets.

| Command | Contract |
|---|---|
| `create(document, baseId, activate)` | refuses an occupied or invalid id, validates the base exists, writes, records the derivation entry, then optionally activates |
| `update(id, expectedRevision, transform)` | reads the document, checks the revision, applies `transform` to that **latest committed** document inside the lock, refuses an id change, writes |
| `rename(id, expectedRevision, name)` | `update` with a name-only copy |
| `duplicate(id, activate)` | copies the source into a fresh generated id, records the source's base (or its built-in/default with one warning) |
| `reset(id, expectedRevision, scope)` | field (`resetField`), section (`resetSection`) or all (`resetAll`) from the derived base; unknown paths are refused, never a silent no-op |
| `delete(id)` | snapshots the active id via the store's mutation-only read and aborts with `Failed` if it is unreadable; otherwise deletes the user file through the store (which clears the pointer for an active id captured before the deletion), then clears the derivation entry |
| `switch(id)` | the store's validated switch; a DataStore write failure is a typed `Failed` |
| `importWrite(document, baseId)` | D-83 collision resolution and the derivation entry, never an activation |
| `restoreWrite(document, baseId, activate)` | the same collision resolution and derivation entry, then activation only when the caller has already restored its data (D-85/D-86) |

### 6.1 Revision

[VERIFIED — source/test] `StyleRevision` is the SHA-256 of a document's canonical text (`StyleWriter`). Only committed bytes produce a revision, so an external write, restore or delete that changes the file changes the revision without any counter to persist. Every command against an existing style carries the revision the caller last observed; a mismatch returns `StyleMutationResult.StaleStyle(id, expected, actual)` and writes nothing. This is F-26's guard against a queued editor transform landing on a document that an external write replaced.

### 6.2 Derivation metadata

[VERIFIED — source/test] `StyleDerivationStore` maps a custom id to the built-in base id it was copied or imported from (D-82). `FileStyleDerivationStore` keeps one JSON object in a same-directory temp file, `flush()` + `fd.sync()` + rename, so an interrupted metadata write keeps the previous entries; the file lives beside the private files directory, never under `styles/`, so `list()` cannot mistake it for a style. The entry is set on create/import/restore and cleared on delete. A missing entry is never resolved to a guessed built-in: `duplicate` and `reset` log one warning and fall back to `defaultStyleId` explicitly.

### 6.3 Failure truthfulness

[VERIFIED — source/test] Results are typed: `Saved`, `Deleted`, `Switched`, `StaleStyle`, `NotFound`, `Rejected`, `Failed` and `Partial`. Only the first three are success shapes, so a caller cannot push an Undo entry for a write that did not land. The file commit is the point of no return; the derivation entry and the pointer are ordered **after** it and their failure returns `Partial` naming which part is true — for example a style saved with activation refused (`saved inactive`), or a file deleted but the active pointer stuck. A DataStore switch failure changes nothing; a DataStore delete failure after the file is gone is reported as partial, never as `Deleted`. [VERIFIED — source/test, D-113] The point of no return is protected from the other side as well: `delete` reads the active id through `StyleProfileStore.snapshotActiveId()` — a raw read without the observation fallback — **before** the repository deletion, and returns `Failed` without deleting any file or derivation entry when the pointer cannot be read. A later pointer-**write** failure after a successful file delete remains `Partial` (D-108); only a pointer-**read** failure aborts early with `Failed`.

### 6.4 Ordering evidence

[VERIFIED — JVM, `StyleMutationCoordinatorTest` 23 cases, both `:core-data` variants]

- Rapid alternating field edits keep both fields and end at the latest document; two updates holding one captured revision commit exactly once and the other gets `StaleStyle`.
- An injected replace failure leaves the previous bytes, the active pointer and the resolved document unchanged, with no temp leftover.
- A DataStore switch failure is `Failed`; a DataStore delete failure after the file is gone is `Partial` — neither is a success shape.
- [VERIFIED — JVM, TODO 2R1 / D-113] A DataStore **read** failure during a delete aborts before the file or derivation entry is touched and returns `Failed` — never `Deleted` — with no pointer write, no `*.tmp` leftover and no success-shaped result; deleting an inactive style keeps the active pointer and clears only its own derivation entry; an observation-only read failure still resolves the default built-in through the unchanged `activeId` fallback.
- A delete and a write on one id cannot resurrect the file in either order; a write after a delete is `NotFound` and writes nothing.
- A stale revision is refused and the externally written document stays; a restart reads the last committed document and the persisted derivation base.
- Duplicate gets a fresh id and its own derivation entry; import/restore apply D-83 (free id preserved, identical document a no-op, occupied id saved as a copy, a built-in id never shadowed); import and restore share the one lock.
- Derivation metadata survives a failed metadata replace with its previous entries intact, and no operation leaves a `*.tmp` file behind.

[UNKNOWN] On-device timing, process-death behavior of a save in flight, and editor UX on a stale target are not device results (TODO 5d owns the state machine).
