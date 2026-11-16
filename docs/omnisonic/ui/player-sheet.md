# Player sheet — three detents, one spring, sheet-owned settle

Status: shipped 2026-11-16 as the out-of-band player-navigation item D-22 (the
lyrics pane is D-23, the return-latch revision D-24). Revised the same day by
D-25: the `AnchoredDraggableState` façade was replaced by a sheet-owned state
machine after a device defect in which a post-lift nested-scroll delta could
park the sheet between detents. Supersedes the settle design of D-19/D-20 in
this file and absorbs the motion sections of `ui/player-ux-fixes.md` §3-§5.
Spec context: `ui/gesture-contract.md`, `ui/player-queue-return.md`,
`ui/player-screens.md`, `decisions.md`.

Requirement [VERIFIED, user report 2026-11-16]: expand, collapse and the
queue reveal are **one sheet continuum** (peek → now-playing → queue) whose
releases must feel the same on every device; the hand-written settle chain was
tuned against one screen and misclassified slow pulls on the other, and a
release must always finish on a detent even when a stale callback arrives after
the finger is gone (D-25). Lyrics is a pane of the player surface, not a screen
(D-23).

## 1. Why a shell overlay and not a navigation transition

NavHost transitions in `navigation-compose` 2.8.2 are `AnimatedContent`-style
enter/exit lambdas over an internal, non-seekable transition. Nothing public
exposes the transition's progress, so a drag cannot position them, and a route
push replaces the destination, so the previous screen is not composed to be
revealed [VERIFIED: `javap` over `navigation-compose-2.8.2-api.jar`,
`NavHostKt` has no transition-state parameter].

The player therefore lives in a **shell-level overlay** (`:app`
`PlayerSheetOverlay`) above the whole shell — content, mini player and
navigation bars — while the current route keeps rendering underneath. Pulling
the sheet down uncovers that still-composed route.

The sheet owns its position: `PlayerSheetState` is the only writer, and every
release resolves to a detent before the state machine lets go of the gesture
(§3). The overlay itself only reads `position` to translate its two layers.

## 2. Geometry and the three detents

One number, `position`, in viewport fractions (`:feature-library`
`PlayerSheetStops`):

| `position` | Detent | Surface |
|---|---|---|
| `-1` | `SWIPE_UP_TARGET` | the swipe-up target fully shown (queue by default) |
| `0` | `NOW_PLAYING` | now-playing fully shown |
| `1` | `PEEK` | sheet off-screen, only the mini player bar |

`PEEK` is D-20's `COLLAPSED` renamed: same resting place, the bar is the only
visible layer. There is no fourth layer.

- now-playing layer: `translationY = position * viewportPx`
- swipe-up target layer: `translationY = (position + 1) * viewportPx`

A detent's value is also its rest position, and `PlayerSheetState` writes it
directly — there is no separate anchor model and nothing converts between the
two.

## 3. State: a sheet-owned state machine (D-25)

`:app` `shell/PlayerSheetHost.kt` holds `PlayerSheetState`, which owns its
position outright: `position` is a `mutableFloatStateOf` in viewport fractions
and this class is its only writer. There is no `AnchoredDraggableState` on the
hosted path. D-22's framework façade was replaced after the RMX3085 defect in
which a nested-scroll delta arriving *after* the lift cancelled the settle and
left the sheet parked between detents with the finger already up (§9).

One gesture epoch governs every write:

- `onPointerDown()` opens a gesture (idempotent) and is the **only** thing
  that cancels a running settle. The shell root calls it from a non-consuming
  pointer observer, so it sees every pointer stream — including the one that
  starts on the mini bar while the sheet is off-screen.
- `drag` / `dragFromSwipeUpTarget` are **refused while no gesture is open**,
  so a post-lift nested-scroll callback or a duplicated lift is dropped instead
  of moving the sheet. `dragFromSwipeUpTarget` also marks the gesture bounded,
  so the all-up net releases it with the queue rule.
- `release(v, flingPx)` / `releaseFromSwipeUpTarget(v, flingPx, commitPx)` pick
  one detent through `PlayerSheetRules` and close the gesture.
- `onAllPointersUp(flingPx, commitPx)` is the net for a gesture whose release
  never arrived (system cancellation, focus loss, the last of several pointers
  lifting). It settles with zero velocity through the rule that started it;
  it replaces the old `endDragFallback`.
