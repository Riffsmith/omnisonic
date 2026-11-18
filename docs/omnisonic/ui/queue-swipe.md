# Queue row swipe-to-remove — `ui/queue-swipe.md`

Purpose: canonical spec for the queue row's swipe-to-remove gesture — one
right-to-left direction, a threshold commit that springs the row off-screen
before the removal command runs, a reserved but disabled second direction, and
the swipe-versus-scroll ownership that keeps the list scrollable.

How derived: own design, approved plan `.agents/plans/queue-swipe-removal-plan.md`
(§1 evidence, §3 user decisions Q1-Q4, §4 changes). The gesture model is a
clean-room borrow of the studied reference player's per-direction swipe-action
structure (threshold + reveal + dismiss callback, evidence recorded in plan §1);
no studied-app name or code is reproduced. The spring reuses the existing
`MotionTokens.nowPlayingExpand` token rather than adding a motion system.

Confidence: implementation, rules and tests `[VERIFIED]` against
`:feature-library` `ui/QueueScreen.kt` and `QueueSwipeRulesTest` plus the
`:app` `PlayerScreensRenderTest` queue cases; threshold/spring *feel* on hardware
is device-gated `[UNKNOWN]` (§7). The pre-existing D-24 return latch is
`[VERIFIED]` unchanged (code untouched; its tests green).

Date: 2026-11-16

Links: `ui/gesture-contract.md` (swipe vs scroll ownership, pointer passes);
`ui/player-queue-return.md` (D-24 return latch, unchanged);
`ui/player-screens.md` (queue screen layout); `queue-ux-improvements.md`
(a committed swipe removal is now undoable via the snackbar — thresholds, direction and gesture rules below are unchanged);
`decisions.md` (D-26; cross-links D-24);
`.agents/plans/queue-swipe-removal-plan.md`.

---

## 1. Structure

The queue stays a standalone route (Q1); the row makes no route-transition
assumption and can move into a sheet block later without rewriting its layout.

Each `QueueRowItem` (`ui/QueueScreen.kt`) is a `Box` of two layers:

1. The reveal background — `matchParentSize`, `errorContainer`, a `Delete`
   icon aligned center-end, behind the row. It is decorative
   (`contentDescription = null`); the row's delete `IconButton` is the
   accessibility twin.
2. The content row — cover, title/artist, current marker, jump-on-tap, delete
   button, overflow menu — unchanged from the pre-swipe row, carrying a
   horizontal `offset` **outside** its opaque `background`, so the background
   translates with the content and the reveal layer underneath becomes
   visible (an inner offset would slide the content over a stationary row
   background).

The row's live position is one `Animatable<Float>` keyed by `queueItemId`
(`remember(row.entry.queueItemId)`), so rows own independent offsets across
recomposition and list recycling.

## 2. Rules (`QueueSwipeRules`, pure)

Offsets are horizontal pixels: `0` at rest, negative in the revealed delete
direction.

- **Drag clamp** — `dragOffset(current, deltaXPx, rowWidthPx, startToEndEnabled)`
  adds the finger delta and clamps to `[-rowWidthPx, max]`. `max` is `0` while
  the start-to-end direction is disabled; positive travel therefore does
  nothing today and is honoured only when that slot is enabled.
- **Threshold commit** — `commitsOnRelease(offsetPx, rowWidthPx, thresholdFraction)`
  is `|offset| >= rowWidth * thresholdFraction`. The delete threshold is
  `DELETE_THRESHOLD_FRACTION = 0.5` of the row width (plan §4.1 starting
  value). The rule is total: every release either commits or rests.
- **Settle target** — `settleTarget(...)` returns `0f` (spring back) under the
  threshold, or the full row width signed in the dragged direction (spring
  off-screen) at/over it.
- **Preconditions** — a positive row width and a positive threshold are
  required; violating either is an `IllegalArgumentException` (row width is
  measured, never assumed).

`QueueSwipeAction` is the small per-direction config: `enabled`,
`thresholdFraction`, `background`, `icon`, `onDismiss`.

## 3. Gesture and motion

- **Detection** — `Modifier.pointerInput` with `detectHorizontalDragGestures` on
  the content row. The detector claims only after horizontal touch slop, so
  taps, vertical scrolls and taps on the row's inner controls are untouched.
- **Follow the finger** — each horizontal drag delta is consumed and
  `snapTo`'d (clamped) on the offset `Animatable`; there is no animation lag
  while the finger is down.
