# Search screen UX improvements — `search-ux-improvements.md`

Purpose: Hand off the three `progress/KNOWN_ISSUES.md` P1 search-screen items —
results that update while typing, tapping a song result to play it immediately,
and recents that store/display the picked result instead of the typed query —
and pin the interaction, persistence and playback contracts the fix must
establish. This is a standalone UX fix, **not Phase 6 and not part of any
phase**.

How derived: source review of `SearchViewModel`, `SearchUiState`,
`SearchScreen`, `SearchSource`/`RepositorySearchSource`, `SearchRepository`,
`SearchPreferencesStore`, `PlaybackController`, the `DetailViewModel` play
precedent, the `:app` shell wiring and both test-double sets; the existing
search docs (`search/data-layer.md`, `ui/search-tab-fixes.md`); and the
`SearchPerformanceTest` measurement notes. No device was attached during the
investigation (2026-11-17). Decisions below were taken by the user on
2026-11-17.

Confidence:
- [VERIFIED source] `onQueryChange` only updates state; every search runs
  through `submit()`/`rerunIfActive()`.
- [VERIFIED source] `runSearch` sets `phase = SEARCHING` and `results = null`,
  so the current state machine has no "refreshing while keeping results" state.
- [VERIFIED source] a song row's click opens the album when `albumId != null`
  and does nothing otherwise; no play callback exists in the feature.
- [VERIFIED source] recents are `List<String>` persisted as a JSON string list
  and rendered with a generic icon; recording happens on successful submit only.
- [VERIFIED source] the play contract (queue the loaded window, anchor the
  tapped row, skip null `externalId`) already exists in `DetailViewModel` and
  the Library browse path.
- [VERIFIED source] a worst-case first page over 100k songs measures 0.02–0.8 s
  under Robolectric; the per-page budget is 3 s. There is no device latency
  number.
- [INFERRED] the debounce values and re-query behaviour chosen here are
  responsive enough for typing; this needs the device pass to confirm.
- [UNKNOWN device] typing feel, IME interaction, playback start latency,
  artwork availability for every pick kind, and TalkBack reading order.

Date: 2026-11-17

Links: `../search/data-layer.md` (tokenising, ranking, paging — unchanged by
this fix apart from the recents contract), `search-tab-fixes.md` (bottom field,
focus and IME work this fix builds on), `../playback/queue-restore-and-song-tap-fixes.md`
(the row-tap-to-play precedent), `../decisions.md` (D-30 to be recorded only
after the fix lands), `progress/KNOWN_ISSUES.md`.

---

## Phase boundary (read before coding)

- This is standalone P1 work recorded in `progress/KNOWN_ISSUES.md`; it is
  **outside the phase structure**.
- Phase 6 is **not** being started. Do not touch `.agents/plans/phase-*.md`,
  `.agents/plans/phase-plans.md`, `.agents/plans/roadmap-ref.md`, task
  checkboxes, or any phase status record.
- This is the immediate task before resuming planned phase work. The entry
  point is `.agents/plans/next-session-prompt.md`.
- Do not change the search data layer's tokenising, matching columns, ranking,
  paging, `ProfileScope` exclusion or page-size preference. Only the recents
  persistence contract, the screen's interaction wiring and the ViewModel
  behaviour change.

## Implementation status

**Implemented (2026-11-17), JVM-verified, device gate OPEN.** All three
behaviours are in the working tree; the module suites, the full
`:app:assembleDebug test lint` gate and the banned-name scan are green. No
device was attached, so every device checklist item below stays
`[UNKNOWN device]` and the P1 items remain open.

## Decisions taken (2026-11-17, user-approved)

1. **Debounce = 300 ms** before a live search runs; IME "Search" still executes
   immediately.
2. **Legacy query recents are deleted on upgrade**, not converted or shown.
3. **Recents click re-resolves the row by id first**; the display snapshot is
   only the fallback for rendering, never the authority for what is opened or
   played.
4. **Artwork:** accept the per-track image; when a song's `imagePath` is null
   and it has an `albumId`, resolve the album image as the pick's artwork.
5. **Header copy stays "Recent searches"** (existing string
   `search_recent_title`).
