# Library browse UI + paging (`:feature-library`, Phase 5 task 4)

Status: Phase 5 task 4, shipped 2026-11-09. Spec: `reference/docs/UI_SPEC.md` §3.4
(hub + section slots, states, paging), §1.4 (state triplet), §1.5 (token
discipline). Data contract: `docs/omnisonic/search/data-layer.md` (the
`*Raw` + `ProfileScope` wrapper convention this feature's queries follow).

## Data layer (`:core-data`)

- **Browse queries** live on the five catalog DAOs (`AlbumDao`, `ArtistDao`,
  `SongDao`, `GenreDao`, `PlaylistDao`): one `browse…Raw` `@Query` plus a
  scope-taking `browse…` wrapper, and one `count…Raw`/`count…` pair per
  table — the task-3 convention enforced by `ProfileScopeQueryTest`
  (source scan + exemption table). Exclusion binds one CSV string matched
  via `instr()` (999-variable bound, `storage/sqlite-api29-floor.md`).
- **Ordering** matches the search DAO's per-table orderings: albums /
  artists / songs `COALESCE(sortTitle, title) COLLATE NOCASE, title, id`;
  genres `COALESCE(sortTitle, title), id`; playlists `title, id` with
  `hidden = 0`. Songs browse is alphabetical (browse context), not
  `lastPlayed DESC` (search relevance) — [VERIFIED] `BrowsePagingTest`.
- **`BrowseRepository`** (`core-data/browse/`, `@Singleton`, injects
  `OmnisonicDatabase` like the other repositories) exposes per-table
  `BrowsePage<T>(items, offset, limit, hasMore)` reads and counts. The page
  fetches one row past the window (`limit + 1`) so `hasMore` needs no second
  query. Default page limit 60, clamped 1–200; offset floors at 0.
- **Scope semantics:** the repository takes `ProfileScope` (default
  `Unrestricted`) and passes it through — unlike `SearchRepository`, browse
  does **not** fold `ProviderVisibilityStore`/`ProviderStatusTracker`
  exclusion today. There is no hide-provider UI yet (Phase 9), so the two
  behaviors are indistinguishable in practice; revisit when the visibility
  UI lands. [INFERRED]
- **OFFSET paging caveat:** windows are `LIMIT/OFFSET` over the live table
  (K7, no Paging 3). A sync running mid-scroll can shift rows between pages;
  the deterministic no-duplicate guarantee holds only for a stable library —
  [VERIFIED] for static data in `BrowsePagingTest`.

## UI layer (`:feature-library`)

- **`LibrarySection`** — `albums | artists | genres | songs | playlists`
  (fixed Phase 5 hub order; the UI_SPEC §3.4 `radios` entry is deferred —
  no radios browse/detail route this phase). `id` equals the
  `library/{section}` route argument (`ShellRoutes.SECTION`).
- **`LibraryBrowseViewModel`** (`@HiltViewModel`, created once in
  `OmnisonicApp` and threaded through the shell like the other shell-scoped
  view models) owns one `SectionPager<T>` per section, so section pages
  survive navigation. Public typed flows: `albums`/`artists`/`songs`/
  `genres`/`playlists` as `StateFlow<BrowseUiState<…Entity>>`; commands
  `load(section)` (idempotent initial load; re-enters only from the error
  state), `loadMore(section)` (guarded by `hasMore`/`appending`/active job)
  and `playFrom(rowIndex)` (songs only, see the playback-actions note below). Reads go through **`LibraryBrowseSource`** (interface) implemented
  by `RepositoryBrowseSource` over `BrowseRepository` — the seam plain JVM
  tests fake (bound in `di/LibraryBrowseModule`).
- **`SectionPager<T>`** owns one section's state. `loadInitial` is idempotent
  (re-entry only from `Error`), `loadMore` is the guarded append, and a
  catalog-change feed (task 4b, below) drives a live refresh that re-fetches
  the window currently shown plus its count. A refresh request that arrives
  mid-load is replayed once the in-flight job finishes; a section that was
  never opened ignores it.
- **`BrowseUiState<T>`** — the §1.4 triplet: `Loading`, `Content(items,
  totalCount, hasMore, appending, appendFailed)`, `Empty`, `Error`. An
  append failure keeps the loaded items and raises `appendFailed`; the
  footer's inline retry re-runs `loadMore`.
- **Screens:** `LibraryHubScreen` (entry grid, `LayoutTokens.gridColumnsFor`
  + `hubEntryAspectRatio`, tap → `library/{section}`) and
  `LibrarySectionScreen` (header row with title + formatted item count →
  loading placeholder grid/rows, empty copy, error + retry, or content).
  Albums/artists/playlists render `CoverGridBody` (cover tiles +
  title/subtitle), genres render placeholder tiles with the title inside,
  songs render `SongListBody` (thumb, title, artist, duration; the whole row
  is one tap target). Load-more is
  a full-span footer that **is** the trigger: while it is composed with more
  pages pending it requests the next page; a failed append swaps the spinner
  for a retry [VERIFIED: state machine in `LibraryBrowseViewModelTest`].
- **Song-row playback action (task 7d):** tapping a songs row calls
  `LibraryBrowseViewModel.playFrom(rowIndex)`, which queues the **loaded**
  songs window through `PlaybackController` and starts at the tapped row.
  Rows without a provider external id cannot be resolved by the stream
  resolver, so they are skipped and the start index re-anchors on the tapped
  row's identity; a tap before content, out of range, or on an unplayable row
  is a no-op. This is the same contract as `DetailViewModel.playFrom`
  (`ui/detail-screens.md`), implemented once through the shared
  `feature/library/playback/QueueEntries.kt` mappers. Tap only — no
  long-press/appbar action, so no visible-control twin is owed
  (`ui/gesture-contract.md`). Queueing beyond the loaded window stays a
  later-phase item. [VERIFIED in `LibraryBrowseViewModelTest` +
  `:app` `LibrarySongTapTest`; device tap feel UNKNOWN]
- **Covers:** `CoverImage` composable (`feature-library/ui/CoverImage.kt`)
  builds `CoverImageKey(providerId, imagePath)` models (provider-api —
  relocated from `:playback` in this task; `CoverKeyer` stayed in
  `:playback`). The app-level Coil loader (`SingletonImageLoader.Factory`)
  resolves the salted URL via `MediaProvider.coverArtUrl`. A missing/failed
  cover reveals the token-sized placeholder layer underneath
  (`coverPlaceholder` + `placeholderTintAlpha`).
- **Cover request sizing (D-17, task 4b):** `BoxWithConstraints` measures the
  cell and `CoverSizing.sizePx` returns a 64-px-bucketed, 1024-px-capped
  request size (null only when the cell is not measured yet), stored on
  `CoverImageKey.sizePx`. Neighbouring cells and rows share a cache entry;
  the server no longer serves 0.3–5 MB originals into grids.
  [VERIFIED: `CoverSizingTest`; on-device smoothness is user-assisted —
  re-measure with `dumpsys gfxinfo` when a device session happens [UNKNOWN]]

## Live refresh (task 4b, D-16)

- `:core-data` `sync/LibraryInvalidation` collapses Room invalidation bursts
  over the five catalog tables (250 ms quiet period). `BrowseRepository`
  exposes it as `libraryChanges`; `RepositoryBrowseSource` forwards it and
  every `SectionPager` collects it. A sync that writes album rows now fills
  an open section as it runs: the pager reloads its current window and count
  instead of staying a point-in-time snapshot.
  [VERIFIED: `LibraryInvalidationTest` (insert → flow) and
  `LibraryBrowseViewModelTest` (refresh paths)]

## Deferred from §3.4 (logged, not dropped)

- **Radios hub entry** — needs a radios browse query + section; deferred
  with task 5's detail pass or later.
- **Long-press multi-select** (+ header selection-mode button twin) —
  deferred: selection has no consumers until detail/play wiring (tasks 5/6).
- **Sort control / view toggle** in the section header — explicitly later
  per §3.4.
- **10k-album grid frame time** — device-gated [UNKNOWN]; Robolectric
  asserts paging windows/keys only.

## Live device findings (2026-11-09, user's phone) [VERIFIED on device]

First real-device run of this screen set (debug APK, user's phone). What
worked: full browse path end-to-end (setup → hub → sections), covers load,
artists' images land, counts correct after a completed sync ("Albums 274",
"Songs 1374"). Three issues, each root-caused in code — remediation is
tasks 4b/4c (D-16/D-17), recorded in
`docs/omnisonic/providers/library-sync-ux.md`:

1. **Sections are point-in-time snapshots while the initial sync runs.**
   First entry into Albums mid-sync: "Albums 1" + one row (first
   alphabetically, `hasMore = false`); after completion: "Albums 274".
   Mechanics: `SectionPager.loadInitial` reads one page + one COUNT once and
   never refreshes; no DB-invalidation feed exists; the syncer's per-album
   HTTP walk lands album rows over minutes. **Fixed in 4b** — debounced
   library invalidation flow → pagers re-run pages/counts as sync writes
   land (`LibraryInvalidationTest`, refresh cases in
   `LibraryBrowseViewModelTest`).
2. **Force-stop mid-sync leaves a partial library with no resume.**
   Observed 15/105 → terminate → relaunch → 55/204 persisted, no growth;
   the only `InitialLibrarySync.start` call site is provider-setup
   completion. **Fixed in 4b** — resume-on-launch when
   `sync_state.lastFullSyncAt IS NULL` (`LibrarySyncResumePolicy` +
   `LibrarySyncLauncher.resumeIncomplete`, run-gated by
   `LibrarySyncStatusRepository`).
3. **Scroll lag/freeze on every art screen.** Root cause: original-size
   covers (`CoverImage` passed `sizePx = null`; `getCoverArt` serves
   0.3–5 MB originals). **Fixed in 4b** — request size derived from the
   measured cell (`CoverSizing`, 64-px buckets, 1024-px cap). DB exonerated
   at this scale; debug-build (no R8/baseline profile) noted as a secondary
   factor. Re-measure with `dumpsys gfxinfo` on the user device — [UNKNOWN]
   until that session; the 10k-grid item above becomes measurable then too.

## Second device session (2026-11-10, release build) [VERIFIED on device]

First R8-minified runtime run (`-Pomnisonic.tmpReleaseSign=true`), same
phone. Release is smoother than debug — but **scroll initiation still
freezes on every library surface (hub, albums, songs, artists, genres; both
directions) while taps navigate instantly**: "try again and again… suddenly
works… lets me scroll until it stops again." The user's observation that
freezes hit when the content shown is *unchanged* (static screen) is the
tell: the screen is static because no scroll events were processed.

Diagnostics run over adb (numbers recorded in the task file + STATE.md):

- **gfxinfo baseline (5-min active-scroll window):** 11,262 frames, 1.42%
  jank, p50 17ms / p90 19ms / p99 32ms, worst ~250ms ×1, slow-UI-thread 127,
  slow bitmap uploads 3, **high-input-latency 17,585**. Frame production is
  healthy while input events queue ⇒ **gesture-level, not rendering** — the
  D-17 cover sizing holds (3 slow uploads in 11k frames vs. the
  original-cover flood before 4b).
- **ColorOS `OplusHansManager` (app freezer) exonerated for foreground
  use:** a 4-minute foreground scroll session captured 29,993 log lines with
  ZERO omnisonic freezer events. The freezer does cycle the app ~15 s while
  it is backgrounded (normal cached-app behavior, woken by network packets —
  which incidentally explains why the warm-cover-cache era *felt* worse:
  less traffic meant fewer wake-ups). The app is not battery-whitelisted; a
  Phase 6+ question (open questions file), not this bug.
- **Root cause (code-proven, fixed 2026-11-10 in task 4d):** `pagerEdgeExclusion`
  (`SwipeZone.kt`) consumed every vertical drag after touch-slop on the
  Initial pass — the pass that preempts children — so list scrollables
  cancelled drags; taps (no slop) and established flings (animation-driven)
  survived. Violated this doc's sibling contract
  (`ui/gesture-contract.md`) and UI_SPEC §1.2. Fixed with the pure
  `SwipeZoneRules.edgeExclusionClaims` rule: only horizontal drags starting
  inside the token strips are consumed. Regression coverage:
  `LibraryScrollGestureTest` (real vertical `performTouchInput` on the hub and
  a section grid; `:app` 47 tests green).
- Post-fix acceptance: the same scroll session's high-input-latency count
  collapses to near zero and scroll initiation is reliable on all surfaces.
  Device A/B on the release build is still pending [UNKNOWN device]; the
  code-level regression is green in Robolectric.

## Third device session (2026-11-10, release build, post-4d) [VERIFIED on device]

Gesture ownership is fixed, but a **new symptom shape** remains. The user's
report, verbatim:

> "when the first time after the provider setup I look up the
> albums/tracks/artists/genre, the scroll doesn't happen for a while. Then it
> happens and gets stuck. It occurs until I reach the end. After that scroll
> happens nicely (not absolutely flawless but just minor ms delays rarely).
> … if I spent more time in the app, the less the scroll problem occurs.
> However, if I terminate the app and open it again, it takes time for scroll
> to start feeling fluid."

Facts decoded from it (F1–F6), and what each constrains:

| # | Observation | Constraint |
|---|---|---|
| F1 | Worst on the **first pass** through a section after setup | cold cache / cold JIT / first page loads |
| F2 | All sections, incl. albums (274 rows) and songs (1,374) | not a pure large-table effect |
| F3 | Recovers once the list **end** has been reached | scales with items composed / pages loaded / covers fetched |
| F4 | Better the longer the **process** stays alive | in-process warm-up (memory cache, JIT, SQLite page cache) |
| F5 | **Relaunch resets** it | per-process state, not on-disk state (disk cache + DB survive) |
| F6 | Not experienced as a freeze — content moves, then stalls | frame-production / input-handling spikes, not gesture ownership |

The hub (5 static tiles, no DB reads, no covers) is reportedly fine, so the
cost scales with list content. F6 distinguishes this from the 4d bug: that one
was a static screen (consumed drags → no frames); this one is stop-and-go.

**Investigation and the ranked fix plan (cover pipeline → cold-process
compilation → paging/invalidation churn → composition micro-costs), the
measurement protocol, and the acceptance gates live in
`ui/scroll-performance.md`** — that document is the authoritative record for
this symptom and the Phase 5 task 4e playbook; this section only pins the
symptom and the device-session facts.

**[Corrected 2026-11-11 — the §4 matrix ran and the user refined the
symptom.]** F1/F3/F4/F5 were artifacts of one underlying fact: **every new
touch sequence could be swallowed for a random number of tries** (even after
idle waiting), while an established drag/fling was smooth. Fast continuous
scrolling (the original symptom reports) hides it — hence "gets better over
time" (F4) and "fine after reaching the end" (F3). Root cause =
`consumeSurplusInput` Main-pass ancestor consumption (task 4e; full mechanism
in `ui/gesture-contract.md` §"Second bug record"); fixed and device-verified
2026-11-11. What remains open for a **fresh install** is a second, distinct
pattern — stalls at specific mid-list points (page boundaries) — which stays
with `ui/scroll-performance.md` §5 P2 (paging/invalidation).
