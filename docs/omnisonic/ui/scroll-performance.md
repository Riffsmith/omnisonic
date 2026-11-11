# Library scroll performance — `ui/scroll-performance.md`

Purpose: pin down why the library surfaces (hub, albums, artists, genres,
songs, playlists) still feel sticky on a cold process after task 4d fixed the
gesture-ownership bug, and give the next session a measurement-first,
decision-complete plan to finish the job instead of re-deriving it.

How derived: third live-device session (2026-11-10, release build, post-4d)
with the user's symptom report; the shipped code read end-to-end (library UI,
paging engine, invalidation feed, Coil image loader, provider-client registry,
Room queries and the merged baseline profile in the built APK); the gfxinfo
metric semantics from the Android platform performance docs; Coil's own
guidance on list scrolling and crossfade. Device numbers for the *new*
symptom do not exist yet — every device-gated step below is `[UNKNOWN]`.

Confidence: root-cause *candidates* are code-verified where tagged; the
ranking is `[INFERRED]` until the discrimination protocol in §4 is run. The
shipped costs themselves are `[VERIFIED]` against the file references.
**[Update 2026-11-11 — the matrix ran (§4.5): H1/H2/H3 demoted as causes of
the primary symptom; the real cause was a second gesture-ownership bug
(`consumeSurplusInput`, Main-pass ancestor consumption) — see §5 P0 and
`ui/gesture-contract.md`. P1–P4 stand as general cost work.]**

Date: 2026-11-10 (matrix results + P0 added 2026-11-11)

Links: `ui/library-browse.md` (screen/paging/cover pipeline), `ui/gesture-contract.md`
(edge-exclusion bug record), `providers/library-sync-ux.md` (invalidation +
first-run sync), `decisions.md` D-16/D-17, `.agents/plans/phase-5-tasks.md`
task 4e.

---

## 1. Symptom record (third device session, 2026-11-10)

Release APK, data cleared at install, provider configured by the user, first
run of each library section:

> "So when the first time after the provider setup I look up the
> albums/tracks/artists/genres, the scroll doesn't happen for a while. Then it
> happens and gets stuck. It occurs until I reach the end. After that scroll
> happens nicely (not absolutely flawless but just minor ms delays rarely).
> This is valid for all screens (songs/albums/artists/genres). So if I spend
> more time in the app, the less the scroll problem occurs. However, if I
> terminate the app and open it again, it takes time for scroll to start
> feeling fluid."

Decoded into testable facts:

| # | Observation | What it constrains |
|---|---|---|
| F1 | Worst on the **first pass** through a section after setup | cold cache / cold JIT / first page loads |
| F2 | **All** sections including albums (274 rows) and songs (1374) | not a pure large-table effect |
| F3 | Recovers once the user has scrolled **to the end** | correlates with "all pages loaded / all covers loaded" |
| F4 | Better the longer the **process** stays alive | in-process warm-up (memory cache, JIT code cache, SQLite page cache) |
| F5 | **Relaunch resets** the problem | per-process state, not on-disk state (disk cache, DB file survive) |
| F6 | User does not experience it as a freeze; content moves, then stalls | frame production / input handling spikes, not a dead gesture |

F6 is the key difference from task 4d. The 4d bug produced a *static screen*:
vertical drags were consumed on the Initial pass, so nothing moved at all
(`ui/gesture-contract.md`, bug record). That class is now covered by
`LibraryScrollGestureTest` (3 tests, real `performTouchInput` vertical drags).
The remaining symptom is **stop-and-go**, which points at main-thread cost or
CPU contention, not gesture ownership.

## 2. Why gfxinfo alone did not catch (and still will not catch) this

The 4d baseline (same phone, release, 5-minute active scroll) was:

```
frames 11,262 · janky 1.42% · p50 17ms · p90 19ms · p99 32ms
high-input-latency 17,585 · slow-UI-thread 127 · slow-bitmap-upload 3
```

Frame *production* was healthy the whole time the screen was frozen, because a
consumed drag produces no frames at all — there was nothing to be janky. The
number that moved was **`Number High input latency`** (frames whose input
dispatch exceeded the deadline, per the platform docs). Consequences for this
bug:

- Do **not** accept or reject a fix on `Janky frames %` alone; it was 1.42%
  while the app was unusable.
- `high-input-latency` is the sensitive counter for "the main thread was busy
  when the touch arrived".
