# Now-playing + queue screens (`:feature-library`, Phase 5 task 6)

Status: Phase 5 task 6, shipped 2026-11-11. Revised 2026-11-16: the lyrics pane
(D-23), the queue/now-playing detents of the anchored player sheet (D-22), and
the queue row's threshold-commit swipe (`ui/queue-swipe.md`, D-26).
Spec: `reference/docs/UI_SPEC.md` §3.8 (now-playing) and §3.9 (queue), with the
button-twin rule §1.3 and the gesture-ownership contract in
`ui/gesture-contract.md`. Phase 9 owns the block model, effects renderer and
per-slot configuration; the layouts here are the fixed Phase 5 default.

Both screens read playback state exclusively through `PlaybackController`
state flows and dispatch all edits through its command port — no ExoPlayer,
MediaSession or queue-machine types reach the screen layer.

## State mapping (`:feature-library`)

### `NowPlayingViewModel` / `NowPlayingUiState`

- The VM is destination-scoped (`hiltViewModel()` in the shell destination)
  and combines `controller.playerState` + `controller.queueState` into one
  eagerly shared `StateFlow` (initial value built from the current values, so
  a fast first frame never shows a false empty state).
- `NowPlayingUiState.from(player, queue)`:
  - `entry` = `player.current ?: queue.currentEntry` (the queue pointer
    survives a stopped/ended engine, so the screen keeps showing the song);
    the cover key is rebuilt from `providerId` + `coverImageId`.
  - `isPlaying` = `player.isPlaying`; position/duration from the player, with
    the entry duration as the fallback when the engine reports none.
  - `shuffleEnabled` = `queue.shuffle != null` (the queue machine owns the
    order; a null order is the unshuffled state).
  - `repeatMode` from the queue state.
  - `progress` is a pure 0–1 fraction (duration null/zero → 0).
- Commands: `playOrPause`, `skipNext`, `skipPrevious`, `seekTo` (clamped to
  the known duration, floored at 0), `setShuffleEnabled(!current)`,
  `setRepeatMode(next)` cycling OFF → ALL → ONE → OFF.
- **Pane state (D-23):** `pane: StateFlow<NowPlayingPane>` (`COVER` /
  `LYRICS`) plus `lyrics: StateFlow<LyricsUiState>` and `togglePane()`. It is
  VM state, not a route or sheet state, so collapsing the sheet keeps the pane
  for the process lifetime; only process death resets it. `LyricsUiState`
  carries `lines` and `isEmpty` — the data layer fills it later (D-15).
- Zero entries / empty queue renders the idle state (prompt + open-library
  button). Loading and error states do not apply — playback is stateful, not
  fetched (UI_SPEC §3.8 states).

### `QueueViewModel` / `QueueUiState`

- `QueueState.toQueueUiState()` maps `effectiveOrder()` to rows: each row
  carries the entry, its **playback-order index** (fed to jump) and an
  `isCurrent` marker. Rows therefore follow the shuffle order when shuffle is
  on, matching what actually plays next.
- Header position = `playIndex + 1` of `N` rows (null when the pointer is out
  of range); empty rows → idle state.
- Commands: `jumpToOrderIndex`, `removeFromQueue(id)`,
  `moveInQueue(id, targetIndex)`, `clearQueueExceptCurrent()`. The VM
  translates a shown row index into an entries-index target for move-up/down
  and refuses moves at the ends. **Reordering is refused while shuffled**
  (`canReorder = shuffle == null`): the shown order is the shuffle order, so
  an entries-list move would move a row the user did not touch. Queue edits
  never start a playback session.

## `QueueCommandPort` extension (`:playback-api` + `:playback`)

Three abstract commands were added to the port and implemented by
`PlaybackControllerImpl` (and the late-bound delegate in the wiring module;
test doubles implement them too):

- `removeFromQueue(queueItemId)` — removes the entry and its play-order slot
  (unknown ids are a no-op). Removing the current entry while playing starts
  the following entry, or ends playback when it was the last one; the queue
  pointer is clamped, and an emptied queue resets to the terminal state.
- `moveInQueue(queueItemId, targetIndex)` — reorders the stable entries list
  and repositions the pointer; the target is clamped to the list bounds. While
  shuffled the visible order stays the shuffle order (the UI refuses reorder).
