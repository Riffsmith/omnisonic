# Library sync UX: progress, live refresh, resume (D-16) + cover sizing (D-17)

Status: **tasks 4b + 4c shipped 2026-11-09** (D-16/D-17, inserted from the
first live-device test). Sync mechanics, the progress surface, resume-on-launch,
live refresh, cover sizing and the first-run/settings screens are all
`[VERIFIED]` by unit tests; on-device feel of the new screens is still
[UNKNOWN] until the next device session.

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

## Shipped — task 4b (2026-11-09) [VERIFIED by unit tests]

- **Progress model + seam:** `sync/LibrarySyncProgress.kt` defines
  `LibrarySyncPhase` (`ARTISTS | ALBUMS_SONGS | GENRES | RADIOS | PLAYLISTS |
  BOOKMARKS | PRUNE | DONE | FAILED | BLOCKED | SKIPPED`), `LibrarySyncOutcome`,
  and the immutable progress value (`providerId`, phase, `itemsDone`/
  `itemsTotal`, `startedAt`, `finishedAt`, `outcome`, `message`, derived
  `isRunning`/`fraction`; terminal phases require an outcome + finish time).
  `sync/LibrarySyncStatusRepository.kt` is the in-memory `StateFlow` seam plus
  the per-provider `tryBegin`/`finish` run gate. `SubsonicLibrarySyncer` takes
  an `onProgress` hook and publishes the phase walk; [ALBUMS_SONGS] carries the
  N/M counter (totals grow as listing pages arrive), terminal values cover
  success, failure, blocked and skipped. No schema change.
  [VERIFIED: `LibrarySyncProgressTest`, `SubsonicLibrarySyncerTest` progress
  cases]
- **Resume-on-launch:** `LibrarySyncResumePolicy.shouldResume(status,
  hasLibrarySync)` = "full sync never completed" (`lastFullSyncAt == null`)
  for providers that report library sync. `LibrarySyncLauncher.resumeIncomplete`
  queries the provider rows and restarts those walks through `start`, whose
  `tryBegin` gate makes a live walk unstoppable restart and keeps setup
  completion from double-starting. `OmnisonicApplication.onCreate` calls it.
  [VERIFIED: `LibrarySyncProgressTest` resume-policy + run-gate cases;
  full `:app:assembleDebug` proves the wiring]
- **Live library refresh:** `sync/LibraryInvalidation.kt` wraps Room's
  `invalidationTrackerFlow("songs","albums","artists","genres","playlists")`
  with a quiet-period collapse (250 ms). `BrowseRepository.libraryChanges`
  exposes it; `LibraryBrowseSource` carries it into each `SectionPager`, which
  re-fetches the window it is currently showing plus the COUNT (a never-opened
  section ignores changes, and a change during a load replays once). [VERIFIED:
  `LibraryInvalidationTest` inserts a row and observes the flow;
  `LibraryBrowseViewModelTest` covers count growth, window preservation and
  the mid-load replay]
- **Cover sizing (D-17):** `CoverImage` measures its cell with
  `BoxWithConstraints`, derives a bucketed request size (`CoverSizing`, 64 px
  buckets, 1024 px cap; null for an unmeasured cell) and passes it through
  `CoverImageKey.sizePx`, so `coverArtUrl` asks the server for a thumbnail
  instead of the original. Cache identity stays per-size (bucketing keeps
  neighbours on one entry). [VERIFIED: `CoverSizingTest`; on-device scroll
  smoothness still [UNKNOWN] until the next user device session]

## Shipped — task 4c (2026-11-09) [VERIFIED by unit tests]

- **First-run sync step:** the onboarding steps list is
  `[providerSetup, librarySync]` (`OnboardingSteps.LIBRARY_SYNC`); the gate
  mechanism itself is unchanged (D-12). New route `librarySync`
  (UI_SCREENS.md §5, extend-only) renders `LibrarySyncStepRoute` — a phase
  list over the 4b progress flow with N/M for the album walk, fresh
  shelf-stocking copy, a determinate bar when the walk reports a count.
  Continue (enabled on completion or after a failure) completes the step via
  `completeOnboardingStep`, which exhausts the list and sets
  `onboardingComplete`; a failure additionally offers Retry (restarts the full
  walk through the run gate) and Continue-anyway. Leaving the screen never
  cancels the walk (process-scoped scope). The step self-heals: it restarts a
  walk when the provider has no recorded full sync and nothing is running
  (relaunch mid-sync), and adopts a recorded completion without starting
  anything. No skip while sync is healthy — per the open-question default.
  [VERIFIED: `LibrarySyncStepViewModelTest` (8), `ShellPreferencesStoreTest`
  two-step cases, `ProviderSetupNavigationTest.firstRunWalksSetupThenSyncThenHome`]
