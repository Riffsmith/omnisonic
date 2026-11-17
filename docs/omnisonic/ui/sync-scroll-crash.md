# Live-sync scroll crash — `ui/sync-scroll-crash.md`

Purpose: pin down why the library sections crash while a sync walk is
running and the user scrolls, and give the next session a
decision-complete, measurement-first fix plan (device capture first when a
phone is available, test-first otherwise) instead of re-deriving the
mechanism.

How derived: investigation of the P0 record in `progress/KNOWN_ISSUES.md`
(found in the 2026-11-16 fresh-install flow review, release build) against
the shipped code read end-to-end on 2026-11-17: the sync walk
(`SubsonicLibrarySyncer`), the invalidation feed (`LibraryInvalidation`), the
pager (`SectionPager`), the browse page contract (`BrowseRepository`,
`BrowsePage`), the section screens and their lazy keys, and every
cover-request path reachable during a section scroll (`CoverImage`,
`CoverSizing`, `SearchCover`, detail/now-playing headers). No device logcat
capture exists yet — every device-gated statement is `[UNKNOWN]`.

Confidence: the H1 chain is `[VERIFIED code/JVM]` — the duplicate-window
precondition was reproduced in `LibraryBrowseViewModelTest` — and remains
`[INFERRED]` as the cause of the *original phone incident*, whose logcat
signature was never captured. H2/H3 are code-level demotions (`[VERIFIED]`
absent from the section paths), with the OOM path kept only as a logcat
fallback. The fix is `[VERIFIED code/JVM]` (tests + `:app:assembleDebug`);
every device gate below stays `[UNKNOWN device]` until a valid post-fix
acceptance pass runs.

Date: 2026-11-17 (implementation record added after the test-first hotfix)

Links: `ui/library-browse.md` (paging engine + the existing OFFSET caveat),
`ui/scroll-performance.md` §5 P2 (the shift problem as a cost/scaling
defect), `ui/gesture-contract.md` (unrelated 4d gesture fix — do not
re-open), `progress/KNOWN_ISSUES.md` P0, `.agents/plans/next-session-prompt.md`.

---

## 1. Symptom record

`progress/KNOWN_ISSUES.md` P0, found during the 2026-11-16 fresh-install
flow review (release build, device at task 6):

> During a live sync walk, navigating to library sections (albums / songs /
> artists) and scrolling crashes the app.

Decoded into constraints:

| # | Observation | What it constrains |
|---|---|---|
| C1 | Only while a sync walk is writing the catalog | a write/read interaction, not a static-data defect |
| C2 | Only when the user scrolls (more pages load) | the append path, not the initial window |
| C3 | All sections (albums, songs, artists, …) | a shared engine (`SectionPager` + stable keys), not one screen's copy |
| C4 | No logcat capture yet | every cause below is a hypothesis until the signature is recorded |

Task 4d's gesture fix (drag-start swallowing, `consumeSurplusInput` →
`inputBarrier`) shipped in the same phase but is unrelated: that bug produced
a *static screen*, not a crash (`ui/gesture-contract.md`). Do not re-open it.

## 2. Ranked root-cause candidates

### H1 — OFFSET page overlap → duplicate lazy-list keys (primary)

The full chain, code-verified:

1. The sync walk commits **one transaction per album**
   (`core-data/.../sync/SubsonicLibrarySyncer.kt:327-339`,
   `syncAlbumWithSongs`). Every commit fires Room's invalidation tracker.
2. `LibraryInvalidation` collapses the burst to one event per 250 ms quiet
   period (`core-data/.../sync/LibraryInvalidation.kt:24-34`).
3. `LibraryBrowseViewModel` owns **five shell-hoisted pagers**, every one
   collecting that feed
   (`feature-library/.../browse/LibraryBrowseViewModel.kt:26-30`), so a
   section the user is scrolling refreshes *during* the walk.
4. `SectionPager.loadMore` fetches the next page at
   **`OFFSET = content.items.size`** and appends the page raw
   (`feature-library/.../browse/SectionPager.kt:80-104`; offset at `:84`,
   append at `:91`).
5. Browse ordering is
   `COALESCE(sortTitle, title) COLLATE NOCASE, title, id`
   (`ui/library-browse.md` §ordering). The walk inserts rows at arbitrary
   alphabetical positions, so every OFFSET window behind the inserts
   shifts. A page fetched after the inserts can contain rows already in the
   loaded list.