- `clearQueueExceptCurrent()` — keeps only the playing entry (no-op when
  nothing is current).

No schema or snapshot change: queue persistence already snapshots the entries
list + play order.

## UI layer

### Now-playing (`ui/NowPlayingScreen.kt`)

- `NowPlayingRoute` collects the VM state and wires the commands; the screen
  is a pure function of `NowPlayingUiState`.
- Layout top→bottom (§3.8): top bar (collapse button + compact title/artist)
  → weighted art block (art or lyrics + title/artist under it) → seek row →
  transport row (shuffle, previous, play/pause, next, repeat) → secondary row
  (queue, lyrics).
- **Backdrop:** `BackdropSurface` (`:style`) paints the token `BackdropStack`
  behind the content; the default "Subtle" stack is base-only (solid +
  art-tinted gradient), and the AGSL effect layers are accepted by the token
  model but not painted until the Phase 9 renderer. The screen wraps content
  in a transparent `Surface` so icons/typography inherit `onBackground`
  (without it Compose goldens rendered black icons).
- **Seek:** slider drag updates a local scrub value; the seek command fires
  on drag end so the engine is not spammed per pixel. Elapsed / remaining
  labels; remaining is the Phase 5 fixed default (per-setting format is a
  later task).
- **Transport:** shuffle and repeat tint with the primary color when active;
  repeat swaps to the repeat-one icon and label. Play/pause is the prominent
  filled button.
- **Omitted / disabled slots:** the top-bar overflow button is not rendered
  (its Phase 9 block model owns per-slot actions). The secondary row's
  favorite slot is replaced by the **lyrics toggle** (D-23): the favorite
  action has no write path from this screen yet and returns with the Phase 9
  rating work, at which point the row grows a third slot.
- **Lyrics pane (D-23):** the art block switches content between cover and
  lyrics via `Crossfade` on `MotionTokens.artCrossfade` (200 ms). The pane
  replaces only the art block's content: layout, seek row, transport row and
  the sheet drag surfaces are untouched. The secondary row's `Icons.Notes`
  button is the visible toggle and tints primary while the pane shows; it needs
  no gesture twin because it is itself the visible control (art double-tap as a
  second trigger is reserved for Phase 9 gesture configuration). `LyricsPane`
  renders the empty state ("No lyrics for this track") while the data layer is
  absent, and a simple scrollable line list when `LyricsUiState.lines` is
  populated. The text is a leaf for the sheet drag detector: a drag it cannot
  scroll falls through to the sheet, overflowing text consumes its own drags.
- **Art gestures + twins (§1.3):** tap = play/pause; horizontal drag =
  **next (right-to-left) / previous (left-to-right)** — the cross-app
  convention and the D-19 default (corrected 2026-11-12 by tasks 7f/7g; the
  original build shipped the inverted mapping; a user-facing direction
  setting is reserved for Phase 9); drag up = open queue; drag down =
  collapse — **on the shell's sheet the vertical drag is live** (task 7h): the
  screen reports `dy`/release velocity through `PlayerSheetDrag` and the sheet
  follows the finger (`ui/player-sheet.md`). The 32 dp threshold on the
  dominant axis is the standalone fallback used when no sheet host is present
  (render tests, goldens). Every action
  is also a visible control: the play/pause, previous/next, queue and collapse
  buttons all exist, and the art box registers matching `GestureTwin`s so the
  accessibility audit finds a custom action for each gesture. Art also carries
  a button role + content description so the tap is reachable without the
  gesture. The art translates with a horizontal drag (clamped to
  `SpacingTokens.xl`, 24 dp) and springs back on release, and the art/content
  column fills the width so the cover is horizontally centered.

### Queue (`ui/QueueScreen.kt`)

- Header: back button, "Playing N of M" (or "Queue" when nothing is current)
  and "Clear remaining" (enabled only with more than one row).
- Rows render in playback order with stable `queueItemId` keys; the current
  row shows an equalizer marker in the primary color. Each row has a bounded
  cover thumbnail, title/artist, a per-row remove button and an overflow menu
  with move-up/move-down.
