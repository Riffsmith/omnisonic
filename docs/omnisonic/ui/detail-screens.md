# Detail screens — album / artist / genre / playlist (`:feature-library`, Phase 5 task 5)

Status: Phase 5 task 5, shipped 2026-11-10. Spec: `reference/docs/UI_SPEC.md` §3.5
(header model, body per type, states, actions). Data contract: the task-3
`*Raw` + `ProfileScope` wrapper convention
(`docs/omnisonic/search/data-layer.md`) and the task-4 browse paging
(`docs/omnisonic/ui/library-browse.md`).

## Data layer (`:core-data`)

- **`DetailRepository`** (`core-data/detail/`) — header lookups by id plus the
  scoped, paged body reads. Headers are plain identity reads (`selectById`;
  a missing row means "deleted on the server" and drives the screen's
  missing state); body lists carry the provider exclusion like every catalog
  read. Paging is the shared `fetchPage` helper (extracted from
  `BrowseRepository` into `browse/BrowsePage.kt`): LIMIT/OFFSET with the
  one-past-the-window `hasMore` probe, default limit 60 clamp 1–200.
- **Body reads** (each a `*Raw` `@Query` + scope wrapper, `ProfileScopeQueryTest`):
  - **Album tracks** (`SongDao.albumTracks`) — `albumId` match ordered
    `discNo, trackNo` with unknown numbers last, then the alphabetical
    tiebreak. [VERIFIED] `DetailRepositoryTest`.
  - **Artist tracks** (`SongDao.artistTracks`) — `song_artists` link join,
    most played first (`playCount DESC`, `lastPlayed DESC` unplayed-last,
    alphabetical tiebreak); `SELECT DISTINCT` collapses multi-role credits.
  - **Artist albums** (`AlbumDao.artistAlbums`) — `album_artists` link join,
    alphabetical, same DISTINCT rule.
  - **Genre tracks** (`SongDao.genreTracks`) — `genresJson` JSON-list column
    matched with a quote-delimited pattern built from `JsonText.encodeString`
    + `SearchQuery.escape` (LIKE `%["Jazz"]%` style with `ESCAPE '\'`), so
    "Jazz" never matches "Acid Jazz" or "Jazz Fusion"; a song with multiple
    genres matches once (the element encoding appears as a substring).
    The repository resolves the genre row first; a missing genre yields an
    empty page. [VERIFIED]
  - **Playlist entries** (`PlaylistEntryDao.detailEntries` +
    `detailEntryCount`) — `position` order (`position, id` tiebreak), the
    server-reported sequence. [VERIFIED]
  - `GenreDao.selectById` was added as an identity lookup and joined the
    `ProfileScopeQueryTest` exemption table (deliberate).
- **Scope semantics** mirror browse: `ProfileScope` pass-through with the
  default `Unrestricted`; no hidden/offline folding (no visibility UI yet).

## UI layer (`:feature-library`)

- **`DetailKind`** (`ALBUM | ARTIST | GENRE | PLAYLIST`) + **`DetailViewModel`**
  (`@HiltViewModel`): each detail destination owns a back-stack-entry-scoped
  instance serving exactly one (kind, id) pair — `load` is idempotent for the
  same pair, re-enters only from `Error`, and a different pair resets the
  body. Destinations create the instance through the shell's
  `detailViewModelProvider` seam (`:app` `ShellNavHost`); the production
  default is `hiltViewModel()` inside the destination, which requires a
  Hilt-aware activity (see the shell gotchas in `progress/STATE.md`).
- **State** (`DetailUiState`): header + body as one sealed type — `Loading`,
  `Missing` (header row gone → friendly copy + back), `Error` (`load`
  retries), and per-kind content (`Album(header, tracks)`,
  `Artist(header, tracks, albums)`, `Genre(header, tracks)`,
  `Playlist(header, entries)`). The body reuses `BrowseUiState` including
  the load-more/appending surface, driven by the task-4 `SectionPager`.
- **Bodies:** one `SectionPager` per list (two for the artist screen).
  Live refresh (D-16) comes free — each pager + the header watcher collect
  `DetailSource.libraryChanges`; a catalog write refreshes the shown window
  + count and re-reads the header row; a row deleted mid-session moves the
  screen to `Missing`.
- **Playback actions** (through `playback-api` only): `playAll` queues the
  loaded rows from index 0, `playFrom(rowIndex)` queues from the tapped row,
  `shuffleAll` queues then `setShuffleEnabled(true)` (the queue machine
  anchors the started track). Rows map to `PlaybackQueueEntry` with
  `songRowId`/`title`/`artistName`/`coverImageId`/`durationMs` snapshotted;
  songs without a provider external id are left out and the play-from index
  re-anchors to the nearest playable row. **The queue holds the rows loaded
  so far** (first page + load-more windows), not the whole list —
  queueing beyond the window is a later-phase item. [INFERRED behavior note]
- **Header (§3.5 slot order):** art/placeholder (`ImageryTokens.detailArtSize`),
  title, subtitle (album artist / playlist description), meta line
  (year + `N songs`, `N albums · N songs`, `N songs`, `N tracks` — plural
  resources), Play + Shuffle buttons. **Favorite button deferred:** the
  header slot is reserved but not rendered — there is no album/artist/
  playlist favorite write path yet (the song-level `FavoritesRepository` +
  outbox action are song-only); logged in `.agents/memory/findings.md`.
- **Artist body** is one scrollable `LazyColumn`: top-tracks section, then
  the albums strip (horizontal `LazyRow`, own paging, tiles open album
  detail). "Appears on" is reserved (Phase 9).
- **Screens:** `AlbumDetailScreen`, `ArtistDetailScreen`, `GenreDetailScreen`
  (placeholder art — genres carry no image), `PlaylistDetailScreen`, each
  with a route composable taking the view model + navigation callbacks
  (`onBack`, artist's `onOpenAlbum`). Shared pieces live in
  `ui/DetailScaffold.kt` (header, state bodies, track/entry rows, plural
  meta helpers) and `ui/Common.kt` (`LoadMoreFooter`, `formatDuration` —
  extracted from the section screen so the paging footer stays one
  implementation).

## Testing

- `:core-data` `DetailRepositoryTest` (10 Robolectric tests): ordering rules
  (disc/track, most-played, alphabetical, position), link-table joins,
  multi-role DISTINCT, genre JSON exact-match, scope exclusion, paging
  windows, header lookups.
- `:feature-library` `DetailViewModelTest` (15 plain-JVM tests over
  `DetailSource` + `PlaybackController` fakes): load/idempotence, missing,
  error + retry, play mapping + start index, unplayable-row skip, shuffle
  call order, load-more, playlist entry order, artist two-pager, header
  live refresh, deletion → Missing, invalidation window reload.
- `:app` shell tests keep passing with a `detailViewModelProvider` seam
  (test doubles in `app/src/test/.../shell/DetailTestDoubles.kt`); the
  route-conformance test now renders the real detail screens.

## Deferred / device-gated

- Favorite toggle (all detail headers) — needs a favorites write path for
  non-song types (Phase 6+ / outbox discussion). [UNKNOWN until built]
- Real nav transitions, back feel, IME-free scroll behavior on hardware —
  device session items, never assumed. [UNKNOWN]
- Queue-beyond-loaded-window (progressive queueing). [UNKNOWN until built]
- Roborazzi goldens for detail screens — not required by the task list;
  candidate for a later pass if wanted.