6. The merged `items` now contains the **same row twice**. All section
   bodies key lazy items by row id — `itemKey = { it.id }` for grids,
   `song.id` for songs
   (`feature-library/.../ui/LibrarySectionScreen.kt:246` and `:342`;
   per-section keys in `ui/LibrarySectionRoute.kt`).
7. Compose 1.7.4 rejects duplicate keys in a lazy item list; the next
   measure throws `IllegalArgumentException: Key "…" was already used` and
   the process dies. [INFERRED: pinned Compose behavior; confirm the exact
   signature in the device capture]

`ui/library-browse.md` already documents the precondition — *"A sync running
mid-scroll can shift rows between pages; the deterministic no-duplicate
guarantee holds only for a stable library"* — but not the consequence. The
stable keys turn a duplicate *window* (a display defect) into a fatal
*exception*.

Why it matches C1–C3: only a live write shifts windows (C1), only a page
fetch can land on a shifted window (C2), and all five sections share the
pager + key convention (C3).

### H2 — Original-size cover decode / OOM (demoted, kept as fallback)

The P0 record suspected original-size art (`sizePx = null`) during the
per-album walk. Code read 2026-11-17: every cover request reachable from a
section scroll is size-bounded.

- `CoverImage` derives `sizePx` from measured constraints and buckets it
  (`CoverImage.kt:44-56`, `CoverSizing.sizePx` buckets to 64 px, caps at
  1024, D-17); it only falls back to the original key when the cell has no
  measurable dimension, which cannot happen in a bounded grid/list cell.
- `SearchCover` buckets explicitly (`SearchScreen.kt:614-645`).
- Detail headers and now-playing art also render through `CoverImage` with
  fixed token sizes (`DetailScaffold.kt:61-65`, `NowPlayingScreen.kt:366-373`).

So an OOM crash from section scrolling is not reachable through the code as
shipped. Keep it in the device decision table (§3.3) because the logcat
signature decides; do not "fix" caches speculatively. The general
cache/queue cost work stays where it belongs — `ui/scroll-performance.md`
§5 P1.2/P1.3.

### H3 — Mid-load invalidation race (demoted as a state corrupter)

`SectionPager` serializes every mutation on the Main dispatcher behind one
`job` guard (`SectionPager.kt:52-150`); `refresh()` replaces the list
atomically from a single query result, and `loadMore`/`refresh` never
overlap. The race cannot corrupt state or create duplicates by itself. What
it *does* cause is churn: a refresh replaces (and clamps to 200 items) the
window under a scrolling user — the P2.1 backlog item in
`ui/scroll-performance.md`. That is Fix 2 below (approved for this hotfix),
not the crash.

## 3. Device discrimination protocol — run before closing the P0

### 3.1 Reproduction scenario

Fresh install or `pm clear` → configure the provider → start the initial
sync walk → while the walk runs, open Albums, scroll continuously to the
end; repeat for Songs and Artists; then take a second pass after the walk
finishes (control). The crash should land within the first pass if H1 is
right.

### 3.2 Capture

```bash
PKG=app.omnisonic
adb logcat -c
# [run the scenario; when the app dies]
adb logcat -b crash -d > /tmp/crash-buffer.txt
adb logcat -d | grep -E "FATAL EXCEPTION|IllegalArgumentException|already used|OutOfMemory|failed to allocate" > /tmp/crash-match.txt
```

The app has no crash reporter or log viewer (`KNOWN_ISSUES.md` P2), so
logcat is the only channel. Keep the screen unlocked (Wi-Fi adb drops when
it locks).

### 3.3 Signature decision table

| Logcat signature | Conclusion | Next |
|---|---|---|
| `IllegalArgumentException: Key "…" was already used` | H1 confirmed | land Fix 1 (+ Fix 2) |
| `OutOfMemoryError` / `failed to allocate …` | H2 — a size path was missed | extend this doc with the failing call site before touching caches; pull `scroll-performance.md` P1.2 forward only with evidence |
| `SQLiteException` / `IllegalStateException` / native crash | a fourth class | extend this doc; do not guess a fix |
| No crash in the capture | timing-dependent reproduction | repeat with the smallest library that still walks; if still clean, keep the P0 open as `[UNKNOWN device]` and land Fix 1 test-first (the duplicate window is proven at JVM level) |