- `dumpsys gfxinfo framestats` (per-frame columns) is what distinguishes
  *queued input* (`HandleInputStart - OldestInputEvent` large) from *slow input
  handling* (`AnimationStart - HandleInputStart` large) from *slow
  layout/draw*. Use the parser in §4.4.
- Subjective feel remains the primary acceptance signal (§6).

## 3. Cost inventory — what the code actually does per pass

All five library bodies are `LazyColumn` / `LazyVerticalGrid` with stable keys
(`feature-library/ui/LibrarySectionScreen.kt`). Nothing per-frame is obviously
pathological. The costs are concentrated in three stacks and scale with
"how many items have been composed/fetched so far" — which is exactly F3.

### H1 — Cover pipeline (network + decode + crossfade) — highest expected yield

Per newly composed tile, `CoverImage` (`feature-library/ui/CoverImage.kt:38-61`)
requests a `CoverImageKey` through the app `ImageLoader`. That request path
does, per fetch:

1. `CoverImageFetcher.fetch()` → `resolveUrl` → `ProviderClientRegistry.providerFor(id)`,
   which takes a **global mutex and runs `SELECT * FROM providers WHERE id = ?`**
   (`ProviderClientRegistry.kt:104-118`, `:51-53`).
2. `ProviderRoutedCallFactory.newCall()` → **`runBlocking { apiClientForUrl(url) }`**
   (`ProviderRoutedCallFactory.kt:19-22`). `apiClientForUrl` holds the same
   mutex while it **`SELECT * FROM providers`** (one or two full scans) and then
   calls `cachedLocked`, which runs **another** `SELECT` (`ProviderClientRegistry.kt:85-94`).
3. The loader is built with **`crossfade(true)` globally**
   (`app/src/main/java/app/omnisonic/image/CoverImageFetcher.kt:60`).

