# Queue screen UX improvements — `queue-ux-improvements.md`

Purpose: Hand off the two `progress/KNOWN_ISSUES.md` P1 queue-screen items —
undo after removing a queue row, and a moving now-playing marker in place of
the still equalizer icon — and pin the restore contract, invalidation rules and
motion gating the fix must establish. This is a standalone UX fix, **not
Phase 6 and not part of any phase**.

How derived: source review of `QueueScreen`, `QueueViewModel`, `QueueUiState`,
`QueuePlayer`, `QueuePlayerImpl`, `QueueCommandPort`,
`PlaybackControllerImpl`, the `PlaybackWiringModule` delegate, `QueueState`,
`MotionTokens`, both test-double sets and the existing queue docs
(`ui/player-screens.md`, `ui/queue-swipe.md`, `ui/player-queue-return.md`);
plus the current queue golden `queue_portrait.png`. No device was attached
during the investigation (2026-11-17), so no item in the device checklist has
run. Decisions below were taken by the user on 2026-11-17, before any code was
written.

Confidence:
- [VERIFIED source] removal deletes the entry and its play-order slot; no
  insertion or restore command exists on any layer, and `QueueState` carries no
  epoch/session token.
- [VERIFIED source] removing the playing entry advances playback, or ends it
  when the queue empties; the position is not retained.
- [VERIFIED source] the current-row marker is a static `Icons.Filled.Equalizer`;
  `QueueUiState` does not carry playback state, although
  `PlayerCoreState.isPlaying` is available on the controller.
- [VERIFIED source] the queue layer stays composed under the now-playing sheet,
  and `QueueScreen` already receives an `isRevealed` edge.
- [VERIFIED source] no `SnackbarHost`/`SnackbarHostState` exists anywhere in
  `:app`, `:feature-library` or `:style`.
- [VERIFIED source] the row's visible accessibility twin for removal is the
  trash `IconButton`; the overflow menu contains only Move up / Move down.
- [VERIFIED source] "Clear remaining" is a second unguarded destructive action
  on the same screen.
- [VERIFIED artifact] Material3's snackbar timeout is extended through
  `AccessibilityManager.calculateRecommendedTimeoutMillis`, so a timed snackbar
  satisfies WCAG 2.2.1 for touch-assist users without a custom timer.
- [INFERRED] a three-bar animated indicator gated on playing + revealed +
  animations-enabled is cheap enough to run inside the sheet; only the device
  pass can confirm frame behaviour.
- [UNKNOWN device] snackbar dwell/completion feel, TalkBack announcement order,
  animation smoothness, and the interaction between the snackbar and the sheet.

Date: 2026-11-17

