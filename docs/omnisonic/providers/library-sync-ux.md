# Library sync UX: progress, live refresh, resume (D-16) + cover sizing (D-17)

Status: **planning record — nothing here is implemented yet** (tasks 4b/4c,
inserted 2026-11-09 from the first live-device test). Existing sync mechanics
are `[VERIFIED]`; everything in "Planned" is a contract for the
implementation tasks, not shipped behavior.

## Current mechanics (Phase 3/5 as shipped) [VERIFIED]

- **Walk order** (`SubsonicLibrarySyncer.runSync`): artists → albums+songs
  (paged album list, then **one `getAlbum` HTTP fetch per album** — the slow
  phase; ~274 round-trips for a 274-album server) → genres → radios →
  playlists → bookmarks → prune (full sync only). `runDiffSync` exists
  (watermark-based, no prune) and is unused by the UI today.
- **Progress surface: none.** The walk publishes nothing until it records
  the final `sync_state` row (`lastFullSyncAt`/`lastDiffSyncAt`,
  `lastSyncResult`, `lastSyncMessage`, `lastSyncCount`,
  `lastSyncDurationMs`) on success or failure. Cancellation records nothing.
- **Trigger: provider-setup completion only.** `ProviderSetupService.
  startInitialSync` → `LibrarySyncLauncher.start` → fire-and-forget on a
  process-scoped IO scope (auth outcomes reported to the provider row;
  walk failures logged, never thrown). No launch-time resume, no periodic
  sync, no manual trigger.
- **UI consumption: point-in-time snapshots.** Browse sections (task 4)
  read one page + one `COUNT(*)` per section entry (`SectionPager.
  loadInitial`, idempotent, re-entry only from `Error`) and never refresh.

## Live device findings (2026-11-09, user's phone) [VERIFIED on device]

1. **Mid-sync snapshots:** "Albums 1" (one row, the first alphabetically,
   `hasMore = false`) while the walk was in its album phase; "Albums 274"
   after completion; the Songs header showed 1374 at another moment.
2. **Force-stop mid-sync = permanent partial library:** 15 albums/105 songs
   → terminate → relaunch → 55 albums/204 songs persisted, no growth — the
   walk died with the process and nothing restarts it.
3. **Scroll jank on every art screen:** original-size covers (`CoverImage`
   sends `sizePx = null`; `getCoverArt` returns the 0.3–5 MB original).
   The DB is exonerated at this scale (~1–2k rows).

## Planned — task 4b (data plumbing)

- **Progress model (D-16):** a sync progress value
  (`providerId`, phase ∈ `ARTISTS | ALBUMS_SONGS | GENRES | RADIOS |
  PLAYLISTS | BOOKMARKS | PRUNE | DONE | FAILED | BLOCKED`, `itemsDone` /
  `itemsTotal` for the per-album walk, `startedAt`, outcome/message) exposed
  as an in-memory StateFlow from a small repository/seam (name to be coined
  at implementation, e.g. `LibrarySyncStatusRepository`); the syncer gains
  an optional progress hook wired through `LibrarySyncLauncher`. **No schema
  change for live progress** — `sync_state` remains the persisted record of
  last outcomes. [PROPOSED — implement in 4b]
- **Library invalidation → live refresh:** a debounced flow over the catalog
  tables (Room `InvalidationTracker.createFlow("songs","albums",
  "artists","genres","playlists")` — API availability on Room 2.6.1
  [INFERRED, verify at implementation]) exposed through a core-data seam;
  the section pagers re-run pages + counts when it fires (coalescing so a
  whole sync produces a handful of refreshes, not one per INSERT). This is
  the core correctness fix: it makes the library fill in front of the user
  during sync regardless of which screen is open.
- **Resume-on-launch:** at app start, if a provider row exists and
  `sync_state.lastFullSyncAt IS NULL` (a full sync never completed), start
  the initial sync through the existing run-gate seam. Double-start guard =
  the in-process progress state (a running walk is not restarted); force-stop
  detection is not needed — "never completed" is the trigger. [PROPOSED]
- **Cover sizing (D-17):** `CoverImage` derives `sizePx` from the measured
  cell size (density-scaled, capped, e.g. ≤1024 px; list thumbs ~128–192 px,
  grid cells ~300–600 px). `SubsonicClient.coverArtUrl` already forwards
  `size=`. Verify with `dumpsys gfxinfo` on the user device; the parked
  10k-grid frame-time item becomes measurable too.

## Planned — task 4c (UX)

- **First-run sync step:** the D-12 onboarding steps list becomes
  `[providerSetup, librarySync]` (the gate mechanism takes new steps without
  code changes); new route `librarySync` (UI_SCREENS.md §5, extend-only).
  Screen slots: phase/progress list (N/M), fresh humorous copy (clean-room —
  written fresh, never lifted), Continue enabled on completion; on failure →
  Retry + Continue-anyway (safe once the invalidation flow lands — the
  library fills live afterwards). Leaving the screen does not cancel the
  walk (process-scoped scope continues). Completing the step sets
  `onboardingComplete` via the existing `completeOnboardingStep`.
- **Post-setup navigation:** first run navigates `providerSetup` →
  `librarySync` → `home`; the later settings path keeps today's behavior
  (no flag semantics).
- **Settings sync manager:** providers group gains a sync section per
  provider — live phase/progress, last result from `sync_state` (time,
  outcome, message), "Sync now" (`runDiffSync`), "Full resync" (guarded +
  confirm). Not shown on every launch; the first-run step is gate-only.
- **Out of scope for now (open questions):** skippable sync step (proposed
  default: not skippable while healthy; failure → Retry/Continue-anyway);
  periodic/diff sync policy (proposed default: manual only in Phase 5;
  pull-to-refresh + background sync are a Phase 6+ decision) — see
  `.agents/memory/open-questions.md`.

## Naming

New public names above are proposals pending the ground-rule naming diff
(`reference/docs/GESTURES.md` §5) at implementation time: `librarySync`
route, `LibrarySyncStatusRepository`/progress phase names, settings manager
strings. No verbatim collision exists in `reference/extracted/theme/
theme_keys.json` or THEMING.md §4 for these (checked 2026-11-09); the
studied app's own sync progress surface, if any, was not reconstructed —
do not borrow names from it regardless [UNKNOWN].