- `settleTo(target)` springs from the current position with the same
  `MotionTokens.nowPlayingExpand` spring used for every detent, and in a
  generation-guarded `finally` writes `target` if the animation was cut short
  and no finger took over. **No finger down therefore always means on a
  detent.**

### 3.1 One fling gate for every release

Every release path uses the device's `ViewConfiguration.scaledMinimumFlingVelocity`,
read once in `OmnisonicApp` and passed to `PlayerSheetRules.settleTarget` and
`pullTarget`. D-22's fraction-of-viewport gate (`0.06f`) is gone: the value
Android already uses for "this was a fling" is the one the sheet uses, on both
the full-range and bounded paths.

`PlayerSheetRules` stays pure and **total**: both release rules return one of
`PlayerSheetStops.ORDERED` for any position and velocity, so a release can
never hand the state machine a non-detent target.

### 3.2 What the rules own

- **Full-range settle** (`settleTarget`): a fling carries the sheet exactly one
  detent in its own direction; anything slower settles on the nearest detent.
  A fling outward from the outermost detent stays there.
- **Bounded queue return** (`pullTarget`): a fling wins in its own direction;
  otherwise a pull past `LayoutTokens.sheetPullCommitDistance` (32 dp) commits
  to `NOW_PLAYING`; anything shorter snaps back to `SWIPE_UP_TARGET`; never
  `PEEK`.
- **Drag clamps** (`dragPosition` / `dragPositionFromSwipeUpTarget`): the
  full-range drag is clamped to `[-1, 1]`, the queue pull to `[-1, 0]`, so the
  rest of a long pull is swallowed rather than collapsing the sheet.
- **The back chain** (`backDestination`).
- **`PlayerSheetVelocity`**, fed by the queue's passive observer (historical
  samples + two-sample fallback; §7).

## 4. Gesture ownership

- **Mini player (the peek drag surface):** the bar's SwipeZone-claimed vertical
  drag forwards raw `dy` and the release velocity to the sheet instead of
  lifting itself. With no sheet host (standalone bar, goldens, bar tests) it
  keeps the 7f lift-and-navigate behaviour.
- **Now-playing:** vertical drags are claimed by one surface-level detector
  once they are vertical-dominant, are not already consumed by a child, and
  exceed touch slop. It consumes the moves while claimed. The lyrics pane is a
  leaf, so the detector keeps working over it; overflowing lyrics text is
  scrollable and consumes its own drags first. Without a host the same detector
  falls back to the `SpacingTokens.xxl` (32 dp) threshold → queue / collapse,
  which is the render-test path.
- **Artwork:** horizontal only (`detectHorizontalDragGestures`), so the art
  nudge and next/previous swipe never fight the vertical sheet drag.
- **Barrier:** the overlay's first layer is an inert `pointerInput(Unit) {}`
  that keeps touches off the route underneath while the sheet covers it. It
  never consumes — consumption on an ancestor's Main pass cancels child drag
  starts (`ui/gesture-contract.md`, task 4e bug record).

### 4.1 The child-scrollable handoff (queue)

A target that owns a scrollable list does not copy now-playing's surface
detector: that detector consumes on the Main pass and is safe only because the
now-playing surface has no scrollable child. The handoff instead has two halves
on the list itself, neither of which touches pointer consumption
(`ui/gesture-contract.md`):

- a `NestedScrollConnection` that consumes a downward `UserInput` pre-scroll
  delta while the gesture is eligible, which keeps the list pinned and leaves
  Material a zero delta (no stretch or glow);
- a **passive, never-consuming** pointer observer that latches the
  gesture-start eligibility and reports the release velocity, because
  `onPreFling` may never fire when the scrollable saw no deltas.

`QueuePullHandoff` owns the gesture lifecycle (`ui/player-queue-return.md`
§3). Eligibility (D-24) is true-list-top **or** the unscrolled reveal position,
latched once at the down: a gesture that began mid-list scrolls the list and
never moves the sheet, even if it reaches the top. The handoff opens at the down
and closes at the lift, and `shouldHandOff` refuses every delta after the lift,
so a late nested-scroll callback cannot drive the sheet. Forwarding is bounded
to `[-1, 0]`, and the release always targets `SWIPE_UP_TARGET` or `NOW_PLAYING`
— one gesture can return to now-playing but never skip it for the route
underneath. Past `LayoutTokens.sheetPullCommitDistance` (32 dp) a slow pull
commits; under it a tiny twitch snaps back; a fling still wins in its own
direction.