- **Remove has both twins:** a right-to-left swipe (custom reveal row; start→
  end is reserved but disabled) and the per-row delete button
  (`ui/queue-swipe.md`). The row follows the finger, a release past half the
  row springs it off-screen and only then fires `onRemove`, and a shorter
  release springs back to rest. The swipe background is the error container;
  rows paint an opaque background so the swipe affordance never shows through.
  Vertical drags still belong to the list.
- **Move** ships through the per-row overflow (the accessibility twin). Free
  drag-reorder is deliberately deferred — the Phase 9 row-action work owns
  drag handles and `MotionTokens.queueReorder`; the move commands already
  exist, so wiring the drag later is presentation-only.
- **Orientation (D-21):** the list remembers its scroll state and jumps
  instantly to `currentOrderIndex - 1` on each reveal, so the current row is
  the second visible row; the one-shot latch resets when the sheet hides, so
  every open re-orients, while track advances during an open leave the list
  where the reader left it. No current entry → no positioning.
- **Jump-to-current pill:** floating bottom-end over the list — cover thumb +
  one-line ellipsized title + primary equalizer icon, content description
  `queue_jump_to` ("Jump to %1$s"), plain button (no gesture twin needed).
  Visible only while the current row is not fully inside the viewport, derived
  from `LazyListLayoutInfo` so it recomposes on the crossing; a tap
  `animateScrollToItem`s back to the row. The list reserves one extra line of
  bottom content padding so the pill cannot permanently cover the last row.
- **Return gesture:** inside the sheet, a downward pull that starts at the very
  top of the list **or** at the unscrolled reveal position pulls now-playing
  back with the finger (`ui/player-queue-return.md` §4.1, `ui/player-sheet.md`
  §4.1). The reveal-position half is D-24: the queue opens with the current row
  second, so a true-top-only latch was dead on open; one user scroll clears it.
  It is a list affordance only when a sheet host exists; the back arrow stays
  the visible twin.
- **The queue is a sheet detent, not a destination** (D-22): at the sheet level
  it is the `SWIPE_UP_TARGET` anchor (`-1`) above now-playing, revealed by the
  same finger-following continuum as the player. `ShellRoutes.QUEUE` is kept
  only for direct navigation, where it renders as a normal full screen.
- Empty state: prompt + open-library button. Inside the sheet the empty state is
  the return gesture's drag surface (it is a leaf, so it can own a vertical
  drag the container must not have).

## Shell wiring (`:app`)

`ShellNavHost` replaced the NOW_PLAYING and QUEUE placeholders with
`NowPlayingRoute` / `QueueRoute`. Both VMs are destination-scoped behind the
same provider seams as the detail screens (`nowPlayingViewModelProvider`,
`queueViewModelProvider`), so the non-Hilt shell tests inject doubles.
Collapse returns to the previous destination; "open queue" navigates the queue
route; empty states navigate to the library.

**Player sheet (`ui/player-sheet.md`, D-22/D-25):** the normal player path no longer
navigates. `OmnisonicShell` wraps its layout in a `Box` that hosts the
`PlayerSheetOverlay` above content, mini player and navigation bars, so the
current route stays composed and is what a downward drag reveals. Mini-player
expand and now-playing vertical drags move the sheet with the finger; a
released full-range drag settles on a detent through the sheet's own state
machine and one spring, and the mini bar slides/fades out as a
pure function of sheet position (the bar is the `PEEK` detent, not a second
motion system). The queue is the `SWIPE_UP_TARGET` detent. The swipe-up target
is a slot (queue by default, configurable in Phase 9) and receives two optional
values: the bounded `PlayerSheetDrag` for the return gesture and the
`isRevealed` flag derived from the sheet position with `derivedStateOf`. System
back and the queue's back arrow follow the visual stack (queue → now-playing →
peek → route, D-21/D-24); the arrow keeps calling `openNowPlaying()`, so both
paths agree. The NOW_PLAYING/QUEUE routes stay registered for the route table
and direct navigation (no host there: `sheetDrag = null`, `isRevealed = true`,
back pops the route).