So one cover = 3 provider-table queries + 2–3 acquisitions of one global mutex,
executed on Coil/OkHttp threads (`runBlocking` parks the calling thread while
Room's executor answers). OkHttp's default dispatcher caps concurrent requests
per host (5 in 4.12), so image fetches queue behind each other while the mutex
serializes the rest. Nothing in the app bounds Coil's fetch/decode parallelism
(it uses its defaults) or sizes the memory/disk caches.

The crossfade is the part that matches F5 especially well: Coil
**skips the fade when the result comes from the memory cache** and fades for
network *and disk* results. A warm process (F4) has bitmaps in the memory
cache → no fade, no decode; a relaunched process (F5) has an empty memory cache
→ every cover is re-read from disk, re-decoded and **faded again**, even though
the network is never touched. A fade is per-image paint work that lands exactly
while the user is scrolling the first pass (F1) and disappears once every cover
has been composed (F3).

Relevant Coil guidance: crossfade is cheap on a detail screen and expensive in
a scrolling list; fixed item size + explicit request size is the first
optimisation; `SubcomposeAsyncImage` (subcomposition) is the one to avoid in
lists. This app already uses `AsyncImage` and explicit sizes (D-17), so those
two are in place.

### H2 — Cold-process compilation (profile coverage) — secondary, but explains F5

The merged profile in the built release APK is **not empty**: AGP merges the
library profiles, and the current artifact carries Compose runtime/ui, coil3
(including `AsyncImageKt`) and coroutines rules (390 KB of text rules → 6.3 KB
binary `assets/dexopt/baseline.prof`).

But the project's own profile is a **placeholder** — 
`baseline-profile/src/main/baseline-prof.txt` contains only comments — and the
merged artifact contains **zero `app/omnisonic` and zero `androidx/room`
methods** (verified by grepping the merged profile). So the app's scan/cover
composition path, the paging engine, the invalidation feed and Room's generated
DAO code are interpreted/JIT-compiled on the first pass of every new process.
That produces exactly the "sticky until I've used it for a while, sticky again
after relaunch" shape (F1/F4/F5), with stalls concentrated in the first
traversal (F3).

Do not guess between H1 and H2: the `compile -m speed` probe in §4.3 separates
them in one command.

### H3 — Paging + invalidation churn — the "gets stuck until the end" shape

- Page fetch is `LIMIT/OFFSET` over a **full-table scan plus a temp sort**:
  every browse query orders by an expression —
  `ORDER BY COALESCE(sortTitle, title) COLLATE NOCASE, title COLLATE NOCASE, id`
  (`SongDao.kt:155-163`, `AlbumDao.kt:110-117`, `ArtistDao.kt:94-102`,
  `GenreDao.kt:43-51`) — which no index can serve. `songs` and `genres` have no
  index on the sort column at all; albums/artists only have
  `(providerId, sortTitle)`. Each page and each `COUNT(*)` is therefore
  O(rows) CPU on Room's query executor, and `OFFSET` re-walks the discarded
  prefix.
- `SectionPager.loadMore()` appends `current.items + page.items` **on
  `viewModelScope` (Main)** (`SectionPager.kt:80-104`). At 1.4 k items this is
  a list copy per page — small, but it is main-thread work that scales with the
  list.
- `SectionPager.refresh()` re-fetches `items.size` **capped at
  `MAX_PAGE_LIMIT` = 200** and **replaces the whole list**
  (`SectionPager.kt:121-147`). A user who has scrolled past 200 items and gets
  any catalog invalidation (a running sync writes one transaction per album —
  `SubsonicLibrarySyncer.kt:339`, and `LibraryInvalidation` collapses bursts to
  one refresh per 250 ms) has the list truncated back to 200, which clamps the
  scroll position. During the first-run sync this can fire repeatedly.
- `LibraryBrowseViewModel` is shell-hoisted, so **all five pagers** collect the
  invalidation feed from app launch; any open section refreshes on every quiet
  period. There is no "pause while the user is dragging" rule.

This is the only candidate that naturally stops at "reached the end": once
`hasMore = false` there are no more page/count queries, and the refresh window
stops growing. It is also the candidate that a 274-album library can still show
(F2) if the *count* and refresh are the expensive parts, or if covers (H1) are
the real cost.

### H4 — Per-item composition micro-costs — amplifiers, not the cause

- `CoverImage` wraps every cell in **`BoxWithConstraints`** (`CoverImage.kt:38`),
  a `SubcomposeLayout`: each tile's content is subcomposed during measure and
  cannot ride the lazy-list prefetch pass. One per visible tile.
- `SongRow` calls `formatDuration`, which does `String.format` per row per
  composition (`ui/Common.kt:22-27`, called at `LibrarySectionScreen.kt:389`),
  and builds a `TextStyle` inline (`tokens.typography.caption.toTextStyle()`).
- The section header calls `NumberFormat.getIntegerInstance()` on recomposition
  (`LibrarySectionScreen.kt:88`).
- Lazy items declare keys but no `contentType` (`CoverGridBody`,
  `SongListBody`).

None of these explains seconds of stall, but they add main-thread work per new
row and become visible once H1/H3 are fixed. Fix them in the same task, after
the measurement.

## 4. Discrimination protocol — run this before changing code

Fill the matrix on the user's phone, release build
(`./gradlew :app:assembleRelease -Pomnisonic.tmpReleaseSign=true`,
`adb install -r -g`, then **force-stop** before every cold scenario). Keep the
screen unlocked; the Wi-Fi adb link drops when it locks.

### 4.1 Scenarios

| # | Scenario | Paging | Covers | JIT | Isolates |
|---|---|---|---|---|---|
| A | cold process, Wi-Fi on, first pass to the end of **albums** and **songs** | yes | network+decode | cold | baseline |
| B | cold process, **airplane mode**, same pass | yes | fail fast / disk only | cold | network/cover fetch |
| C | cold process, same build after **`cmd package compile -m speed -f`** | yes | network+decode | AOT | JIT / verification |
| D | warm process: same sections a second time (no force-stop) | yes | memory | warm | in-process cache |
| E | repeat the pass **after reaching the end** (scroll up, then down) | no | memory | warm | page loads |
| F | **hub** (5 static tiles, no DB, no covers) on a cold process | – | – | cold | shell/Compose/device |

### 4.2 Commands per scenario

```bash
PKG=app.omnisonic
adb shell am force-stop $PKG
adb shell dumpsys gfxinfo $PKG reset
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
# [user scrolls the section end-to-end; note the wall-clock seconds]
adb shell dumpsys gfxinfo $PKG > /tmp/gfx-A.txt
adb shell dumpsys gfxinfo $PKG framestats > /tmp/gfx-A-frames.txt
```

AOT probe (scenario C):

```bash
adb shell cmd package compile -m speed -f $PKG     # AOT-compile everything
adb shell cmd package dump $PKG | grep -A6 "Dexopt state"   # confirm "speed"
# ...run scenario A again...
adb shell cmd package compile -m verify -f $PKG    # revert (or reinstall the APK)
```

Per-thread CPU during a stall (run in a second shell while scrolling):

```bash
PID=$(adb shell pidof $PKG)
adb shell top -H -b -d 1 -n 8 -p $PID | grep -E "PID|omnisonic|RenderThread|DefaultDispatcher|OkHttp|pool"
```

System trace (best single artifact; open in <https://ui.perfetto.dev>):

```bash
adb shell perfetto -o /data/misc/perfetto-traces/scroll.perfetto-trace -t 15s \
  sched freq idle am wm gfx view binder_driver hal dalvik input res memory
# [scroll during the 15 s window]
adb pull /data/misc/perfetto-traces/scroll.perfetto-trace
```

Look for: main thread runnable-but-not-running gaps (CPU stolen by
`DefaultDispatcher-worker-*`/`OkHttp`/`RenderThread`), `Choreographer#doFrame`
overruns, and whether the stall window is dominated by `ImageView`/codec work or
by database/binder activity.

### 4.3 Reading the result — decision table

| A vs B | A vs C | A vs E | Conclusion | Fix order |
|---|---|---|---|---|
| B smooth | – | – | network/cover **fetch** is the cost | P1.1–P1.3 |
| – | C smooth | – | **JIT/verification** is the cost | P3.1 |
| – | – | E smooth | **page/count queries** are the cost | P2 |
| B and C both slow, E still slow, **F janky** | | | shell/Compose/device-level, or decode | P1.2 then P4 |
| B and C both slow, E smooth | | | paging + decode mix | P1.2 + P2 |
| A smooth | | | intermittent (gesture or sync) — re-check `high-input-latency` and the 4d rule | – |

If A and B are both bad but C is smooth, H2 wins even though the symptom is
about lists: the app's own code is cold. If C is smooth *and* B is smooth, run
the fixes in P1 anyway — they are independently worthwhile.

Note on B: with the provider unreachable, Coil fails fast, so the decode path
still runs for disk-cached covers. A smooth B is strong evidence for the
network side; a still-bad B with a smooth C points at JIT.

### 4.4 framestats parser (paste into a file, run after each scenario)

`dumpsys gfxinfo <pkg> framestats` prints a CSV block between `---PROFILEDATA---`
markers. Per frame:

- **input queue wait** = `HandleInputStart - OldestInputEvent` (skip
  `OldestInputEvent == 9223372036854775807`, i.e. no input that frame)
- **input handling** = `AnimationStart - HandleInputStart`
- **layout/measure** = `PerformTraversalsStart - AnimationStart`
- **draw/issue** = `SwapBuffers - DrawStart` (or `IssueDrawCommandsStart`)
- **frame total** = `FrameCompleted - IntendedVsync`

```python
import statistics, sys

path = sys.argv[1]
rows, header, in_block = [], None, False
for line in open(path):
    line = line.strip()
    if line == "---PROFILEDATA---":
        in_block = not in_block
        continue
    if not in_block or not line:
        continue
    cols = line.split(",")
    if header is None:
        header = cols
        idx = {name: i for i, name in enumerate(header)}
    elif cols[0].isdigit():
        rows.append(cols)

def ms(name):
    return [int(r[idx[name]]) for r in rows]

def pct(values, p):
    if not values:
        return float("nan")
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * p))] / 1e6

pairs = [
    ("input_queue", lambda r: int(r[idx["HandleInputStart"]]) - int(r[idx["OldestInputEvent"]]),
     lambda r: int(r[idx["OldestInputEvent"]]) != 9223372036854775807),
    ("input_handle", lambda r: int(r[idx["AnimationStart"]]) - int(r[idx["HandleInputStart"]]), None),
    ("traversals", lambda r: int(r[idx["PerformTraversalsStart"]]) - int(r[idx["AnimationStart"]]), None),
    ("draw", lambda r: int(r[idx["SwapBuffers"]]) - int(r[idx["DrawStart"]]), None),
    ("frame_total", lambda r: int(r[idx["FrameCompleted"]]) - int(r[idx["IntendedVsync"]]), None),
]
print("frames:", len(rows))
for name, fn, keep in pairs:
    vals = [fn(r) for r in rows if keep is None or keep(r)]
    print(f"{name:13s} n={len(vals):5d} p50={pct(vals,.5):7.2f} "
          f"p95={pct(vals,.95):7.2f} p99={pct(vals,.99):7.2f} "
          f"max={max(vals, default=0)/1e6:7.2f} over16ms={sum(v > 16_666_667 for v in vals)}")
```

Interpretation: large `input_queue` p95 ⇒ the main thread was busy before it
even looked at the touch (CPU contention or a long frame/GC). Large
`input_handle` p95 ⇒ pointer handling itself is heavy (gesture modifiers,
semantics). Large `traversals`/`draw` ⇒ measure/layout/graphics. All small with
a bad feel ⇒ look at input injection/dispatch or the device's own scheduling.

### 4.5 Matrix results — 2026-11-11 (fourth device session, release build)

| # | Scenario | frames | janky % | high-input-latency | hi-lat/frames | Result |
|---|---|---|---|---|---|---|
| A | cold + Wi-Fi | 8,101 | 3.23 | 13,626 | 1.68 | drag-start swallow |
| B | cold + airplane | 8,217 | 1.91 | 13,499 | 1.64 | **same** → not network |
| C | cold + AOT | — | — | — | — | **device-blocked** (see below) |
| D | warm process | 2,354 | 3.14 | 4,113 | 1.75 | **same** → not process warmth |
| E | all pages loaded | 7,115 | 1.45 | 12,314 | 1.73 | **same** → not page loads |
| F | hub (cold) | 3,370 | 2.79 | 4,750 | 1.41 | horizontal swipes flawless; hub vertical untestable (too little content) |

- The user re-reported the swallowing in **every** scenario and **overturned
  F4** mid-session: "the more the app runs, the less it occurs" was an
  artifact of testing with fast continuous scrolling (fewer drag starts), not
  process warm-up.
- Frame tails (last-120-frames ring buffer) were healthy in all scenarios:
  `input_handle` p50 0ms, `pre_input_wait` p50 <1ms, `traversals` p50 ~2–4ms —
  frame production was never the problem. `framestats` is a 120-frame ring
  buffer, so it only samples the END of a pass — capture per segment if a
  first-pass segment is needed.
- **Scenario C is impossible on this phone:** ColorOS rejects shell-initiated
  dexopt for ALL packages (including `com.android.settings`) —
  `cmd package compile -m speed -f` fails silently ("Failed to cpmpile !",
  no dex2oat log) and `cmd package bg-dexopt-job` returns "Failure". JIT
  isolation stays `[UNKNOWN device]` on this hardware.
- The decisive data was the user's refined symptom: **every new touch
  sequence can be swallowed for a random number of tries (even after idle
  waiting); an established drag/fling is smooth; a drag started
  simultaneously with navigation works perfectly.** That is a gesture
  ownership race, not CPU contention — see §5 P0.

## 5. Fix plan

Execute in order, re-running the §4 matrix after each step. Do not bundle all
of P1–P4 into one unmeasured change; the point is to know which one bought the
fix.

**Status after the 2026-11-11 matrix: the §4.3 decision table's last row
("A smooth? NO — intermittent (gesture or sync)") was the live one. The
primary fix turned out to be P0 below — a second gesture-ownership bug — which
shipped the same day and was device-verified. P1–P4 remain real costs and the
follow-up work queue, re-ranked; P1.4 is dropped (A was not network-bound).**

### P0 — Drag-start swallowing — LANDED 2026-11-11 (same session)

**Root cause:** `consumeSurplusInput` on the NavHost layer (task 2a) consumed
every move on the Main pass; `detectDragGestures` (which backs every
`scrollable` in the pinned Compose 1.7.4) re-checks the same event on
`PointerEventPass.Final` and cancels when anything consumed it — an ancestor's
Main-pass consumption therefore cancels every child drag start whose per-event
delta stays under touch slop. Full mechanism + fix + tests: `ui/gesture-contract.md`
§"Second bug record". The fix (inert `inputBarrier()` + pager
`userScrollEnabled` gating) was verified on the device the same session:
slow drag starts work on Albums/Songs; 9,662 frames, 2.50% janky post-fix.

### P1 — Cover pipeline (mostly `:app`, small `:feature-library`) — general cost work, demoted

**P1.1 Per-surface crossfade, off in lists.** Remove the global
`crossfade(true)` from `buildImageLoader`. Keep the art fade where it is a
single large image (detail header, now-playing) by enabling it per request
(`ImageRequest.Builder.crossfade(...)` using `MotionTokens.artCrossfade`,
default 200 ms) in those call sites only. Lists/grids get no fade.
Tests: `CoverImageLoaderTest` keeps the cache-identity assertions; add one that
the default request has no transition and the detail request has one.

**P1.2 Explicit caches + bounded concurrency.** In `buildImageLoader`:

```kotlin
ImageLoader.Builder(context)
    .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.15).build() }
    .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(...).build() }
    .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(4))
    .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(2))
    .crossfade(false)
```

`ImageLoader.Builder.fetcherCoroutineContext` / `decoderCoroutineContext` exist
in the pinned Coil 3.0.0 (verified against the AAR). Pick the memory percent
from a quick `dumpsys meminfo` check on the phone rather than assuming; 25% of
`Runtime.maxMemory` is Coil's default and is aggressive on low-RAM devices.
Also set `Precision.INEXACT` (already the list-friendly choice) and keep the
explicit `sizePx` from D-17.

**P1.3 Take the DB and `runBlocking` out of the image request path.** Resolve
the provider and its API client **once per image request, off the main thread**
and without the `selectAll()` URL match:

- `ImageLoaderModule`: drop `ProviderRoutedCallFactory`; instead pass a
  `suspend (CoverImageKey) -> Resolved` (provider + client) into
  `CoverImageFetcher`.
- `CoverImageFetcher.fetch()`: `val provider = ...; val client = ...;` then
  `OkHttpNetworkFetcherFactory(client).create(url.toUri(), options.copy(
  diskCacheKey = key.cacheKey), imageLoader)` — one registry pass per request,
  no `runBlocking`, no provider-table scan.
- `ProviderClientRegistry`: leave the `updatedAt` safety check and the mutex
  semantics alone for now (a test covers rebuild-on-row-change); just stop the
  per-call `selectAll()`. Record the follow-up in
  `.agents/memory/open-questions.md`: explicit invalidation on every provider
  edit would let `cachedLocked` skip the SELECT entirely.
- The same `runBlocking { httpClientFor(...) }` shape exists at
  `playback/di/PlaybackWiringModule.kt:110`; out of scope for scroll, but note
  it in the task record.

Tests: a plain/Robolectric test that one cover fetch performs exactly one
provider lookup and zero `selectAll()` calls (fake registry counter), plus the
existing cache-identity test.

**P1.4 (dropped 2026-11-11)** — post-sync disk-cache warm: the §4.5 matrix
showed A ≈ B (airplane mode), so the network was never the cost. Not pursued.

### P2 — Paging + invalidation (`:feature-library`, `:core-data`)

**Re-ranked after the matrix:** H3 is not the drag-start cause, but it is the
best explanation for the **fresh-install-only** mid-list stall pattern
("scrolls, stucks at a specific part, scrolls again, stucks, then smooth at
the end" — the page-boundary shape) and remains a real 10k-library scaling
defect. Keep P2.1/P2.3 (cheap) and P2.2 (the schema-v2 sortKey migration —
user confirmation still pending, §9.2).

**P2.1 `refresh()` must never shrink the window under the user.** Options, in
order of preference:

1. Keep the loaded window intact: re-fetch only the pages that are already
   loaded **and** whose content can have shifted, splicing them in — complex.
2. Refresh only the first `items.size.coerceAtMost(MAX_PAGE_LIMIT)` items **and
   keep the untouched tail** — not order-correct when rows are inserted.
3. **Pause refreshes while the list is being dragged/flung, and cap the refresh
   window at `MAX_PAGE_LIMIT` without replacing more items than are loaded**;
   if `items.size > MAX_PAGE_LIMIT`, set a `stale` flag and refresh on the next
   section entry / `loadInitial` instead. Simple, deterministic, and enough for
   Phase 5.
4. Refresh only the section the user can see (add an `active` flag set by the
   section destination), and coalesce to 1 s while a sync is running.

Test: `SectionPagerTest` — scroll past 200 items, deliver an invalidation,
assert the list length and the state are unchanged (and the stale flag is set
for option 3).

**P2.2 Make the browse ordering index-friendly, then move to keyset paging.**
Ordering by `COALESCE(...)` defeats every index. Preferred shape (Room-friendly,
no COLLATE mismatch):

- Backfill `sortTitle` for every row (it is written by the syncer at
  `SubsonicLibrarySyncer.kt:244/351/396`; nulls only exist for rows written
  before that / by the local provider).
- Add a stored `sortKey` column = `TextNormalizer.sortKey(title)` (non-null,
  defaulted to the lowercased title on migration), indexed:
  `@Index(value = ["sortKey", "title", "id"])` on songs/albums/artists/genres/
  playlists.
- Query `ORDER BY sortKey, title COLLATE NOCASE, id LIMIT :limit` (no
  `COALESCE`).
- Replace `OFFSET` with keyset/seek paging:
  `WHERE (sortKey, title, id) > (:lastKey, :lastTitle, :lastId)` — SQLite
  3.22 (API 29 floor, `storage/sqlite-api29-floor.md`) supports row values.
  This also removes the "sync shifts rows between pages" caveat in
  `ui/library-browse.md`.

This is schema v2 + a migration + exported `2.json` + a migration test. It is
the only change here that makes paging O(log n) instead of O(n) per page, so
do it even if H3 turns out to be secondary — a 10 k-library is the stated
target.

Test: `BrowsePagingTest` grows a keyset case (insert rows, page by seek, no
duplicates/gaps, ordering stable); migration test opens v1 with rows and
asserts the backfill + index.

**P2.3 Do not refresh while a scroll is in flight.** `SectionPager` gets a
`setActive(Boolean)` (called from `LaunchedEffect` in the section route) and a
`setScrolling(Boolean)` (from `LazyListState.isScrollInProgress` via
`snapshotFlow`); invalidation sets `stale` instead of launching while
scrolling or inactive. This is the cheap version of P2.1 option 4 and the
guard against sync-time churn.

### P3 — Cold-start compilation (`:baseline-profile`, `:app`)

**P3.1 Generate a real baseline profile.** The module is wired
(`app/build.gradle.kts:89`, `androidx.baselineprofile` 1.3.1, profileinstaller
already on the runtime classpath) but the profile is a placeholder. On the
phone:

```bash
./gradlew :baseline-profile:generateReleaseBaselineProfile   # needs the device
```

Cover: app launch → library hub → each section scroll → open a detail →
back. Commit the generated `baseline-prof.txt`, then verify with
`adb shell cmd package dump app.omnisonic | grep -A6 "Dexopt state"` that the
installed APK's profile is non-empty and that the app package appears in the
merged profile (the current artifact has zero `app/omnisonic` entries).

**P3.2 (recommended, phase-permitting) a Macrobenchmark module.** A
`androidx.benchmark.macro` `FrameTimingMetric` test that launches the app,
navigates to Albums, and runs `scrollForward()` for N seconds is the only
harness that reproduces "first pass after launch" repeatably and yields
`frameOverrunMs`. Without it every future session is back to manual feel +
gfxinfo. If adding a module is too big for Phase 5, park it in
`.agents/memory/open-questions.md` with the exact test sketch.

### P4 — Composition micro-costs (`:feature-library`)

- Replace `BoxWithConstraints` in `CoverImage` with a size the caller already
  knows: grid tiles derive it from `LayoutTokens.gridColumnsFor` + page padding
  + grid spacing; rows pass `ImageryTokens.listThumbSize`. Use
  `Modifier.onSizeChanged { }` + `remember` only as the fallback for measured
  cells. Removes one subcomposition per tile.
- Hoist `NumberFormat.getIntegerInstance()` (header) and replace
  `"%d:%02d".format(...)` with plain arithmetic (`"%d:%02d"` is fine for
  correctness; use `StringBuilder`/`(minutes * 100 + seconds)` formatting only
  if profiling shows it).
- Add `contentType` to `CoverGridBody` and `SongListBody` items.

These are safe, testable (render tests + a `CoverImage` size test) and should
land in the same task because they mask the real cost in measurements.

## 6. Acceptance gates (do not mark any as verified without the device)

**JVM gates (every change):** targeted module tests green; `:app:assembleDebug`
green; banned-name scan clean over touched files; no new public names without
the naming diff.

**Device gates (release build, data preserved, screen unlocked):**

1. **Cold start, first pass, every section:** scroll from top to end of albums,
   artists, genres, songs, playlists with no multi-frame stall. The user's
   own judgement is the acceptance signal; record it verbatim.
2. **Relaunch:** force-stop → relaunch → first pass of the same section must
   feel the same as the warm pass (F5 is the regression that hid behind "it
   gets better with time"). **[Updated 2026-11-11: F4/F5 were artifacts of
   fast-scroll testing — the real invariant is "drag starts always respond",
   now covered by P0. Keep this gate as a smoke check after future changes.]**
3. **After a fresh sync:** the first pass while/after the initial sync must not
   be worse than the steady state (P2.3).
4. **`dumpsys gfxinfo` — recalibrated 2026-11-11:** `Number High input latency`
   is **NOT a usable pass/fail gate on this device**. It stayed ~1.5–1.75×
   frame count in every scenario including the fixed, smooth session: the
   60Hz ColorOS device completes steady-state frames in ~19.9ms, missing the
   16.7ms deadline by presentation cadence alone. Accept instead: subjective
   feel (primary) + janky frames % (must stay ≤ ~3%) + `framestats` tails.
5. **`framestats`:** `input_handle`/`pre_input_wait` p95 under one vsync and
   no frame over ~50ms in a pass segment (mind the 120-frame ring buffer —
   capture right after the segment of interest).
6. If P3.2 lands: `frameOverrunMs` p95 ≤ 0 and p99 within one frame on the
   scroll benchmark, run three times, cold each time.

Record the numbers in the task note + `progress/STATE.md`; mark anything not
run `[UNKNOWN device]`, never "fixed".

## 7. Do not re-litigate (already ruled out)

- **Gesture ownership** — fixed in 4d; `SwipeZoneRules.edgeExclusionClaims`
  consumes only strip-started horizontal drags, `LibraryScrollGestureTest`
  guards it. A dead vertical drag is no longer possible.
- **Rendering pipeline** — p50 17 ms / p99 32 ms / 1.42% janky with 3 slow
  bitmap uploads over 11 k frames. Frame production is not the problem.
- **ColorOS freezer** — zero `OplusHansManager` events during a 4-minute
  foreground scroll session; the app-freezer cycles only while backgrounded.
  Battery whitelisting remains a Phase 6+ open question, not this bug.
- **Database size alone** — 274 albums / 1,374 songs was exonerated as the
  *cause* of the 4b freeze at that scale, and a 274-row table cannot produce
  seconds of stall by itself. That does **not** excuse the O(n) query shape in
  P2.2: it is a real scaling defect and it must be measured, not dismissed.
- **Debug build / R8** — these tests are on the R8 release build; "debug is
  slower" is not an explanation here.
- **`LoadMoreFooter` chaining** — fixed in 4d (keyed on `Unit`).

## 8. File map

| Concern | File |
|---|---|
| Cover composable / request size | `feature-library/src/main/java/app/omnisonic/feature/library/ui/CoverImage.kt`, `CoverSizing.kt` |
| Coil loader wiring | `app/src/main/java/app/omnisonic/image/ImageLoaderModule.kt`, `CoverImageFetcher.kt`, `ProviderRoutedCallFactory.kt` |
| Provider/client cache | `core-data/src/main/java/app/omnisonic/core/data/providers/ProviderClientRegistry.kt` |
| Paging engine | `feature-library/src/main/java/app/omnisonic/feature/library/browse/SectionPager.kt` |
| Invalidation feed | `core-data/src/main/java/app/omnisonic/core/data/sync/LibraryInvalidation.kt` |
| Browse queries / indexes | `core-data/.../db/dao/{Song,Album,Artist,Genre,Playlist}Dao.kt`, `db/entity/*.kt`, `db/Migrations.kt`, `core-data/schemas/…/2.json` |
| Section UI | `feature-library/.../ui/LibrarySectionScreen.kt`, `LibrarySectionRoute.kt`, `ui/Common.kt` |
| Baseline profile | `baseline-profile/src/main/baseline-prof.txt`, `app/build.gradle.kts` |
| Docs to update | this file, `ui/library-browse.md`, `ui/gesture-contract.md`, `decisions.md` (new D-number if P2.2 lands), `progress/*` |

## 9. Open questions for the user

1. **Art crossfade is a product choice.** `MotionTokens.artCrossfade` (200 ms)
   exists for the detail/now-playing surfaces. The recommendation here is
   "fade single covers, never list cells". Confirm that is acceptable.
2. **Schema v2 for the sort key** (P2.2) is the only fix that makes paging
   scale to the 10 k-library target; it adds a migration and an exported
   schema. Confirm the migration is in scope for Phase 5 rather than a
   follow-up task.
3. **A Macrobenchmark module** (P3.2) needs a device session and a new Gradle
   module. Confirm whether to add it now (recommended for "once and for all")
   or park it until Phase 12.