- **Post-setup navigation:** first run navigates `providerSetup` →
  `librarySync` → `home` (the NavHost's completion callback branches on
  `previousBackStackEntry == null`, i.e. setup as start destination); the
  later settings path keeps today's behavior (`providerSetup` → `home`).
  [VERIFIED: `ProviderSetupNavigationTest.laterSetupFromSettingsReturnsHomeAfterSave`]
- **Settings sync manager:** the providers group in settings gained a
  per-provider sync section (`LibrarySyncManagerSection`) — last recorded
  outcome from `sync_state` (result label, relative time, failure message),
  the live walk (phase label + N/M + bar) when one is running, **Sync now**
  (`runDiffSync`) and **Full resync** (confirmation dialog first; both
  triggers no-op while that provider's walk is live). Providers that do not
  report library sync are not listed; recorded results refresh when a walk
  turns terminal. [VERIFIED: `LibrarySyncManagerViewModelTest` (4)]
- **Screen-facing seam:** `sync/LibrarySyncControl` (`progress` StateFlow +
  `providers()` + `startFullSync`/`startDiffSync`) implemented by
  `LibrarySyncLauncher` (which also gained the FULL/DIFF kind on `start`/`run`)
  and Hilt-provided from `ProviderWiringModule`; the screens never touch the
  database directly. [VERIFIED: `LibrarySyncLauncherTest` providers-mapping +
  full/diff timestamp cases; `:app:assembleDebug` proves the graph]

## Second device session (2026-11-10, release build) — verification + defects

The release (R8-minified) build ran on the RMX3085 for the first time. What
the session verified, and the two defects it exposed (fixes scheduled as task 4d):

- **Resume works [VERIFIED on device]:** force-killing mid-sync and relaunching
  brings the walk back up (the `resumeIncomplete()` path) and the step shows
  live cues — the persisted partial library from the killed run is adopted.
- **Live refresh works [VERIFIED on device]:** sections entered mid-sync fill
  in as the walk writes rows (the 4b invalidation feed).
- **Cover sizing (D-17) holds [VERIFIED on device]:** the release gfxinfo
  baseline (11,262 frames over a 5-minute active scroll) shows 3 slow bitmap
  uploads and healthy frame times — the original-size-cover flood is gone.
  Numbers recorded in `ui/library-browse.md` and the task file.
- **DEFECT — first-run step shows no progress cues (fixed 2026-11-10 in task 4d):**
  `LibrarySyncStepViewModel` was hoisted at the app root (`hiltViewModel()`
  default param in `OmnisonicApp`), so its `init` ran at APP LAUNCH. On a
  fresh install there was no provider row yet → `providerId = null` → the
  progress collector's `progress.providerId != state.providerId` filter
  dropped every update forever → the screen rendered only the static
  title/intro copy; on relaunch the provider existed at composition → init
  resolved it → cues appeared. Same init-time-empty class existed in
  `LibrarySyncManagerViewModel` (providers() at launch → empty entries until a
  terminal event re-queried). **Fix shipped:** the step VM is destination-scoped
  through a `syncStepViewModelProvider` seam on `ShellNavHost` (the task-5
  `detailViewModelProvider` pattern), so `init` runs when the `librarySync`
  route opens; the manager re-resolves providers via `refresh()`, driven by a
  `LaunchedEffect` in `LibrarySyncManagerSection` on screen entry. Tests:
  `ProviderSetupNavigationTest.syncStepViewModelIsOnlyCreatedWhenTheRouteOpens`,
  `LibrarySyncManagerViewModelTest.refresh picks up a provider that existed
  only after launch`. Device acceptance (fresh install, no relaunch) stays
  [UNKNOWN device].
- **NOT a sync defect — scroll freeze is gesture-level:** the persisting
  scroll freeze was traced to `pagerEdgeExclusion` consuming all vertical
  drags (see `ui/gesture-contract.md` bug record). Frame production during
  the sync/scroll session was healthy (1.42% jank), and the ColorOS app
  freezer was exonerated for foreground use. Fixed in task 4d.
- **Open, Phase 6+:** the app is not battery-whitelisted on the test device;
  ColorOS freezes it while backgrounded (normal cached-app behavior). That
  pauses background syncs — decide the background-sync policy and whether to
  request an exemption when the periodic-sync question is answered
  (`.agents/memory/open-questions.md`).

## Naming

New public names are recorded in the ground-rule naming diff
(`reference/docs/GESTURES.md` §5 rule 2 / §5.1): `librarySync` route,
`OnboardingSteps.LIBRARY_SYNC`, `LibrarySyncControl`/`LibrarySyncProvider`/
`LibrarySyncLastResult`/`LibrarySyncKind`, the step/manager view-model and
screen names — Omnisonic-original coinages, no verbatim collision in
`reference/extracted/theme/theme_keys.json` (241 dotted keys) or THEMING.md §4
(re-run 2026-11-09; the only studied `sync` key is the unrelated
`d.lyrics.show.sync.buttons.bar` lyrics toggle). The studied app's own sync
progress surface, if any, was not reconstructed — do not borrow names from it
regardless [UNKNOWN]. User-facing copy is written fresh.
