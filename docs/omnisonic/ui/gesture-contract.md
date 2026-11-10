# UI gesture contract — `ui/gesture-contract.md`

Purpose: document the SwipeZone ownership contract, the tab+pager pairing, the edge-exclusion rule and the button-twin audit as shipped code — the infrastructure every later screen task (mini-player 6a, now-playing 6, row actions Phase 9) consumes instead of re-deriving.

How derived: own design for Phase 5, specified in `reference/docs/GESTURES.md` §4 (design), `reference/docs/UI_SPEC.md` §1-2 (axis ownership, edge exclusion, button twins) and §5 (test contract); split out of shell task 2 on 2026-11-07. The studied app has no tab pager and no gesture-ownership abstraction (`reference/docs/GESTURES.md` §1 note); the pairing and the contract are Omnisonic-original.

Confidence: `[VERIFIED]` against `style/src/main/java/app/omnisonic/style/SwipeZone.kt`, `ButtonTwins.kt`, their tests (`SwipeZoneRulesTest` 22 incl. the five `edgeExclusionClaims` cases, `SwipeZoneRegistryTest` 3), the shell wiring tests (`MainTabsPagerTest` 7, `ButtonTwinAuditTest` 3, `:app`) and the 4d regression `LibraryScrollGestureTest` 3 (vertical drags through the real dispatch pipeline on the hub and a section grid + single-append footer). Gesture *feel* (spring parameters, fling thresholds on hardware) is device-gated `[UNKNOWN]`.

Date: 2026-11-08

Links: `ui/style-tokens.md` (`LayoutTokens.edgeExclusionDp`, `TabSwipeMode`, `MotionTokens.nowPlayingExpand`); `decisions.md` (D-13, D-14); `reference/docs/GESTURES.md` §4-5; `reference/docs/UI_SPEC.md` §1-2, §5; `.agents/plans/phase-5-tasks.md` task 2a.

---

## SwipeZone ownership rules (`:style`, `SwipeZone.kt`)

One contract answers, for any drag in progress, "does a zone own this gesture, or does the parent pager/sheet?" — implemented once in `SwipeZoneRules` as pure logic over `(dx, dy, touchSlop)` tuples:

1. **Orthogonal-axis rule** — a zone claims only after touch slop is crossed (`max(|dx|, |dy|) ≥ slop`) and only when its axis strictly dominates (`|dx| > |dy|` for horizontal zones, mirrored for vertical). A diagonal tie (`|dx| == |dy|`) claims for nobody: the parent keeps the gesture.
2. **Drag-handle precedence** — among claimants, a `DRAG_HANDLE` zone beats `CONTENT` zones; a lone handle also wins over ambiguous content pairs. Two claimants of the same role resolve to `Parent` (same-axis content conflicts are forbidden by construction — UI never nests same-axis scroll/pager containers; if built anyway the answer is the deterministic parent hand-off, not an arbitrary pick).
3. **Velocity settle** — `velocitySettle(velocity, fraction, threshold)`: a fling at or above the threshold settles by velocity direction; below it, by which side of 0.5 the gesture already passed. The mini-player expand/collapse (task 6a) consumes this with the `MotionTokens.nowPlayingExpand` spring.
4. **No claim → parent** — the asking container (pager, sheet) proceeds.

`SwipeZoneRegistry` (hoisted via `rememberSwipeZoneRegistry()`) holds the zones registered by composed surfaces; `Modifier.swipeZone(registry, axis, role)` registers/unregisters on attach/detach (Modifier.Node). Registration replaces by key; there is no cross-axis or spatial reasoning — zones are whole-surface declarations in Phase 5.

## Edge exclusion (`Modifier.pagerEdgeExclusion`)