6. **Every result tap is recorded** — song, album, artist, genre and playlist —
   regardless of whether playback later fails.

## Symptoms (from `progress/KNOWN_ISSUES.md` P1)

1. **Live results:** results are not live while typing. They should update on
   each keystroke, or after a short debounce.
2. **Song tap plays:** tapping a searched song should play it immediately. Only
   album / artist / genre / playlist results should redirect to their pages.
3. **Result-based recents:** recents currently store the typed query. Instead
   they should store and display what the user clicked — e.g. searching
   "dooms" and opening "Doomsday" shows the song "Doomsday" (with its image),
   and the same for album / artist / genre / playlist picks.

Reproduction:
- Type a query and do not press the IME action: the body keeps showing the idle
  state (or the previous results), only the text field changes.
- Search for a song, tap its row: the album detail opens when `albumId` is set;
  rows without an album do nothing. Playback never starts.
- Submit a search for "dooms", tap "Doomsday", relaunch the app, clear the
  field: the recents row reads "dooms" with a magnifier icon.

## Evidence

### S1 — submit-only search [VERIFIED by source]

- `feature-search/src/main/java/app/omnisonic/feature/search/search/SearchViewModel.kt:46-48`:
  `onQueryChange` only does `_state.update { it.copy(query = query) }`.
- `:50-58`: `submit()` trims, clears when blank, otherwise calls
  `runSearch(query, record = true)`. `rerunIfActive()` (`:130-133`) re-runs the
  submitted query after a chip toggle; nothing reacts to typing.
- `:135-175`: `runSearch` cancels the previous job, then sets
  `phase = SEARCHING`, `results = null`, `hasMore = false`. Any naive
  type-triggered search therefore blanks the body on every character.
- `SearchScreen.kt:83-113`: `SearchRoute` reads `state` and forwards
  `onQueryChange`; the keyboard action calls `onSubmit` (`:237-241`).
- `SearchRepository.search` performs five DAO reads (`LIMIT + 1` per category)
  and calls `providerStatuses.refresh()` once per call — provider-table and
  connectivity-capability reads, no network I/O.
- `SearchPerformanceTest`: 100k songs, 3 s budget, measured 0.02–0.8 s
  Robolectric first page per `search/data-layer.md`. Device latency is
  `[UNKNOWN]`, so debounce + cancellation + a stale guard are mandatory.

### S2 — song rows never play [VERIFIED by source]

- `SearchScreen.kt:427-434`: the song row's click is
  `val albumId = song.albumId; if (albumId != null) onOpenAlbum(albumId)`.
  There is no else branch and no play callback.
- `SearchScreen.kt:83-113`, `:116-134`, `:394-440`: only `onOpenAlbum`,
  `onOpenArtist`, `onOpenGenre`, `onOpenPlaylist` are threaded down.
- `SearchViewModel` has no `PlaybackController` dependency;
  `feature-search/build.gradle.kts:34` already exposes
  `api(project(":playback-api"))`, and `PlaybackController.play(entries,
  startEntriesIndex, …)` is the shell-independent port.
- The established contract: `feature-library/.../detail/DetailViewModel.kt:98-116`
  queues the loaded row window, skips rows whose `externalId` is null, and
  re-anchors the start index on the tapped row's identity. The Library browse
  path (`LibraryBrowseViewModel.playFrom`) does the same.
- `SongEntity → PlaybackQueueEntry` lives in
  `feature-library/.../playback/QueueEntries.kt:12-23` and is `internal` to
  `:feature-library`. `core-data` and `playback-api` are independent modules,
  so `:feature-search` cannot reuse it without moving it or duplicating it.
- `docs/omnisonic/search/data-layer.md` documents "a song row opens its album
  detail"; that sentence must be corrected when this fix lands.

### S3 — query-string recents [VERIFIED by source]

- `core-data/.../search/SearchPreferencesStore.kt:22-27`:
  `SearchPreferences.recentSearches: List<String>`.
- `:34-44`: decoded with `JsonText.decodeStringList` from
  `search.recent_searches` (`:97-99`).
- `:61-71`: `recordRecentSearch` trims, dedupes case-insensitively, keeps the
  newest first, caps at 10. `:73-80` removes by exact string; `:82-84` clears
  the key.
