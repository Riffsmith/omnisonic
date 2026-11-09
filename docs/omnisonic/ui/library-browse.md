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
  state) and `loadMore(section)` (guarded by `hasMore`/`appending`/active
  job). Reads go through **`LibraryBrowseSource`** (interface) implemented
  by `RepositoryBrowseSource` over `BrowseRepository` — the seam plain JVM
  tests fake (bound in `di/LibraryBrowseModule`).
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
  songs render `SongListBody` (thumb, title, artist, duration). Load-more is
  a full-span footer that **is** the trigger: while it is composed with more
  pages pending it requests the next page; a failed append swaps the spinner
  for a retry [VERIFIED: state machine in `LibraryBrowseViewModelTest`].
- **Covers:** `CoverImage` composable (`feature-library/ui/CoverImage.kt`)
  builds `CoverImageKey(providerId, imagePath)` models (provider-api —
  relocated from `:playback` in this task; `CoverKeyer` stayed in
  `:playback`). The app-level Coil loader (`SingletonImageLoader.Factory`)
  resolves the salted URL via `MediaProvider.coverArtUrl`. A missing/failed
  cover reveals the token-sized placeholder layer underneath
  (`coverPlaceholder` + `placeholderTintAlpha`).
- **`sizePx` stays null** (original-size covers) — **retired by the 2026-11-09
  device findings** (this caused real scroll jank); server-side `size=`
  resizing derived from the measured cell size is D-17, landing in task 4b.

## Deferred from §3.4 (logged, not dropped)

- **Radios hub entry** — needs a radios browse query + section; deferred
  with task 5's detail pass or later.
- **Long-press multi-select** (+ header selection-mode button twin) —
  deferred: selection has no consumers until detail/play wiring (tasks 5/6).
- **Song-row tap** — no action yet; play wiring lands with the playback
  surfaces (tasks 5/6).
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
   HTTP walk lands album rows over minutes. Fix (4b): debounced library
   invalidation flow → pagers re-run pages/counts as sync writes land.
2. **Force-stop mid-sync leaves a partial library with no resume.**
   Observed 15/105 → terminate → relaunch → 55/204 persisted, no growth;
   the only `InitialLibrarySync.start` call site is provider-setup
   completion. Fix (4b): resume-on-launch when `sync_state.lastFullSyncAt
   IS NULL`.
3. **Scroll lag/freeze on every art screen.** Root cause: original-size
   covers (`CoverImage` passes `sizePx = null`; `getCoverArt` serves
   0.3–5 MB originals). This retires the "cover `sizePx` stays null"
   deferral above — sizing is D-17, fix in 4b. DB exonerated at this scale;
   debug-build (no R8/baseline profile) noted as a secondary factor.
   Re-measure with `dumpsys gfxinfo` after 4b; the 10k-grid item above
   becomes measurable on the same device.