## 5. Mini player as the peek detent (D-22, Q4-A)

The bar's self-animated 24 dp lift is gone on the hosted path. It is now a pure
function of the sheet position (`ui/MiniPlayerBar.kt`, internal
`barExitProgress`):

```
exit      = ((PEEK - position) / (PEEK - NOW_PLAYING)).coerceIn(0f, 1f)
translationY = exit * (bar height + SpacingTokens.md)
alpha        = 1 - exit
```

At `PEEK` the bar rests untouched (`exit = 0`); by `NOW_PLAYING` it has slid
down its own height plus one spacing step and faded out (`exit = 1`). The sheet
spring animates it, so there is exactly one motion system for the bar and the
sheet. The bar's drag stays the peek drag surface, so "drag the bar up" and
"drag the now-playing surface down" are one continuum.

Without a host (`sheetPosition = null`) the 7f lift (`expandFraction`,
`MAX_LIFT_SCALE`, the `nowPlayingExpand` spring) still runs, because the
standalone bar has no sheet to be a function of.

The bar swap distance and fade curve were exercised on the RMX3085 but not
measured against an optimum; `bar height + md` is the starting point, not a
measured value. The moto g32 remains [UNKNOWN].

## 6. Actions, the configurable swipe-up target and the back chain

The swipe-up target is a **composable slot** on the sheet host, not a fixed
route reference; the queue is only the default. Phase 9 (per-slot gesture
configuration, G7) can point it at another player surface without touching the
geometry. The slot contract is two optional values, and both default to "no
gesture, treat the target as open", so the standalone routes, render tests and
goldens take the unchanged path:

- `sheetDrag: PlayerSheetDrag?` — the target's own drag path into the sheet
  (now-playing receives the full-range drag; a target with list content
  receives the bounded one, §4.1);
- `isRevealed: Boolean` — the sheet's reveal edge, for one-shot orientation
  work on each open (the queue positions its list at the current row,
  `ui/player-queue-return.md` §4.3).

Back stack and back gesture follow the visual stack — the sheet's position
decides, not the route:

- system back and the target's back arrow share one chain: target →
  `NOW_PLAYING` → `PEEK` → the route underneath
  (`PlayerSheetRules.backDestination`, `PlayerSheetState.onBack()`);
- collapse button / downward drag from now-playing → `PEEK`;
- queue's "open library" → close the sheet, then navigate the library route.

`ShellRoutes.NOW_PLAYING` / `ShellRoutes.QUEUE` remain registered so the route
table, deep links and direct navigation still resolve; a direct navigation
renders the screens through their route destinations (no sheet host there, so
they use the threshold fallback and the 7g `MotionTokens.nowPlayingRoute`
tweens — those transitions now exist **only** on the standalone-route path).

## 7. Release-velocity sampling (`:feature-library` `PlayerSheetVelocity`)

Both hosts feed every dispatched change through one helper instead of a bare
`VelocityTracker`:

- coalesced moves contribute their `PointerInputChange.historical` samples
  first, exactly like the foundation drag detectors, so a flick that arrives as
  one batched event still has real movement history;
- Compose 1.7.4's default Lsq2 estimator needs **three** samples — its quadratic
  fit is underdetermined with two and returns `0f` — so when the whole gesture
  produced fewer than three samples the helper returns the exact two-point
  average instead.

The measured fast-fling regression is kept as a shell test
(`fastPartialUpwardFlingFromTheBarOpensNowPlaying`): without the fallback the
release reads 0 px/s and the sheet settles back to `PEEK`.

## 8. Tests

- `:feature-library` `PlayerSheetRulesTest` (15) — drag fractions and clamps, a
  positive-viewport requirement, the bounded distance-based return commit (slow
  pull, tiny twitch, down-fling, up-flick, never `PEEK`), the swipe-up-target
  drag clamp, `backDestination` at every detent including `PEEK`, the
  full-range nearest/fling rule (including the outermost-detent case that the
  rev-4 gate caught), and a sweep asserting every release from every position
  and velocity lands exactly on a detent.