**Route transitions (task 7g, D-19):** `nowPlaying` enters with a bottom
slide-up + fade and pops with a mirrored slide-down; `queue` slides up over the
still-visible now-playing screen and pops back down — both driven by
`MotionTokens.nowPlayingRoute` through per-destination NavHost transitions.
Since D-22 these tweens exist **only** on the standalone-route path; the sheet
path uses the one spring. Root causes and designs: `ui/player-ux-fixes.md`.

## Tests

- `:feature-library` `NowPlayingViewModelTest` (10) — state mapping (entry
  fallback, duration fallback, progress clamps, shuffle/repeat mapping),
  command dispatch (play/pause, skips, seek clamping, shuffle toggle, repeat
  cycle) and the pane state (starts on the cover, toggles both ways, lyrics
  start empty).
- `:feature-library` `QueueViewModelTest` (9) — order mapping under shuffle,
  header position, jump dispatch, remove dispatch, move-up/down translation +
  bounds + shuffle refusal, clear-except-current.
- `:playback` `PlaybackControllerImplTest` (+1, 255 module total) — queue
  edits remove, move and clear through the command port.
- `:app` `PlayerScreensRenderTest` (19) — both screens' content/empty states,
  disabled controls, seek label rendering, the button-twin audit over every
  registered gesture, command wiring from taps, art drag direction
  (right-to-left → next, left-to-right → previous), the lyrics button
  dispatching the pane toggle, the lyrics pane's empty state and line
  rendering, the cover pane hiding lyrics, the jump pill appearing with the
  current row off-screen and its absence when every row fits, and the
  `sheetDrag = null` path opening at the current row.
- `:app` `PlayerScreensGoldenTest` (2) — Roborazzi goldens
  `now_playing_portrait.png` and `queue_portrait.png` (411 × 891 dp).
  `now_playing_portrait.png` was **re-baselined deliberately** on 2026-11-16
  when the disabled favorite button became the lyrics button; the queue and
  mini-player goldens are byte-stable (sha256 checked before/after recording).
  Note: plain `test` does **not** compare goldens on this setup — only
  `:app:verifyRoborazziDebug` does (R-25, reconfirmed 2026-11-16).
- `:app` `MiniPlayerShellTest` — the sheet flow: expand opens the player over
  the current route without navigating, swiping up pulls the queue in, swiping
  down reveals the route underneath, and the fast-fling regression cases (a
  partial upward flick from the bar, upward on now-playing → queue, downward on
  either surface) settle on the expected detent. Release-velocity sampling is
  covered directly by `:feature-library` `PlayerSheetVelocityTest` (7):
  history-aware samples plus the exact two-sample fallback for a coalesced
  flick.

## Deferred / device-gated

- Drag-reorder on queue rows (presentation only; commands shipped).
- Per-slot block model, top-bar overflow, slider styles and favorite/rating
  actions — Phase 9.
- Player-surface gesture configuration (art-swipe direction mapping) — Phase 9
  (D-19); Phase 5 hard-codes right-to-left = next.
- Finger-following player sheet — **shipped 2026-11-12 (task 7h)**, moved to a
  sheet-owned state machine on 2026-11-16 (D-22, then D-25), see
  `ui/player-sheet.md`; the configurable swipe-up target stays Phase 9.
- **Lyrics data layer** — provider fetch, parsing and synced-line highlighting
  are D-15's post-1.0 track. D-23 ships only the pane, the VM seam and the
  empty state.
- Shared-element cover morph (mini-player art → now-playing art) — deferred
  stretch (`ui/player-ux-fixes.md` §5).
- Art-gesture feel (threshold, directional intent, accidental triggers),
  swipe-to-dismiss feel, scrub feel, route-transition feel and
  notification/lock-screen layouts are device-gated and stay [UNKNOWN] until
  hardware verification.
- Mini-player UX defects (missing progress hairline, black controls, janky
  expand) — **fixed 2026-11-12 (task 7f)**: bottom-edge 2 dp progress hairline
  (`LayoutTokens.miniPlayerProgressHeight`), `contentColor = onSurface` on the
  bar's `Surface`, 24 dp + scale drag lift, expand fires at the settle decision.
  Drag/hairline feel on hardware stays [UNKNOWN] (`ui/player-ux-fixes.md` §7).
