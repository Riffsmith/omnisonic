# Now-playing + queue screens (`:feature-library`, Phase 5 task 6)

Status: Phase 5 task 6, shipped 2026-11-11. Spec: `reference/docs/UI_SPEC.md`
§3.8 (now-playing) and §3.9 (queue), with the button-twin rule §1.3 and the
gesture-ownership contract in `ui/gesture-contract.md`. Phase 9 owns the
block model, effects renderer and per-slot configuration; the layouts here
are the fixed Phase 5 default.

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
  → weighted art block (art + title/artist under it) → seek row → transport
  row (shuffle, previous, play/pause, next, repeat) → secondary row (queue,
  favorite).
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
  (its Phase 9 block model owns per-slot actions); favorite is present but
  disabled — no song-favorite write path from this screen yet (the header
  slot is reserved, same as detail screens).
- **Art gestures + twins (§1.3):** tap = play/pause; horizontal drag =
  previous (left) / next (right); drag up = open queue; drag down = collapse.
  Threshold is `SpacingTokens.xxl` (32 dp) on the dominant axis. Every action
  is also a visible control: the play/pause, previous/next, queue and collapse
  buttons all exist, and the art box registers matching `GestureTwin`s so the
  accessibility audit finds a custom action for each gesture. Art also carries
  a button role + content description so the tap is reachable without the
  gesture.

### Queue (`ui/QueueScreen.kt`)

- Header: back button, "Playing N of M" (or "Queue" when nothing is current)
  and "Clear remaining" (enabled only with more than one row).
- Rows render in playback order with stable `queueItemId` keys; the current
  row shows an equalizer marker in the primary color. Each row has a bounded
  cover thumbnail, title/artist, a per-row remove button and an overflow menu
  with move-up/move-down.
- **Remove has both twins:** swipe-to-dismiss (end→start only; the dismiss
  state always settles back, the queue state is the source of truth) and the
  per-row delete button. The swipe background is the error container; rows
  paint an opaque background so the swipe affordance never shows through.
- **Move** ships through the per-row overflow (the accessibility twin). Free
  drag-reorder is deliberately deferred — the Phase 9 row-action work owns
  drag handles and `MotionTokens.queueReorder`; the move commands already
  exist, so wiring the drag later is presentation-only.
- Empty state: prompt + open-library button.

## Shell wiring (`:app`)

`ShellNavHost` replaced the NOW_PLAYING and QUEUE placeholders with
`NowPlayingRoute` / `QueueRoute`. Both VMs are destination-scoped behind the
same provider seams as the detail screens (`nowPlayingViewModelProvider`,
`queueViewModelProvider`), so the non-Hilt shell tests inject doubles.
Collapse returns to the previous destination; "open queue" navigates the queue
route; empty states navigate to the library.

## Tests

- `:feature-library` `NowPlayingViewModelTest` (9) — state mapping (entry
  fallback, duration fallback, progress clamps, shuffle/repeat mapping) and
  command dispatch (play/pause, skips, seek clamping, shuffle toggle, repeat
  cycle).
- `:feature-library` `QueueViewModelTest` (9) — order mapping under shuffle,
  header position, jump dispatch, remove dispatch, move-up/down translation +
  bounds + shuffle refusal, clear-except-current.
- `:playback` `PlaybackControllerImplTest` (+1, 255 module total) — queue
  edits remove, move and clear through the command port.
- `:app` `PlayerScreensRenderTest` (11) — both screens' content/empty states,
  disabled controls, seek label rendering, the button-twin audit over every
  registered gesture, and command wiring from taps.
- `:app` `PlayerScreensGoldenTest` (2) — Roborazzi goldens
  `now_playing_portrait.png` and `queue_portrait.png` (411 × 891 dp), verified
  in compare mode after the `Surface` fix.

## Deferred / device-gated

- Drag-reorder on queue rows (presentation only; commands shipped).
- Per-slot block model, top-bar overflow, slider styles and favorite/rating
  actions — Phase 9.
- Art-gesture feel (threshold, directional intent, accidental triggers),
  swipe-to-dismiss feel, scrub feel and notification/lock-screen layouts are
  device-gated and stay [UNKNOWN] until hardware verification.