- `SearchViewModel.kt:167`: recorded only after a successful search, not on a
  result tap. `SearchUiState.kt:9-20` exposes `recentSearches: List<String>`.
- `SearchScreen.kt:312-390`: recents render as `Icons.Search` + text, keyed by
  the query string (`:358`), clicked as a query (`:364`), removed by string.
- `SearchSource.kt:16-33` is implemented by `RepositorySearchSource` (`:36-75`)
  plus `FakeSearchSource` (`feature-search/src/test/...`) and
  `EmptySearchSource`/`testSearchViewModel()`
  (`app/src/test/java/app/omnisonic/shell/SearchTestDoubles.kt:14`, `:54`).
  Any signature change must update all of them.
- Every entity needed for a pick is available on tap: `SongEntity` has `id`,
  `providerId`, `externalId`, `albumId`, `title`, `displayArtist`,
  `imagePath`, `durationMs`; the other rows have `id`, `providerId`, `title`,
  `imagePath` and (albums) `displayArtist`. `SearchCover`/`CoverImageKey`
  already renders `(providerId, imagePath)` in this module.
- `SongEntity.imagePath` is written by the syncer
  (`SubsonicLibrarySyncer.kt:404`) and can be null; album art is on
  `AlbumEntity.imagePath`. Identity reads by id are already the detail-screen
  convention (`DetailRepository.album/artist/genre/playlist`), so a
  row-by-id re-resolve is a sanctioned read and needs no `ProfileScope`
  wrapper.
- `SearchPreferencesStoreTest` pins the string contract (newest-first, cap,
  remove/clear, blank query, persistence). `SearchRepositoryTest` and
  `SearchPerformanceTest` construct `SearchPreferences` directly and must be
  updated with the new field.

### Test coverage [VERIFIED]

- `SearchViewModelTest` (11) covers submit-only state, paging, failure and
  recents-through-submit; none of it covers typing.
- No Compose/Robolectric test renders the search screen; `:feature-search` has
  no `compose-ui-test` test dependency. `:app` has that dependency and the
  precedent pattern (`LibrarySongTapTest`, `DetailScreenRenderTest`).
- `MainTabsPagerTest` reaches Search only through `testSearchViewModel()`.

## Chosen design

### S1. Live results with a 300 ms debounce [VERIFIED pattern; INFERRED feel]

Keep the ViewModel as the single source of truth; debounce there, not in the
composable.

1. **Schedule on typing.** `onQueryChange(query)` updates `query` and, unless
   the trimmed query is blank, cancels the pending search job and starts a new
   one: `delay(DEBOUNCE_MS = 300)` then a fresh offset-0 search. Use a
   `Job` + `delay` rather than `Flow.debounce` (which is `@FlowPreview`), so
   the existing job/cancellation structure and the plain-JVM tests stay
   intact. Blank input cancels the job and returns to `IDLE` while leaving the
   typed text alone (the trailing clear button still clears text and state).
2. **Keep prior results visible.** Add a `refreshing: Boolean` to
   `SearchUiState`, independent of `SearchPhase`:
   - no previous results → `SEARCHING` (existing centered spinner);
   - previous results present → keep `RESULTS` + `refreshing = true`, and show
     a slim `LinearProgressIndicator` (or equivalent token-styled indicator)
     above the list. The body must not flash a spinner between keystrokes.
3. **Stale guard.** After `source.search` returns, apply the result only if the
   trimmed current query still equals the query that produced it. Job
   cancellation covers the normal case; the guard closes the cancellation
   window.
4. **IME submit stays immediate.** `submit()` runs the search without the
   delay and keeps its current keyboard-dismiss behaviour. It no longer
   records anything (recording moves to result taps).
5. **Paging.** A new live search resets paging exactly like today; `loadMore`
   must not run while a debounce job is pending, and a new query cancels any
   append. Chip toggles keep `rerunIfActive()` semantics (immediate re-run, no
   debounce, using the current query).
6. **Do not optimise the repository first.** The per-call
   `ProviderStatusTracker.refresh()` is DB + capabilities only. If the device
   pass shows it matters, add a measured, separately documented refresh
   throttle; do not pre-emptively change `SearchRepository`'s contract.