- `:feature-library` `QueuePullHandoffTest` (10) — the gesture lifecycle:
  downward only, true top, the unscrolled reveal position, never from a
  mid-list start, the per-gesture latch, **a delta after the lift is
  refused**, one release per gesture, reopen on a new down, and cancel.
- `:app` `PlayerSheetReleaseTest` (5) — the user-facing release contract,
  driving `PlayerSheetState` directly because a synthetic gesture cannot
  produce a post-lift delta: a delta after a queue release and after a
  full-range release cannot move or park the sheet, a lost release still
  settles, a lost release before the commit distance snaps back, and every
  dragged release rests on a detent.
- `:feature-library` `MiniPlayerPeekTest` (4) — `barExitProgress` at both
  detents, halfway between them, and clamped at the outer anchors.
- `:feature-library` `PlayerSheetVelocityTest` (7) — exact two-sample average of
  a fast flick, many-sample direction, stationary/no-movement releases, equal
  timestamps, the historical-sample burst of a coalesced flick, and a new down
  resetting the previous gesture. Unchanged by D-22.
- `:app` `MiniPlayerShellTest` (25) — the full-range settle through the real
  pointer pipeline: expand opens the sheet over the current route (route
  unchanged), a fast *partial* upward fling from the bar opens now-playing and
  stays there, a slow short drag still opens, downward flings on the bar and on
  now-playing stay/collapse, a fast upward fling on now-playing opens the
  queue, and swiping down reveals the route underneath. Thirteen cases cover the
  return path, the lost-release net and the back chain
  (`ui/player-queue-return.md` §6), including the D-24 regressions: one pull at
  the reveal position of a 60-row queue returns on the first attempt, a twitch
  there snaps back, and a pull after a user scroll only scrolls the list.
- `:app` `PlayerScreensRenderTest` (19) — the lyrics button dispatches the pane
  toggle, the lyrics pane renders its empty state and its lines, the cover pane
  shows neither, plus the existing transport/twin/queue cases.
- `:app` `PlayerScreensGoldenTest` (2) — `now_playing_portrait.png` was
  re-baselined deliberately: the disabled favorite button became the lyrics
  button. `queue_portrait.png` is byte-stable; the mini-player goldens are
  byte-stable (the bar's resting geometry at `PEEK` is unchanged).
- Every new drag test uses `down` + N × `moveBy`, never `swipe()` (the 4e
  lesson). Gate: `./gradlew :app:assembleDebug test lint` green; banned-name
  scan over every touched file.

## 9. Device-gated / open [UNKNOWN]

- **The device matrix ran on the RMX3085 and missed this defect.** It was green
  for the drag, collapse, queue-return and back-chain scripts — including the
  reveal-position case — but the sheet still parked on roughly four of five
  real-finger pulls from the true queue top. **Synthetic `adb shell input
  draganddrop` is not a proxy:** injection produces a clean, single-gesture
  stream and cannot reproduce a nested-scroll delta dispatched after the lift,
  which is exactly the park mechanism (§3, D-25). The matrix result therefore
  proved the happy path and nothing about post-lift ownership; only the user's
  hand on the device could show it.
- **D-25 verified on the RMX3085:** after the rewrite and a fresh debug
  install, the user pulled down from the true queue top ~5 times and the sheet
  returned to now-playing every time; the long-pull/`PEEK`, twitch, reveal-
  position, mini-bar expand, now-playing collapse and back-chain checks also
  passed on that build. The moto g32 received the same build but this rewrite
  was not re-confirmed there — **[UNKNOWN]** for that device.
- The bar swap distance and the general drag feel are still only
  Robolectric-plus-one-device evidence; the moto g32 feel is [UNKNOWN].
- The D-24 latch revision was confirmed on the RMX3085 in the same run
  (`decisions.md` D-24).
- Predictive-back animation on the custom overlay stays out of scope (D7).
- Swipe-up target configuration UI and the player-surface block model are
  Phase 9.
- Shared-element cover morph (mini-player thumbnail → now-playing art) remains
  a stretch goal (`ui/player-ux-fixes.md` §5).