The system back gesture owns the strip within `LayoutTokens.edgeExclusionDp` (default 24dp) of the left/right screen edge (UI_SPEC.md §1.2; predictive back, targetSdk 36). `pagerEdgeExclusion(edgeExclusionPx, touchSlopPx)` sits on the pager and consumes horizontal drags whose *start* falls inside the strip — decision made once touch slop is crossed, in the initial pass so it preempts the pager's own drag detection. Vertical drags are exempt by definition (they are not horizontal drags). Rule source: `SwipeZoneRules.edgeExclusionClaims` (pure, tested both sides of the 24dp boundary; `pagerClaimsHorizontalDrag` remains only as the pager's own claim rule — see the bug record below for why the consumption rule moved off it).

**[BUG RECORD — found 2026-11-10, fixed as task 4d]** The shipped modifier violated this contract: it computed `excluded = !pagerClaimsHorizontalDrag(...)` — and since the pure rule returns false for every vertical drag, `excluded` was true for **all vertical drags anywhere on screen**, consuming them on the Initial pass (the pass that preempts children). Every list/grid scrollable under the pager therefore cancelled vertical drags after touch-slop, while taps (no slop crossing) and established flings (animation-driven, no pointer events) survived — the device symptom was "scroll initiation freezes, try again and again, flings work, taps navigate instantly." Survived three tasks because `MainTabsPagerTest` only injects horizontal swipes and the pure-rule tests never exercised the modifier's consumption. **Corrected semantics (binding, shipped 2026-11-10):** the Initial-pass consumer claims ONLY drags that are horizontal-dominant AND start inside the token strips — implemented as the pure `SwipeZoneRules.edgeExclusionClaims(...)` sibling; vertical drags and mid-screen drags are never consumed by it. Regression tests: five pure cases both sides of the strip boundary, plus `LibraryScrollGestureTest` driving a real `performTouchInput` vertical drag on the hub and a section grid under the NavHost overlay (asserting the scroll offset moves); the footer single-append check lives in the same class.

## Tab + pager pairing (`:app`, `MainTabsPager` + `OmnisonicShell`)

- One `PagerState` hoisted in `OmnisonicShell` is the single source of truth for the selected main tab; page count = the persisted visible-tab list. Tab taps (`animateScrollToPage`) and swipes both drive it; the bottom bar / rail read `pagerState.currentPage`, so the selection tracks a swipe at the halfway point.
- `TabSwipeMode` gates `userScrollEnabled` per orientation; taps still animate the pager when swipe is disabled (tabs-only mode).
- The pager and the route table stay coherent in both directions: a settled swipe navigates the tab's route (so the back stack, deep links and the `MainTab.id == route` identity keep working), and route arrivals (deep link, back pop) snap the idle pager. A guard redirects to the first visible tab if the current tab is hidden in settings.
- Tab routes remain registered in the NavHost (§5 route table conformance) but render an empty slot: the pager draws the tab screens above the NavHost. The two layers swap z-order instead of composition — while a non-tab destination (detail, provider setup) is on top it draws an opaque background over the pager and absorbs every gesture its own content did not consume, so nothing bleeds through to the hidden tab pages.
- Pairing is Omnisonic-original (no studied counterpart); the single-page gesture style (GESTURES.md §4.3) composes from these tokens plus the mini-player drag surface (task 6a).

## Button twins (`:style`, `ButtonTwins.kt`)

Every gesture action must also exist as a visible control and a custom accessibility action on the same screen (UI_SPEC.md §1.3 — nothing is swipe-only).

- `GestureTwin(gestureId, actionLabel, twinControlLabel)` declares the pair; `Modifier.gestureTwins(twins, perform)` installs the custom accessibility actions on the gesture surface.
- The shell registers the tab-swipe twins: each pager carries "Go to \<tab\>" actions and the tab bar items are the visible controls.
- `ButtonTwinAudit.assertTwinsPresent(root, twins)` is the reusable audit every screen task calls in its compose tests: it walks the semantics tree and fails listing any gesture whose custom action is missing or whose visible clickable control (label match on text/contentDescription, `OnClick` present, not hidden from accessibility) is absent. Self-tested both directions in `ButtonTwinAuditTest`.