7. **No minimum query length** (not requested). A one-character query is
   allowed; note its worst-case cost in the device pass.

State transitions the implementation must make explicit and test:

| Input | Before | After |
|---|---|---|
| type first character | `IDLE`, no results | `SEARCHING` after 300 ms, one call |
| type while a search is in flight | `SEARCHING` | previous call cancelled; one call after 300 ms from the last keystroke |
| type while `RESULTS` | `RESULTS` | `RESULTS` + `refreshing`, previous rows stay until the new result applies |
| clear the field | any | `IDLE`, `refreshing = false`, no results, pending job cancelled |
| submit | any non-blank | immediate search, spinner if no results, `refreshing = false` on completion |
| result arrives for an old query | — | ignored (stale guard) |

### S2. Song tap plays the loaded song window

1. Inject `PlaybackController` into `SearchViewModel` and add
   `fun playSong(songId: Long)`.
2. `playSong` reads `state.results.songs.items`, maps rows to
   `PlaybackQueueEntry`, finds the tapped row, and calls
   `controller.play(entries, index)` — the `DetailViewModel.playFrom`
   contract: queue the **loaded** window, skip rows with a null `externalId`,
   re-anchor the start index to the tapped row, no-op when there is no content
   or the row is out of range.
3. Add a private `SongEntity.toQueueEntry()` mapper in `:feature-search`
   (e.g. `search/SearchPlayback.kt`). This duplicates the ~12-line mapper in
   `feature-library/.../QueueEntries.kt`. The dependency-correct consolidation
   would be a primitive-based factory in `playback-api` that both modules use;
   it is out of scope here. Record the duplication in the change summary.
4. Wire `onPlaySong: (Long) -> Unit` through `SearchRoute` →
   `SearchScreen` → `ResultsBody`, and have `SongRow` call it instead of
   `onOpenAlbum`. Album/artist/genre/playlist rows keep their current
   navigation. No forced Now Playing navigation; the mini-player reflects the
   controller state like every other play path.
5. Keep the whole row as the click target (thumb included) and keep the
   existing `heightIn(min = listRowMinHeight)` target. Do not add long-press
   album navigation (not requested).
6. A song with a null `externalId` cannot be queued; the tap is a no-op under
   the existing mapper contract. Do not add new copy for this case.

### S3. Result-based recents

#### Model

Add `core-data/src/main/java/app/omnisonic/core/data/search/RecentPick.kt`:

```kotlin
enum class RecentPickKind { SONG, ALBUM, ARTIST, GENRE, PLAYLIST }

data class RecentPick(
    val kind: RecentPickKind,
    val id: Long,                    // catalog row id
    val providerId: Long,
    val externalId: String? = null,  // SONG only, for playback
    val title: String,
    val subtitle: String? = null,
    val imagePath: String? = null,
    val durationMs: Long? = null,
)
```

A pick is an immutable display snapshot of the tapped row at tap time.

#### Store (`SearchPreferencesStore`)

- Replace `recentSearches: List<String>` with `recentPicks: List<RecentPick>`.
- New key `search.recent_picks`. Persist a private JSON DTO whose `kind` is a
  plain string, mapped on read with
  `RecentPickKind.entries.firstOrNull { it.name == raw }` and **skipping
  unknown kinds**, so a future enum change cannot wipe the list. Decode
  malformed JSON to an empty list (same tolerance as today). Use the shared
  Moshi instance in `core-data/.../json/OmnisonicJson.kt`; if a list adapter is
  needed, add a small `JsonText`-style helper rather than creating a second
  Moshi instance.
- Methods: `recordRecentPick(pick)`, `removeRecentPick(kind, id)`,
  `clearRecentPicks()`. Dedupe identity is `(kind, id)`, newest first, cap 10.
  Keep the cap constant name/semantics (`MAX_RECENT_PICKS`).
- **Legacy deletion (approved):** remove `search.recent_searches` once on
  upgrade. Implement it as a guarded one-shot inside the store (an
  `AtomicBoolean` + `dataStore.edit { remove(legacyKey) }` before the first
  emission), and also remove the legacy key inside every recents write. The old
  strings are never decoded into the new model and never rendered.
- Keep `search.categories` and `search.page_size` unchanged.