Pre-fix reproduction is preferred but not a blocker for the fix: the
duplicate window and its lazy-key consequence are provable in a JVM test, so
an unavailable device session does not stall the code work — it only leaves
the device gates `[UNKNOWN]`.

## 4. Implementation record (2026-11-17)

Written test-first: the RED run (23 tests, 4 failing) reproduced the
duplicate window before any production edit — the mid-order insert case
returned ids 55–60 a second time (page requested at OFFSET 60 held ids
55–114 while ids 1–60 were loaded), the fully-duplicate case repeated all of
1–60, the empty-page case left `hasMore = true`, and the scroll case refreshed
while scrolling. After the fix, `LibraryBrowseViewModelTest` is **24 tests,
0 failures** (the 24th is the added replay-race case).

Status: `[VERIFIED code/JVM]` — focused suite, both module suites
(`:feature-library` 122, `:core-data` 309) and `:app:assembleDebug` green,
banned-name scan clean. Device status: `[UNKNOWN device]` (below).

### Fix 1 — Overlap-proof append (`:feature-library`)

Implemented in `SectionPager`:

1. `keyOf: (T) -> Long` is a required constructor parameter. All eight
   constructions pass the row id (`{ it.id }`): the five shell pagers in
   `LibraryBrowseViewModel`, and the artist-albums, playlist-entries and
track pagers in `DetailViewModel`.
2. The append path dedupes the fetched page against the loaded window:
   `page.items.filterNot { keyOf(it) in loadedIds }`.
3. `nextOffset` is an explicit cursor: set to the first window's size after
   `loadInitial`, advanced by the **raw** fetched count (`requestOffset +
   page.items.size`) after a successful append even when every row was a
   duplicate, left untouched on a failed append (retry re-requests the same
   offset), and reset to the replacement window's size after a refresh.
4. `hasMore = page.hasMore && page.items.isNotEmpty()`, so a raw empty page
   terminates paging and clears `appending` while leaving the cursor
   consistent.
5. Rows skipped by an OFFSET shift stay absent until the next invalidation
   refresh re-reads the window. Accepted for P0: no duplicate, no crash.