Links: `player-screens.md` (queue layout, command port, move/clear
semantics), `queue-swipe.md` (D-26 threshold-commit swipe and its twin
rule), `player-queue-return.md` (D-24 reveal latch, `isRevealed`),
`style-tokens.md` (motion tokens), `../decisions.md` (D-31 to be recorded only
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
- Do not change the queue machine's skip/repeat/shuffle semantics, the D-26
  swipe rules or the D-24 return latch. Only the queue-edit command surface,
  the two screen behaviours and their test coverage change.## Implementation status

**Implemented + JVM-verified (2026-11-18); device acceptance pending.** Both
standalone P1 behaviours are in the working tree, uncommitted:

- **Undo on remove:** `QueuePlayer.insertEntry` / `QueueCommandPort.insertIntoQueue`
  restore a row without any transport call; `QueueViewModel` captures the entry,
  both indices, the post-removal id signature, the shuffle seed and the source,
  and drops the pending action on replacement, shuffle toggle/reshuffle, move,
  clear, another removal or a natural snackbar timeout. The observer always
  records the latest structure, so a removal after an earlier move/undo/invalid
  action still gets its own valid snackbar. Undo is id-guarded on expiry.
- **Animated marker:** `QueueUiState.isPlaying` comes from
  `controller.playerState`; `NowPlayingIndicator` draws three primary-tinted
  bars inside the old `uiIconSize` box and animates only while the gate in
  `nowPlayingIndicatorAnimates(isPlaying, active, durationScale, animatorsEnabled)`
  holds. Hidden, paused and reduced-motion states render the fixed static bars.

Evidence `[VERIFIED JVM 2026-11-18]`: `:playback` and `:feature-library` test
suites green (269 + 135), `:app` `PlayerScreensRenderTest` green, full gate
`:app:assembleDebug test lint` exit 0, banned-name scan clean, and
`queue_portrait.png` re-recorded as the only changed golden and verified by
`:app:verifyRoborazziDebug --rerun-tasks`. The device checklist below remains
`[UNKNOWN device]` (`adb devices` empty), so the `progress/KNOWN_ISSUES.md` P1
items stay **OPEN**. D-31 is recorded in `../decisions.md`.

## Decisions taken (2026-11-17, user-approved)

1. **Undo reverses the edit, not transport.** Restoring a removed row never
   starts playback, seeks, or resumes a paused track.
2. **Undo of the last remaining row restores it and stays idle.** The restored
   entry becomes the pointer target; the user resumes deliberately.
3. **Snackbar duration is `SnackbarDuration.Long` first**, relying on the
   platform accessibility timeout extension. Revisit `Indefinite` only if the
   device pass shows the timed window is too short.
4. **Only structural changes invalidate a pending undo:** queue replacement
   (`setQueue` from a new tap), shuffle toggle/reshuffle, a move, a clear, or
   another removal. A jump or a play/pause does not invalidate it.
5. **Paused marker is static bars in the primary tint**, not a dimmed icon and
   not a frozen animation frame.
6. **Clear-remaining undo is a follow-up**, not part of this fix.
7. **"Scroll speed should scale with hold distance" means edge-hold distance
   during drag auto-scroll** — the longer the finger is held inside the list's
   top/bottom edge band, the faster the list scrolls. This applies to the
   deferred Phase 9 drag-reorder only; nothing is built now.

## Symptoms (from `progress/KNOWN_ISSUES.md` P1)

1. **Undo on remove:** removing a queue item (swipe or trash button) is
   immediate and irreversible; there is no time-limited undo.
2. **Animated now-playing marker:** the still icon at the left of the current
   row should be a moving/animated indicator.

Reproduction:
- Swipe a queue row past half its width (or tap its trash button): the row
  disappears at once, playback advances when it was the current row, and no
  affordance offers to bring it back. Remove every row this way and the queue
  is empty with no recovery.
- Open the queue and watch the current row: the blue equalizer glyph never
  moves, whether the track is playing or paused.

## Evidence

### E1 — removal has no restoration path [VERIFIED by source]

- `feature-library/src/main/java/app/omnisonic/feature/library/ui/QueueScreen.kt:525-545`:
  the row's horizontal drag ends in `settleTarget(...)`; a non-zero target
  springs the row off-screen and then calls `currentDismiss.value()`
  (`:532-542`), which is `onRemove(row.entry.queueItemId)` via
  `deleteSwipe.onDismiss` (`:479-486`).
- `:598-604`: the trash `IconButton` calls the same `onRemove` directly.
- `feature-library/.../queue/QueueViewModel.kt:34`: `remove(queueItemId)` is a
  single forward to `controller.removeFromQueue(queueItemId)`; the VM keeps no
  record of what was removed.
- `playback/src/main/java/app/omnisonic/playback/queue/QueuePlayerImpl.kt:155-183`:
  `removeEntry` deletes the id from `playOrder` and the entry from `entries`,
  decrements `playIndex` when the removal preceded it, then either starts the
  following entry (`startCurrent(QUEUE_EDIT, previous)`), ends playback, or
  emits `TrackChanged` when the removal was not current. Nothing is retained.
- `playback-api/.../queue/QueuePlayer.kt:20-27` and
  `playback-api/.../queue/QueueCommandPort.kt:18-30`: the only structural edits
  are `setQueue`, `append`, `prependNext`, `removeEntry`, `moveEntry`,
  `clearQueue`, `restack` and the port's remove/move/clear. There is **no
  insert-at-index or restore-position command**.
- `playback-api/.../queue/QueueModels.kt:46-56`: `QueueState` has no epoch,
  revision or source-instance token, so undo validity cannot be inferred from a
  monotonic counter today.
- `playback/.../controller/PlaybackControllerImpl.kt:246-249` and
  `playback/.../di/PlaybackWiringModule.kt:285`: the port and its late-bound
  delegate forward each command one-for-one; a new command must be added in
  both places plus every test double.
- `grep SnackbarHost` over `app`, `feature-library` and `style` returns
  nothing: the snackbar host is new infrastructure, not a re-use.

### E2 — the marker is static and cannot see playback state [VERIFIED by source]

- `ui/QueueScreen.kt:563-574`: `if (row.isCurrent)` renders
  `Icons.Filled.Equalizer`, `tint = primary`,
  `size = tokens.imagery.uiIconSize`, `contentDescription = queue_current_marker`;
  otherwise a same-size `Spacer`. The resting frame is captured in
  `app/src/test/roborazzi/queue_portrait.png`.
- `feature-library/.../queue/QueueUiState.kt:18-31`: `QueueUiState` carries
  `rows`, `currentOrderIndex` and `shuffled` only. `toQueueUiState()`
  (`:33-42`) maps the queue snapshot alone; playback state is not passed in.
- `playback-api/.../controller/PlaybackController.kt:14-21`:
  `PlayerCoreState.isPlaying` exists and the controller already exposes
  `playerState: StateFlow<PlayerCoreState>` (`:30`).
- `ui/QueueScreen.kt:147-150` (comment) and `:151-163`: the queue layer stays
  composed while the now-playing sheet covers it; positioning waits for the
  `isRevealed` edge. An unconditional infinite transition would therefore
  animate while invisible.
- The jump pill reuses the same static glyph
  (`ui/QueueScreen.kt:441-451`); it is a navigation affordance, not a playback
  state indicator, and stays static.

### E3 — removal affordances are deliberately twinned [VERIFIED by source]

- `ui/QueueScreen.kt:474`: `GestureTwin(GESTURE_QUEUE_REMOVE, removeLabel, removeLabel)`;
  dispatch at `:556-559`.
- The visible twin is the trash button (`:598-604`); the overflow menu
  (`:605-630`) only offers Move up / Move down, both disabled when shuffled.
- Therefore dropping the trash button (P2) requires an overflow "Remove" item
  first. This fix must keep both removal paths working; undo is layered on the
  command, not on one gesture.

### E4 — "Clear remaining" is a second destructive edit [VERIFIED by source]

- `ui/QueueScreen.kt:216-222` calls `onClearRemaining`, wired at `:104` to
  `QueueViewModel.clearQueueExceptCurrent()` (`QueueViewModel.kt:48`), which
  calls `controller.clearQueueExceptCurrent()` and, in the machine, keeps only
  the current entry. No confirmation, no undo.

### E5 — documentation is stale about the search cluster [VERIFIED by source]

- `docs/omnisonic/README.md` still describes
  `ui/search-ux-improvements.md` as "docs only — not implemented", while
  commit `7ded15d` shipped it and `progress/KNOWN_ISSUES.md` records it as
  implemented + JVM-verified. Fixed in this documentation change.

### Test coverage [VERIFIED]

- `:feature-library` `QueueViewModelTest` (9 tests) covers mapping, jump,
  remove dispatch, move translation/limits, shuffle refusal and clear dispatch;
  it has no undo concept.
- `:playback` `QueuePlayerImplTest` (59 tests) covers remove/move/clear,
  shuffle pointers and prepare behaviour; there is no insertion case.
- `:app` `PlayerScreensRenderTest` (23 tests) renders the queue, jump, remove,
  move limits, gesture twins and four swipe cases; nothing asserts a snackbar
  or animated marker.
- `app/src/test/roborazzi/queue_portrait.png` is the queue golden; plain
  `test` does not verify goldens (`:app:verifyRoborazziDebug` does, and
  `--rerun-tasks` is required on this Gradle wrapper).

## Chosen design

### Undo on remove through a queue-insertion command

#### Machine contract (`:playback-api` + `:playback`)

Add one command to `QueuePlayer` and the port, keeping playback untouched:

```kotlin
fun insertEntry(entry: PlaybackQueueEntry, entriesIndex: Int, orderIndex: Int)
```

Semantics, in `QueuePlayerImpl`:

1. No-op when the id already exists in `entries` (idempotent double-tap undo).
2. `entriesIndex` clamps to `0..entries.size`; insert into the stable list.
3. Unshuffled: rebuild `playOrder` from the entries ids (the `moveEntry`
   pattern, `QueuePlayerImpl.kt:195-197`), then re-point `playIndex` at the
   surviving current id with `repositionPlayIndexTo` (`:602-605`). When there is
   no current entry (the removal emptied the queue), set `playIndex` to the
   inserted order position instead, so the restored row is the pointer target.
4. Shuffled: splice the id into `playOrder` at `orderIndex` clamped to
   `0..playOrder.size`, then re-point `playIndex` by id as above. The shuffle
   seed and the relative order of the other ids are unchanged.
5. `refreshState()` then `schedulePrepareFollowing()` (`:550-551` returns
   immediately while not playing, so an idle restore prepares nothing).
6. **Never** call `startCurrent`, `player.play`, `seekTo`, `replayCurrent`, or
   emit `TrackChanged`. Undo is not a transport command.

Port/controller plumbing: `QueueCommandPort.insertIntoQueue(...)` →
`QueueCommandPort` implementation in `PlaybackControllerImpl` (near
`:246-256`) → the `PlaybackWiringModule` delegate (`:280-291`).

#### ViewModel state

```kotlin
data class PendingRemoval(
    val entry: PlaybackQueueEntry,
    val entriesIndex: Int,
    val orderIndex: Int,
    /** Ordered entry ids observed right after this removal; no pointer data. */
    val signature: List<Long>,
)
```

- `remove(queueItemId)` captures the row's `entry`, its index in
  `QueueState.entries`, its `orderIndex` and the **expected post-removal
  signature** (the live ids with the removed id dropped, order preserved)
  before dispatching the command. `QueueUiState` exposes a display-only
  `pendingUndo: PendingUndoUi?` (title + artist) for the snackbar copy.
- `undoRemove()` re-reads `state.value` and dispatches
  `insertEntry(entry, entriesIndex, orderIndex)` only when the live id list
  equals the captured signature; otherwise it clears the pending action and
  does nothing. The signature check is what makes a stale undo safe after an
  unrelated edit. It deliberately covers ids only — not `playIndex`, position
  or transport — so a jump, a pause or an automatic advance cannot invalidate
  it. A queue replacement that happens to repeat the same ids is harmless: the
  pending id is present again, and `insertEntry`'s duplicate check makes the
  undo a no-op.
- Invalidation (user-approved): `setQueue`/source replacement, shuffle toggle
  or reshuffle, `moveUp`/`moveDown`, `clearQueueExceptCurrent`, and another
  `remove`. **Not** invalidated by `jumpTo`, play/pause, seek, track advance or
  the natural snackbar timeout (expiry simply drops the pending action).
- Only one pending removal exists at a time; a second removal replaces it and
  the host shows the newer message.

#### Screen

- `QueueRoute` passes `pendingUndo` and `onUndo = viewModel::undoRemove`.
- The queue body becomes a `Box` with a `SnackbarHost(hostState)` aligned
  bottom-centre above the jump pill, and bottom content padding that accounts
  for it. `LaunchedEffect(pendingUndo)` shows
  `snackbarHostState.showSnackbar(message, actionLabel = undoLabel,
  withDismissAction = false, duration = SnackbarDuration.Long)`; a second
  `LaunchedEffect` keyed on the pending identity dismisses the previous
  snackbar so messages cannot queue up behind a removal.
- Copy: "Removed %1$s" (`queue_removed_message`) and "Undo"
  (`queue_undo`), using the existing title string for `%1$s`. No new copy for
  expiry — the snackbar just disappears.
- Swipe interplay: the removed row's composition (and its `Animatable` offset)
  goes away with the list item; the restored row composes fresh at offset `0`,
  so no off-screen residue is possible. `queue-swipe.md` needs a one-line note
  that a committed swipe is now undoable, not a rules change.

### Animated now-playing marker

#### State plumbing

- Add `isPlaying: Boolean = false` to `QueueUiState` and a parameter to
  `toQueueUiState(isPlaying)`. `QueueViewModel` combines
  `controller.queueState` with `controller.playerState.map { it.isPlaying }`
  (`combine(...)` + `stateIn`, keeping `SharingStarted.Eagerly`).
- The default keeps every existing test/golden construction compiling and makes
  the paused/static rendering the default in render tests.

#### Composable

`NowPlayingIndicator(isPlaying: Boolean, active: Boolean)` in
`feature-library/.../ui/QueueScreen.kt` (or a small `ui/NowPlayingIndicator.kt`
if it grows):

- Draws three rounded bars in a `Canvas`, widths/gaps derived from
  `tokens.spacing` and the icon box, heights animated with one
  `rememberInfiniteTransition`; per-bar tweens use staggered
  `initialStartOffset` and `RepeatMode.Reverse`, tint `primary`.
- **Gates:** animate only when `isPlaying && active && durationScale > 0f &&
  ValueAnimator.areAnimatorsEnabled()`. `active` is the screen's `isRevealed`.
  Otherwise draw the static bars at fixed fractions (≈0.55 / 0.85 / 0.65) in
  the primary tint — the user-approved paused look and the deterministic frame
  for goldens.
- Keep the container at `tokens.imagery.uiIconSize` and keep
  `contentDescription = queue_current_marker` on the container, so layout,
  semantics and the existing "current marker" assertions survive. The bars are
  decorative.
- New token: `MotionTokens.queuePlayingIndicator: MotionTween =
  MotionTween(durationMs = 900)`; the stagger fraction is a private constant in
  the composable, not a style key. Document it in `ui/style-tokens.md`.
- Playback state changes are not announced; only the row's existing
  description and tap role remain in the semantics tree.

## Out of scope (P2, deferred to Phase 9 configurability)

- **Drag-reorder:** hold the row's left edge and drag vertically; auto-scroll
  speed scales with **edge-hold distance** (user-confirmed interpretation).
  Conflicts to resolve then: the D-26 reserved start-to-end swipe slot, the
  list's vertical scroll ownership, and `MotionTokens.queueReorder`. The move
  commands already exist, so this is presentation-only when it is scheduled.
- **Removing the trash button:** only after the overflow menu gains a Remove
  item and the twin rule in `ui/gesture-contract.md` is explicitly revisited.
  Until then the button is a required accessibility twin, not redundancy.

## Files likely to change

| File | Change |
|---|---|
| `playback-api/.../queue/QueuePlayer.kt` | `insertEntry(entry, entriesIndex, orderIndex)` |
| `playback-api/.../queue/QueueCommandPort.kt` | `insertIntoQueue(...)` |
| `playback/.../queue/QueuePlayerImpl.kt` | insertion implementation (entries + playOrder splice, pointer re-point, no transport) |
| `playback/.../controller/PlaybackControllerImpl.kt` | forward the new port command |
| `playback/.../di/PlaybackWiringModule.kt` | late-bound delegate forward |
| `feature-library/.../queue/QueueUiState.kt` | `isPlaying`, `pendingUndo` |
| `feature-library/.../queue/QueueViewModel.kt` | combine playback state, capture/validate pending removal, `undoRemove()` |
| `feature-library/.../ui/QueueScreen.kt` | `SnackbarHost` + `LaunchedEffect`, `NowPlayingIndicator`, `isPlaying`/`undo` wiring |
| `style/.../StyleTokens.kt` | `MotionTokens.queuePlayingIndicator` |
| `feature-library/src/main/res/values/strings.xml` | `queue_removed_message`, `queue_undo` |
| `feature-library/src/test/.../PlayerScreenTestDoubles.kt` | `FakePlaybackController.inserted` + `insertIntoQueue` |
| `feature-library/src/test/.../queue/QueueViewModelTest.kt` | pending-undo capture/validation cases |
| `playback/src/test/.../queue/QueuePlayerImplTest.kt` | insertion semantics (5-6 cases) |
| `playback/src/test/.../player/TestQueuePort.kt`, `playback/src/test/.../controller/PlaybackControllerImplTest.kt` | new port method |
| `feature-library/src/test/.../detail/DetailViewModelTest.kt` | fake implements the new method |
| `feature-search/src/test/.../SearchViewModelTest.kt` | fake implements the new method |
| `app/src/test/.../shell/PlayerScreenTestDoubles.kt` / `DetailTestDoubles.kt` | `NoopPlaybackController` + fake implement the new method |
| `app/src/test/.../shell/PlayerScreensRenderTest.kt` | snackbar/undo/marker cases |
| `app/src/test/roborazzi/queue_portrait.png` | deliberate re-record (marker changed) |
| `docs/omnisonic/ui/style-tokens.md`, `queue-swipe.md`, `player-screens.md` | one-line contract/link updates after the fix lands |

## Testing and verification

JVM (`:playback` `QueuePlayerImplTest`):
1. Insert restores the exact entries index and leaves the current id playing
   unchanged (player start/seek calls unchanged).
2. Unshuffled insert rebuilds play order and keeps the pointer on the same
   current id; `playIndex` shifts correctly when inserting before it.
3. Shuffled insert splices at `orderIndex`, preserves the seed and the relative
   order of the other ids.
4. Inserting into an emptied queue makes the restored entry the pointer target
   while playback stays idle (no play, no seek, no prepare).
5. Duplicate id is a no-op; out-of-range indices clamp.

JVM (`:feature-library` `QueueViewModelTest`):
6. Remove captures entry + both indices + post-removal signature and exposes
   the pending display state.
7. Undo dispatches the captured values once; a second undo is a no-op.
8. Signature mismatch (shuffle, move, clear, new queue) invalidates; a jump or
   play/pause keeps it valid.
9. A second removal replaces the pending action.

UI (`:app` `PlayerScreensRenderTest`):
10. Removing a row shows a snackbar with an actionable "Undo"; tapping it
    invokes `onUndo` exactly once.
11. The current row renders the marker with `queue_current_marker`; paused
    state renders the static fallback; the golden remains deterministic.
12. A pure gating function (playing/active/durationScale) is covered without
    relying on frame timing.

Golden: re-record `queue_portrait.png` deliberately with `isPlaying = false`
and confirm by sha256 that no other golden changed; verify with
`:app:verifyRoborazziDebug --rerun-tasks`. Gate:
`./gradlew :app:assembleDebug test lint`; banned-name scan over every touched
file.

Device acceptance (needs an attached device; every item is `[UNKNOWN device]`):
1. Swipe a row past half: it leaves, the snackbar appears with "Undo", and
   playback continues as today.
2. Tap Undo: the row returns to its exact position; playback does not restart,
   seek, or change track; the current row is still the same track.
3. Let the snackbar time out: it disappears and the removal stays.
4. Enable TalkBack and a long touch-assist timeout: the snackbar stays long
   enough to act, and the action is reachable.
5. Remove the playing row, then Undo: the advanced track keeps playing and the
   restored row sits in its old slot.
6. Remove the only row until the queue is empty, then Undo: the row returns and
   playback stays idle; pressing play starts that entry.
7. Remove a row, then reorder or clear before Undo: the stale undo does not
   corrupt the queue (it simply disappears).
8. With playback running, open and close the queue repeatedly: the marker
   animates only while the queue is visible and only while playing; no visible
   jank during scroll.
9. Pause and resume: the marker switches to static bars and back, with no
   layout shift.
10. Record the results in `progress/KNOWN_ISSUES.md` and `progress/STATE.md`
    with tags; only then close the items.

## Risks and constraints

- **Undo vs. playback is the design fork.** The approved rule is
  structure-only; forgetting it (e.g. calling `replayCurrent` or `setQueue`)
  turns undo into a transport action and resets position.
- **Stale undo must be impossible to corrupt the queue.** The signature check
  is mandatory; without it, an undo after shuffle/move/clear would insert a
  row against a different order.
- **New port method ripple.** Five production sites and six test doubles must
  implement it in the same change or the build breaks.
- **Animation while hidden.** The queue is composed under the sheet; the
  `isPlaying && isRevealed` gate plus `areAnimatorsEnabled`/
  `durationScale` is required, not optional.
- **Golden churn is expected exactly once.** A second unexplained golden diff
  means the marker leaked into the paused state or a row's size changed.
- **Snackbar inside the sheet.** The host lives in the queue screen; if the
  user collapses the sheet the host is still composed but obscured. Do not
  hoist it to the shell in this fix (that would couple the queue to shell
  layout); note the behaviour in the device pass.
- **No new hard-coded dimensions or colours.** Bar geometry derives from style
  tokens; tints come from `MaterialTheme.colorScheme.primary`.
- **Scope creep.** Do not add drag-reorder, an overflow Remove item, clear
  undo, a confirm dialog, or Phase 9 style keys.

## Remaining open questions (non-blocking; recommended defaults in brackets)

1. Should the snackbar be dismissed when the sheet is collapsed? [No — let it
   time out; hoisting the host is out of scope.]
2. Should "Clear remaining" at least ask for confirmation now that single-row
   removal is undoable? [No — file the undo follow-up instead; a dialog is a
   different interaction.]
3. Should the marker animate when the queue is visible but the app is in the
   background? [`areAnimatorsEnabled` and lifecycle already stop frames; no
   extra gate.]

## Acceptance criteria

- [x] Removing a row shows a snackbar with Undo; Undo restores the entry at its
      captured entries index and playback order position. `[VERIFIED JVM]`
- [x] Undo never starts, stops, seeks or changes the current track; an emptied
      queue restores the row and stays idle. `[VERIFIED JVM]`
- [x] A pending undo is invalidated by queue replacement, shuffle, move, clear
      or another removal, and by nothing else (jump/play/pause/seek/advance
      keep it; natural timeout drops it silently). `[VERIFIED JVM]`
- [x] The current row shows a three-bar indicator that animates only while
      playing and while the queue surface is revealed; paused, hidden and
      reduced-motion states show static bars in the primary tint. Gate and
      render covered on the JVM; animation smoothness `[UNKNOWN device]`.
- [x] Layout, content description and row height are unchanged; no new
      hard-coded dimensions or colours. `[VERIFIED JVM/golden]`
- [x] JVM tests cover insertion semantics, pending-undo validation and the
      snackbar/marker rendering; the full gate and banned-name scan pass.
      `[VERIFIED JVM]`
- [x] `queue_portrait.png` is re-recorded deliberately and is the only changed
      golden. `[VERIFIED JVM]`
- [ ] Device checklist recorded; the P1 items stay open until it is green.
      `[UNKNOWN device]` — no device attached on 2026-11-18.
- [x] D-31 and the cross-linked docs are updated only after the implementation
      lands; no phase status or task checkbox changed. `[VERIFIED]`

## Where implementation starts

1. Read this brief, `ui/player-screens.md`, `ui/queue-swipe.md` and
   `ui/style-tokens.md`.
2. **Machine first:** add `insertEntry` to `QueuePlayer`, implement it in
   `QueuePlayerImpl`, extend `TestQueuePort`/`PlaybackControllerImplTest`, and
   prove the five insertion cases with no transport calls.
3. **Port seam:** add `insertIntoQueue` to `QueueCommandPort`, forward it in
   `PlaybackControllerImpl` and the wiring delegate, then update every test
   double so the tree compiles.
4. **ViewModel:** combine `isPlaying`, capture/validate `PendingRemoval`, add
   `undoRemove`, and cover the invalidation matrix.
5. **UI:** snackbar host + effects, then `NowPlayingIndicator` with the gating
   function and token; wire `isRevealed`.
6. Run module suites, then `./gradlew :app:assembleDebug test lint` and the
   banned-name scan; re-record the queue golden deliberately and verify with
   `--rerun-tasks`.
7. Device pass (checklist above), then update `style-tokens.md`,
   `queue-swipe.md`, `player-screens.md`, the progress records, findings, and
   add D-31 to `decisions.md`.