#### Recording

- Record on every result tap — song, album, artist, genre, playlist — before
  dispatching play/navigation, via a fire-and-forget `viewModelScope.launch`.
- Do not record on submit, chip toggle, retry, or a blank query.
- Selecting a recent pick re-records it so it moves to the top (natural recents
  semantics), then performs its action.
- Build each pick from the tapped row: `subtitle` is the row's
  `displayArtist` (song/album) or a kind label; `imagePath` is the row's image,
  except a song with `albumId != null` and `imagePath == null` resolves
  `albumDao.selectById(albumId)?.imagePath` at record time (approved artwork
  fallback). Do this inside the repository/source, not the composable.

#### Re-resolution on click (approved)

- On a recent tap, re-resolve the row by id first through the source, reusing
  the existing `selectById` DAO methods (`SongDao`, `AlbumDao`, `ArtistDao`,
  `GenreDao`, `PlaylistDaos`) — identity reads, the same convention as
  `DetailRepository`'s header lookups. These `selectById` functions are already
  in `ProfileScopeQueryTest`'s exemption table, so no new `*Raw` wrapper is
  involved.
- If the row is gone: prune the pick from recents and no-op (do not navigate to
  a guaranteed `Missing` screen, do not play from the stale snapshot).
- If the row exists: SONG plays through the same `playSong` path (with the
  freshly resolved row and, when `externalId` is null, no-op after pruning is
  not required); the other kinds call the existing `onOpenAlbum/Artist/Genre/
  Playlist` callback with the resolved id.
- Re-resolve must not overwrite the stored snapshot (avoid a write on every
  click); display continues to use the stored snapshot until the pick is
  re-recorded.
- New `SearchSource` method: `suspend fun resolveRecentPick(pick: RecentPick):
  RecentPick?`, implemented in `RepositorySearchSource` over a new
  `SearchRepository` method. Update `FakeSearchSource` and `EmptySearchSource`.

#### Rendering (`IdleBody`)

- Keep the header string `search_recent_title` ("Recent searches").
- Row = artwork thumbnail via `SearchCover`/`CoverImageKey(providerId,
  imagePath)` with the kind's placeholder icon + title + subtitle, reusing
  `listRowMinHeight`, spacing and typography tokens. No new hard-coded values.
- The kind must not be conveyed by the icon alone: include a visible kind label
  (e.g. in the subtitle) or a content description that names the type.
- Stable keys and remove actions use `(kind, id)`, not the title.
- Keep the existing clear-all action and `SearchPageConfig.showRecentSearches`
  gate (hiding the block does not stop recording).
- Empty recents → the existing idle copy.

## Files likely to change

| File | Change |
|---|---|
| new `core-data/src/main/java/app/omnisonic/core/data/search/RecentPick.kt` | `RecentPickKind` + `RecentPick` |
| `core-data/src/main/java/app/omnisonic/core/data/search/SearchPreferencesStore.kt` | new key/DTO/decode, pick methods, legacy deletion, cap/dedupe by `(kind, id)` |
| `core-data/src/main/java/app/omnisonic/core/data/search/SearchRepository.kt` | re-resolve-by-id read + album-art fallback lookup used by the source |
| `feature-search/src/main/java/app/omnisonic/feature/search/search/SearchSource.kt` | pick record/remove/clear + `resolveRecentPick`; `RepositorySearchSource` implementation |
| `feature-search/.../search/SearchUiState.kt` | `recentPicks`, `refreshing` |
| `feature-search/.../search/SearchViewModel.kt` | debounce job, stale guard, `refreshing`, `recordPick`, `resolveAndAct`, `playSong`, `PlaybackController` injection |
| new `feature-search/.../search/SearchPlayback.kt` | private `SongEntity.toQueueEntry()` |
| `feature-search/.../ui/SearchScreen.kt` | `onPlaySong` + recent-pick callbacks, artwork/type recents rows, remove-by-id, refreshing indicator, song-row click |
| `feature-search/src/main/res/values/strings.xml` | content descriptions for play/type if new copy is required; keep existing recents title/clear/remove |
| `app/src/main/java/app/omnisonic/shell/MainTabsPager.kt` / `OmnisonicApp.kt` | only if a new route callback is needed; the VM already comes from `hiltViewModel()` |
| `feature-search/src/test/.../FakeSearchSource.kt`, `SearchViewModelTest.kt` | pick API, debounce/refreshing/resolve/play tests |
| `core-data/src/test/.../SearchPreferencesStoreTest.kt`, `SearchRepositoryTest.kt`, `SearchPerformanceTest.kt` | new `SearchPreferences` field + pick contracts |
| `app/src/test/java/app/omnisonic/shell/SearchTestDoubles.kt` | implement the new `SearchSource` API and the new `SearchViewModel` constructor |
| `app/src/test/.../SearchSongTapTest.kt` (new, optional location) | row tap dispatches play; recents render artwork/type |