Cursor semantic note (resolves the brief's open point): the cursor starts at
the **first fetched window size** (`page.items.size`), not the configured
`pageSize`. A full page makes them equal; a short terminal page would set
`hasMore = false`, so no append is attempted. This is deliberate and is what
the implementation and tests use.

### Fix 2 — Scroll-gated refresh (P2.1-option-3 / P2.3 slice, approved 2026-11-17)

Implemented as a `setScrolling(Boolean)` command rather than a pager-owned
state observer:

- `SectionPager.refresh()` returns early and sets `refreshDeferred` while
  `scrolling`; `setScrolling(false)` replays one refresh if deferred.
- The UI reports scroll state from `snapshotFlow { isScrollInProgress }` in
  `ScrollGateReporter` (`LibrarySectionScreen`). `CoverGridBody` observes a
  `LazyGridState` (`rememberLazyGridState`) and `SongListBody` a
  `LazyListState` (`rememberLazyListState`) — the initial edit's shared
  `LazyListState` for the grid was a compile blocker and is corrected.
- Leaving the section reports `false` from `DisposableEffect.onDispose`, so a
  deferred refresh cannot be parked forever.
- Callback identity: the route passes inline lambdas, so the observer effects
  are keyed on `Unit` and read both the state predicate and the callback
  through `rememberUpdatedState`. Recomposition therefore cannot restart the
  collector or emit a spurious `false` that would flush a deferred refresh
  mid-scroll.
- In-flight replay: `replayPendingRefresh()` re-checks `scrolling` and moves
  the request to `refreshDeferred` when the user started scrolling during the
  job, instead of calling `launchRefresh()` directly. Regression test:
  `a refresh pending from an in-flight load waits while scrolling`.

### Non-goals (still deferred)

- **Keyset/seek paging** (`ui/scroll-performance.md` §5 P2.2, schema v2 +
  `sortKey` + index): unchanged backlog; the hotfix makes the crash class
  unreachable, it does not remove the O(n) page cost or the shift itself.
- **Positional lazy keys** (`key = null`): not used; stable row-id keys stay.
- **Global invalidation debounce / shell-hoisted pagers**: unchanged (D-16);
  Fix 2 gates the refresh, it does not remove it.
- **4d gesture fix and Roborazzi goldens**: untouched (no frame change).
- **Cover caches / OOM work**: untouched (H2 still a logcat fallback only).

## 5. Acceptance gates

JVM — `[VERIFIED 2026-11-17]`:

- [x] Mid-order duplicate, fully-duplicate cursor and raw-empty-page cases
  were RED before Fix 1 and are green after (they also pin unique ids and a
  cleared `appending`).
- [x] Scroll-gate test proves an invalidation during scrolling does not
  replace `items` and one refresh happens at scroll end.
- [x] Replay-race test proves a pending refresh does not bypass the gate when
  scrolling starts before the in-flight job finishes.
- [x] Existing refresh-mid-load replay and static-library cases stay green:
  `LibraryBrowseViewModelTest` 24/24; `BrowsePagingTest` 8/8.
- [x] `:feature-library` 122 and `:core-data` 309 unit tests, 0 failures.
- [x] `:app:assembleDebug` `BUILD SUCCESSFUL` (Hilt graph + APK).
- [x] Banned-name scan clean over the touched files (scanner self-test 24/24).

Device (release build, data preserved, screen unlocked) — `[UNKNOWN device]`
until run. The attempted pre-fix sessions are listed in
`.agents/plans/next-session-prompt.md`; none captured a crash signature:

1. Fresh install → sync walk → scroll Albums, Songs, Artists, Genres,
   Playlists to the end during the walk: no crash.
2. Post-fix `logcat -b crash` contains none of the §3.3 signatures during
   the same pass.
3. The second pass after the walk still feels no worse than the first
   (Fix 2 did not regress the live refresh).
4. Record the user's verbatim judgement + the capture file name in the task
   note and `progress/STATE.md`; never mark "fixed" on a device gate that
   did not run.

The implementation therefore lands as "fix verified on the JVM, device
acceptance pending". The P0 stays open in `progress/KNOWN_ISSUES.md` until
the pass above actually runs.

## 6. Do not re-litigate (already ruled out)

- **Gesture ownership** — 4d fixed it; `LibraryScrollGestureTest` guards it
  (`ui/gesture-contract.md`). This bug crashes; that one froze.
- **Frame production** — the 2026-11-11 matrix measured healthy tails in
  every scenario; a crash leaves no jank number to read.
- **Cover sizing for lists** — D-17 + bucketed `CoverImage`/`SearchCover`
  are in place; H2 is a fallback signature, not a working theory.
- **Pager state corruption by the refresh race** — the Main-dispatcher job
  guard rules it out.

## 7. File map

| Concern | File |
|---|---|
| Pager engine / offset + append | `feature-library/.../browse/SectionPager.kt` |
| Shell-hoisted pagers / feed collection | `feature-library/.../browse/LibraryBrowseViewModel.kt`, `ui/LibrarySectionRoute.kt` |
| Lazy keys / section bodies | `feature-library/.../ui/LibrarySectionScreen.kt` |
| Page contract (one-past probe, limits) | `core-data/.../browse/BrowseRepository.kt`, `browse/BrowsePage.kt` |
| Invalidation feed | `core-data/.../sync/LibraryInvalidation.kt` |
| Sync walk (per-album transactions, insert positions) | `core-data/.../sync/SubsonicLibrarySyncer.kt` |
| Cover request sizing (verified bounded) | `feature-library/.../ui/CoverImage.kt`, `CoverSizing.kt`; `feature-search/.../ui/SearchScreen.kt` |
| Pager tests | `feature-library/src/test/.../browse/LibraryBrowseViewModelTest.kt`, `core-data/src/test/.../browse/BrowsePagingTest.kt` |
| Docs to update when the fix lands | this file, `ui/library-browse.md`, `ui/scroll-performance.md` §5 P2, `decisions.md` D-27, `progress/*`, `.agents/memory/*` |

## 8. Open questions

1. **Fix 2 in scope** — resolved: yes (approved 2026-11-17). It is the
   P2.1-option-3/P2.3 slice and ships with Fix 1.
2. **Device session** — the pre-fix capture is preferred; if no session is
   available the fix lands test-first and every device gate stays
   `[UNKNOWN device]`. Do not weaken the gates.
3. **D-27 wording** — proposed: "Library paging is overlap-proof: appends
   carry an explicit cursor and dedupe by row id; refreshes pause while the
   list is scrolling and replay once." Record in `docs/omnisonic/decisions.md`
   when the code lands.