- **Release** — `onDragEnd` reads the settle target. Under threshold the row
  animates to `0f`; at/over it the row animates to `±rowWidth` and only then
  calls `onDismiss`. `onDragCancel` always springs back to `0f`.
- **Spring** — `spring(dampingRatio, stiffness)` built from
  `MotionTokens.nowPlayingExpand` (0.85 / 380). One spring for both settle
  directions; no new motion token.
- **Removal** — `onDismiss` calls the same `onRemove(queueItemId)` as the
  delete button; the row disappears when the queue state updates
  (`QueueViewModel.remove` → `PlaybackController.removeFromQueue`).

## 4. Direction model

- **Live now:** right-to-left (end-to-start) reveal + delete.
- **Reserved, disabled:** start-to-end. The row passes
  `startToEndEnabled = false`; `dragOffset` refuses positive travel and no
  background/command is wired. Enabling it later is one config plus the
  sign-aware `settleTarget` that already exists — the row layout does not
  change (Q2).
- Direct drag-reorder is **not** part of this gesture surface; overflow
  move-up/move-down remains the only reorder (Q4, Phase 9 owns drag handles).

## 5. Gesture-conflict rules

- The row owns horizontal drag; the `LazyColumn` owns vertical scroll. Because
  the horizontal detector is on the row (a child of the list) and consumes
  only after horizontal slop, a vertical drag never reaches it and is not
  consumed before the list sees it. No ancestor consumes pointer events (the
  task-4e rule in `ui/gesture-contract.md`).
- The windowed player-sheet handoff (`ui/player-queue-return.md`, D-24) is
  vertical and orthogonal to this gesture; it is unchanged.
- The row's clickable, delete button and overflow menu keep their own
  semantics and twins (`GestureTwin("queue-remove", …)`); the swipe adds no
  new gesture-only action.

## 6. Edge cases

- Empty queue: no rows; nothing to swipe.
- Current row: still swipable; removing it is the controller's existing
  handling.
- Shuffled queue: reorder is refused, swipe is unaffected.
- Rapid successive swipes on different rows: each `Animatable` is keyed by
  `queueItemId`, so offsets are independent.
- A swipe that starts horizontal and turns vertical: the horizontal detector
  already claimed the gesture; the list does not also scroll it (standard
  Compose slop ownership). Vertical drags that start vertical scroll the
  list.
- Configuration change/recomposition: the offset survives only as long as the
  row's remembered state; a settle always ends at `0` or off-screen, so a
  restored row is never parked mid-reveal.

## 7. Device-gated `[UNKNOWN]`

- The 50% threshold and the 0.85/380 spring feel on hardware (accidental
  commit rate, snap-back feel) — Robolectric proves targets and dispatch, not
  feel.
- A diagonal drag that begins inside the row's horizontal slop window while
  the list is also scrollable (the same class of ambiguity the old dismiss box
  had; plan §6.4 expects the list to win vertical).

## 8. Tests

- `:feature-library` `QueueSwipeRulesTest` (11): finger-follow, full-width
  clamp, reserved-direction refusal/enable, under-threshold spring-back,
  at/past threshold commit, signed settle target, total release sweep, and
  both preconditions.
- `:app` `PlayerScreensRenderTest` queue cases (now 23 in the class): reveal +
  finger-follow + spring-back, commit past threshold invokes `onRemove`,
  under-threshold keeps the row, and a vertical drag on a row still scrolls
  the list (asserted via `VerticalScrollAxisRange`).
- Existing suites unchanged by this work; goldens byte-stable
  (`:app:verifyRoborazziDebug` green; `queue_portrait.png` untouched) because
  the resting frame is identical.
- Gate: `./gradlew :app:assembleDebug test lint` green; banned-name scan over
  every touched file.

## 9. Where the code lives

- `:feature-library` `ui/QueueScreen.kt` — `QueueRowItem` (custom reveal row),
  `QueueSwipeAction`, `QueueSwipeRules`.
- `:feature-library` `ui/QueueScreen.kt` — `QueueViewModel.remove` /
  `QueueUiState` unchanged; `player/QueuePullHandoff.kt` and
  `player/PlayerSheetRules.kt` unchanged.
- `:app` `PlayerScreensRenderTest` — render cases; `PlayerScreensGoldenTest`
  unchanged.