## Testing and verification

JVM (`:feature-search`):
1. Debounce: no source call before 300 ms; one call after the last keystroke;
   earlier keystrokes are coalesced; use `runTest` virtual time.
2. Typing with previous results keeps `RESULTS` and sets `refreshing`; the
   body does not return to `SEARCHING`; blank input cancels and returns to
   `IDLE` without clearing the text.
3. Stale results are ignored if the query changed before they applied.
4. `submit()` is immediate, and paging still works after a live search (reset,
   append, failure-retains-rows).
5. `playSong` queues the loaded window, anchors the tapped row, skips a null
   `externalId` row and re-anchors, and no-ops with no content/out of range.
6. Recording happens on a tap, not on submit; re-picking moves a pick to the
   top; a tap records exactly one pick.

JVM (`:core-data`):
7. `RecentPick` round-trip through the store, including artwork/subtitle/
   external id; `(kind, id)` dedupe; cap 10; newest first.
8. Tolerant decode: malformed JSON → empty; unknown kind skipped while valid
   picks survive; persistence across store instances.
9. Legacy `search.recent_searches` is removed on first access and never
   exposed as picks.
10. Re-resolve returns the current row, and `null` after the row is deleted;
    a song with a null image and an album id records the album image.

UI (`:app`, where Compose test deps already exist):
11. A song row has a click action and tapping it dispatches play with the
    correct start; an album row still opens the album.
12. Recents render the stored artwork identity and a type label; the remove
    action removes by `(kind, id)`; the header still reads "Recent searches".

Project gate: `./gradlew :app:assembleDebug test lint`; banned-name scan over
all touched files. Keep `SearchPageConfigTest`, `SearchFocusRulesTest`,
`MainTabsPagerTest`, `ShellGoldenTest` green unless a deliberate, documented
golden re-record is required (R-25).

Device acceptance (needs an attached device; every item is `[UNKNOWN device]`):
1. Type "bowie" without pressing the IME action: results appear once typing
   pauses and update as the query changes; no spinner flash between keystrokes.
2. Type quickly then delete: no out-of-order results, no blank body while a
   newer query runs.
3. Press the IME Search action: results are immediate and the keyboard
   dismisses as today.
4. Tap a song result: playback starts on that track, the mini-player reflects
   it; tap album/artist/genre/playlist: navigation unchanged.
5. Search "dooms", tap "Doomsday", clear the query: the recents row shows the
   song with artwork, not the query. Repeat for all five kinds and confirm a
   song with no track image shows album art.
6. Kill and relaunch: recents persist with artwork and type; the field is empty
   and there are no legacy query rows.
7. Tap a recent song: it plays; tap a recent album: its detail opens. Delete a
   synced row, then tap its recent: the pick disappears and nothing navigates.
8. TalkBack: a recent row announces its type, title and remove action; a song
   row announces a play action.
9. Record the results in `progress/KNOWN_ISSUES.md` and `progress/STATE.md`
   with tags; only then close the items.

## Risks and constraints

- **Typing cost is unmeasured on hardware.** Five DAO reads plus the provider
  status refresh per debounced query; the Robolectric worst case is 0.02–0.8 s.
  Mitigations: 300 ms debounce, job cancellation, the stale guard, and keeping
  the old rows on screen. Do not claim "smooth" without the device pass.
- **Recents persistence migration.** New key and JSON shape; tolerant decode
  and unknown-kind skipping are mandatory. The legacy key deletion is a write
  during the first read — keep it guarded and idempotent.
- **`SearchSource` API ripple.** Three implementations (repository + two test
  doubles) and `SearchViewModel`'s constructor change (new
  `PlaybackController`). Update them in the same change or the build breaks.
- **Mapper duplication.** `:feature-search` cannot reuse `:feature-library`'s
  internal mapper. Duplicating ~12 lines is accepted for this fix; consolidation
  into `playback-api` is a follow-up, not part of the task.
- **Documented behaviour flips.** `search/data-layer.md` says a song row opens
  its album and that recents are query strings. Update it, and the decision
  record, only after the implementation lands.
- **Stale picks.** Re-resolution prevents navigating to a deleted row, but
  artwork/title in the snapshot can lag until re-pick. Accepted, capped at 10
  entries.
- **Artwork availability.** Per-track art is server-provided and may be null;
  the album fallback covers songs with an `albumId`. Picks whose image is still
  null render the kind placeholder — do not invent art.
- **Accessibility.** Type must be readable, not icon-only; rows keep their 48 dp
  targets; the refreshing indicator must not be the only signal (results stay
  interactive while it shows).
- **Scope creep.** Do not add drag-reorder, per-row menus, undo, favorites, a
  minimum query length, or Phase 9 style-config keys. Do not touch the Queue
  cluster.

## Remaining open questions (non-blocking; recommended defaults in brackets)

1. Should a recent song whose row still exists but whose `externalId` is null
   stay in the list? [Yes — it is still a valid catalog pick; the tap is a
   no-op per the existing row-tap contract.]
2. Should the `refreshing` indicator be a thin bar above the list or inside the
   bottom block? [Above the list, so it is not confused with the field.]
3. Is a snackbar needed when a recent resolves to a deleted row? [No — prune
   silently; adding copy is not part of the request.]

## Acceptance criteria

- [x] Typing updates results after a 300 ms debounce; no search runs on every
      character, blank input returns to idle, and previous results stay visible
      while a newer query runs. `[VERIFIED JVM]`
- [x] IME Search still runs immediately; paging, chip toggles, error/retry and
      no-results states behave as before. `[VERIFIED JVM]`
- [x] Tapping a song result plays it immediately from the loaded song window,
      anchored on the tapped row; album/artist/genre/playlist taps still
      navigate. `[VERIFIED JVM]`
- [x] Recents store the picked item (`kind` + id + display snapshot + artwork),
      render artwork and type under the unchanged "Recent searches" header, and
      remove by identity. `[VERIFIED JVM]`
- [x] Tapping a recent song plays it, other recent kinds open their page, and a
      deleted row is pruned instead of navigating. `[VERIFIED JVM]`
- [x] Legacy `search.recent_searches` values are deleted and never displayed.
      `[VERIFIED JVM]`
- [x] JVM tests cover the debounce/stale/refreshing, play dispatch, pick
      persistence/tolerance and re-resolution paths; the full gate and
      banned-name scan pass.
- [ ] Device checklist recorded; the P1 items stay open until it is green.
      `[UNKNOWN device]` — no device attached.
- [x] `search/data-layer.md`, `progress/*`, `.agents/memory/findings.md` and
      decision D-30 updated only after the implementation lands; no phase
      status or task checkbox changed.

## Where implementation starts

1. Read this brief, `search/data-layer.md`, `search-tab-fixes.md` and the
   `DetailViewModel.playFrom` precedent.
2. **Data first:** add `RecentPick`, switch `SearchPreferences`/the store and
   its tests, including tolerant decode and the guarded legacy-key deletion.
3. **Seam:** update `SearchSource`/`RepositorySearchSource` (pick methods +
   `resolveRecentPick`), then both test doubles so the tree compiles.
4. **Live search:** debounce job, `refreshing`, stale guard, blank-query and
   submit rules, with virtual-time tests.
5. **Playback:** inject `PlaybackController`, add the private mapper and
   `playSong`, wire `onPlaySong`; then the recents UI (artwork, type, remove by
   id, re-resolve on click).
6. Run the module suites, then `./gradlew :app:assembleDebug test lint` and the
   banned-name scan.
7. Device pass (checklist above), then update the data-layer doc, the progress
   records, findings, and add D-30 to `decisions.md`.
