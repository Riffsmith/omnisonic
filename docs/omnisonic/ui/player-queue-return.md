# Queue ⇄ now-playing: return gesture, back chain, current-row orientation — `ui/player-queue-return.md`

Purpose: root-cause and fix brief for the three queue/now-playing defects the
user reported on **2026-11-15** — (1) a downward drag on the queue never
returns to now-playing, (2) system back on the queue closes the whole sheet
instead of landing on now-playing, (3) a long queue opens at the top and has no
control to jump back to the playing track.

Scope: the player sheet (`ui/player-sheet.md`) and the queue surface only. This
is an out-of-band fix, **not** a Phase 5 task; `.agents/plans/phase-5-tasks.md`
and the Phase 5 task states are unchanged.

How derived: user report of 2026-11-15 and the same-day code investigation of
`:app`, `:feature-library` and `:style` (defect table below); industry pattern
research for the design targets (cited inline per claim — behaviour borrowed is
generic industry convention, no studied-app input). The eight open questions
were put to the user and answered on 2026-11-15 (§3).

Confidence: root causes `[VERIFIED]` against the cited file:line locations;
behaviour spec and fix designs `[INFERRED]` (reviewed against the cited
patterns and the binding rules of `ui/gesture-contract.md`); touch feel and
diagonal-start interaction with the row swipe-dismiss and the
standalone-route pill are device-gated `[UNKNOWN]` (§8). The release velocity
of a slow pull was measured on device on 2026-11-16 (§4.1, §8). One design
choice was **not** explicitly confirmed by the user and is recorded as an
assumption in §3.

Date: 2026-11-15. Shipped same day; revised 2026-11-16 with the D-21 slow-pull
return fix, then again 2026-11-16 with the D-24 latch revision (the queue opens
at the reveal position, so "very top" alone left the gesture dead on open), and
again the same day with the D-25 release-final rewrite of the sheet state and
the handoff's closed gesture, and again 2026-11-16 to confirm the D-24 latch is
unchanged by the queue row's new custom swipe (`ui/queue-swipe.md`, D-26).

Links: `ui/player-sheet.md` (sheet geometry, detents, the sheet-owned state
machine, overlay wiring), `ui/player-screens.md` (queue layout, lyrics pane),
`ui/gesture-contract.md` (the sanctioned scrollable-child → ancestor handoff),
`ui/queue-swipe.md` (the row's horizontal remove gesture),
`decisions.md` (D-20, D-21, D-22, D-24, D-25, D-26), `ui/gesture-contract.md`
task 4d/4e bug records (pointer-pass rules).

---

## 1. The defects

| # | Symptom (user report 2026-11-15) | Root cause | Location |
|---|---|---|---|
| 1 | Swiping up from now-playing reveals the queue, but a downward drag on the queue never returns to now-playing | The queue had no path to the sheet at all: `QueueRoute` took only `viewModel`/`onBack`/`onOpenLibrary`, the host passed `sheetDrag` to `NowPlayingRoute` and `MiniPlayerBar` only, and the only drag/release call sites live inside the `PlayerSheetDrag` the shell builds. The queue layer is an inert translated sibling, and the queue `LazyColumn` owns every vertical drag | `QueueScreen.kt:81` (signature); `OmnisonicApp.kt:233-246` (drag construction, no queue consumer); `PlayerSheetHost.kt:149-173` (inert layer) |
| 2 | System back on the queue closes the whole sheet and skips now-playing; expected Queue → Now Playing → collapsed → previous route | The back handler was detent-blind — it always called `close()`. `swipeUpTargetRevealed` already existed but was read only by a test | `OmnisonicApp.kt:246`; `PlayerSheetHost.kt:47-49` |
| 3 | A long queue opens at the top instead of at the current track, with no way to jump back | No scroll state at all: the `LazyColumn` had no `state`, and `:feature-library` contained no `rememberLazyListState`/`scrollToItem` call. The data needed (`currentOrderIndex`) already existed | `QueueScreen.kt` (list construction, pre-fix); `QueueUiState.kt:20,27` |

Supporting facts `[VERIFIED]`:

- Geometry and detents are unchanged since D-20: `position ∈ [-1, 0, 1]` = queue
  / now-playing / peek, now-playing drawn at `position * viewport`, the swipe-up
  target at `(position + 1) * viewport` (`PlayerSheetRules.kt:12-19`,
  `PlayerSheetHost.kt`). The return is the exact inverse of the upward reveal,
  so no overlay or detent change is needed. D-22 briefly moved the underlying
  position to an `AnchoredDraggableState`; D-25 replaced that with the
  sheet-owned state machine and kept these values, the layer geometry and this
  bounded rule (`ui/player-sheet.md` §3).
- **Composition timing trap:** the overlay composes **both** layers whenever the
  sheet is visible, so the queue list is alive at `position == 0` underneath
  now-playing. A naive `LaunchedEffect(currentOrderIndex)` would scroll while
  invisible and again on every track change.
- **Gesture ownership trap:** the overlay is an ancestor of the list, and an
  ancestor must not consume on the Main pass. Copying now-playing's
  `playerSheetDrag` (`NowPlayingScreen.kt:517-573`) onto the queue container is
  exactly the task-4e bug class; that detector is only safe because the
  now-playing surface has no scrollable child.
- The full-range settle has no upper bound (D-22 replaced it with the
  framework's decay-against-anchors settle but kept the same reach): a pull
  released near `0` with downward velocity would settle to `PEEK`, so the
  return gesture would collapse the sheet instead of stopping at the player.
  That is why the queue path has its own bounded rule
  (`dragPositionFromSwipeUpTarget` / `pullTarget`).
- The standalone `ShellRoutes.QUEUE` destination has no sheet host
  (`OmnisonicApp.kt:674`), so every new parameter has to be optional.
- Row swipe-remove is horizontal only, so a vertical handoff does not collide
  with it (`QueueScreen.kt`, the row's own horizontal detector —
  `ui/queue-swipe.md`). The D-24 latch code is untouched by that gesture.

Verdict: defect 1 was a **design gap** — the shipped sheet work (D-20) never
claimed a return path; defect 2 was an omission in the back handler, not a
routing bug; defect 3 was unimplemented.

## 2. Research summary

| App / source | Pattern | What it does | Takeaway |
|---|---|---|---|
| Spotify | Play queue structure | Current track in a pinned "Now playing" section, then the upcoming list ([community.spotify.com](https://community.spotify.com/forum/en/kb/articles/807-how-does-the-play-queue-work)) | Anchoring the current item beats hunting |
| Spotify | Jump to current | A source chip at the top of now-playing jumps to the playing song; back returns to the previous scroll position ([community.spotify.com](https://community.spotify.com/t5/Other-Podcasts-Partners-etc/Jump-to-current-song-when-you-open-playlists/td-p/5926710)) | The jump affordance is an explicit control, not a gesture |
| Spotify | Dismiss convention | Android users request swipe-down-dismiss parity with iOS ([community.spotify.com](https://community.spotify.com/forum/en/discussion/5648062/android-swipe-down-current-playing-song-view-instead-of-tapping-the-arrow)) | Downward dismissal is expected muscle memory |
| YouTube Music | Up Next panel | A handle inside now-playing opens a partial pull (~4 upcoming) and a second swipe the full list ([9to5google.com](https://9to5google.com/2025/09/11/youtube-now-playing-2025-redesign), [androidpolice.com](https://www.androidpolice.com/youtube-music-now-playing-redesign-android)) | Two-stage drag is normal; every drag needs a tap twin |
| YouTube Music | Gesture-only regression | Swipe-down on the mini player was removed in 2023 and users rebelled until a visible "Dismiss queue" button shipped ([9to5google.com](https://9to5google.com/2023/07/13/youtube-music-dismiss-queue)) | Gesture-only affordances regress (UI_SPEC §1.3) |
| Apple Music | Playing Next | The current track sits at the list boundary with history above; the same button hides the panel ([support.apple.com](https://support.apple.com/guide/iphone/queue-up-your-music-ipha4521ef7d/ios)) | Current item anchored at the boundary; a toggle button is the twin |
| Plexamp | Auto-position | A regression report about the playlist no longer scrolling to the current track, on a screen with floating scroll buttons ([forums.plex.tv](https://forums.plex.tv/t/plexamp-playlist-doesnt-scroll-to-current-track-anymore/908329)) | Auto-position on open is expected; jump is a persistent button |
| Audirvana Remote | "Near the top" | Praise that the queue "finds the currently playing song and scrolls down and shows it right near the top" ([community.audirvana.com](https://community.audirvana.com/t/remote-for-android-feature-request/35663)) | Specifically *near the top*, not centered |
| AIMP | Follow cursor | A "follow cursor / automatic scroll" setting exists and users distinguish it from a manual jump ([aimp.ru](https://aimp.ru/forum/index.php?topic=64359.0)) | Auto-follow is a configurable want, not a universal default |
| Material 3 | Sheet anatomy | The at-full-height collapse control returns to the initial position ([m3.material.io](https://m3.material.io/components/bottom-sheets/guidelines)) | An explicit collapse control at full height is the M3 model — the back arrow already is one |
| Android platform | Nested-scroll handoff | `setDraggableOnNestedScroll` — a bottom sheet collapses/expands by dragging on the nested scrolling child ([developer.android.com](https://developer.android.com/reference/com/google/android/material/bottomsheet/BottomSheetBehavior)) | Platform precedent for exactly this handoff |
| Predictive back | Custom sheets | Predictive-back animation is supported for `ModalBottomSheet`/`SearchBar`; a custom sheet has to supply its own callback and progress ([developer.android.com](https://developer.android.com/codelabs/predictive-back)) | The custom overlay gets nothing for free |

## 3. Decisions (user answers, 2026-11-15)

| # | Question | Decision |
|---|---|---|
| D1 | Where the current row lands on open | **Near the top, one row of history above** (the current row becomes the second visible row). The move is **instant**, not animated — the sheet itself is sliding. |
| D2 | Re-open semantics | **Reset on every open**; do **not** follow track advances while the queue is open. The jump control reflects the new state instead. |
| D3 | Jump control | **Floating pill**, bottom-end over the list: cover thumb + equalizer icon + one-line title, content description `Jump to <title>`. Visible only while the current row is not fully visible. |
| D4 | Down-swipe scope | **Only when the list was at its very start at gesture start.** A gesture that began mid-list never hands off, even if it reaches the top later. **Revised 2026-11-16 (D-24):** "start" means the true list top **or** the unscrolled reveal position the queue was positioned to on open — see §4.1. The mid-list rule is unchanged. |
| D5 | Feel | **Live finger-following**, identical to now-playing ⇄ collapse; the pull is consumed so the top stretch/glow never appears. Forwarding stops at `NOW_PLAYING`. |
| D6 | Grabber | **None.** A handle implies drag-from-anywhere while the gesture is top-only; the back arrow is the visible twin. |
| D7 | Back chain | **Queue → now-playing → collapsed → route**, for system back and the arrow. Predictive-back animation stays out of scope. |
| D8 | Edge cases | Empty queue → down-swipe still returns (the empty state is the drag surface). Shorter than the viewport → always returns. Nothing current → no auto-scroll, no jump control. |

**Assumption (not explicitly confirmed):** on the standalone `ShellRoutes.QUEUE`
route (no sheet host) the jump pill stays and the gesture does not exist
(`sheetDrag = null`). It is a list affordance, not a sheet feature — see §8.

## 4. Behaviour spec

### 4.1 Return gesture

- One `Modifier.nestedScroll(connection)` on the queue `LazyColumn` and one
  **passive, never-consuming** pointer observer on the same element
  (`QueueScreen.kt:272-316`). The observer latches the gesture-start
  eligibility at the down and reports the release velocity; it consumes
  nothing, so the list's own drag detection is untouched — the sanctioned form
  of the handoff described in `ui/gesture-contract.md`.
- Eligibility (`QueuePullHandoff.onDown`, `player/QueuePullHandoff.kt`):
  the true list top (`firstVisibleItemIndex == 0 &&
  firstVisibleItemScrollOffset == 0`), **or** the unscrolled reveal position
  (`positionedForReveal && !userScrolledSinceReveal`, computed in
  `QueueScreen` from the positioning edge of §4.3). It is latched once per
  gesture — reaching an eligible position mid-gesture does not enrol.

  **The gesture is closed at the lift (D-25).** `shouldHandOff` requires
  `isOpen`, `onUp` closes the gesture and answers once whether the sheet owns
  the release, and `onCancel` closes it when the pointer stream dies. A delta
  that arrives after the lift is refused by the handoff itself, and the sheet
  refuses it a second time because its own gesture is closed — the two layers
  agree that nothing after the finger is gone may move the sheet.

  **Why the reveal position enrols (D-24, revision of D4).** The queue opens
  with the current row second (D1), so `firstVisibleItemIndex` is
  `currentOrderIndex - 1` whenever the current track is not first and the queue
  is longer than the viewport. A true-top-only latch therefore never enrolled on
  open for almost every real queue, and a deliberate pull just scrolled the
  list — the multi-pull symptom the user reported on the second test device
  (mechanism (a) of the implementation plan §2.2, `[VERIFIED]` on the RMX3085:
  the queue opened at `currentOrderIndex - 1` and a 400 px pull scrolled ~3
  rows). The first user scroll after the reveal (`onPreScroll` with a
  non-forwarded `UserInput` delta) clears the flag, so eligibility reverts to
  true-top-only and mid-list gestures never hand off. The reveal position is
  the *positioned* value, not the current track's row: a track advance while
  the queue is open does not re-enrol (D2).
- While enrolled, a `UserInput` pre-scroll delta with `y > 0` is consumed,
  forwarded to the sheet, and returned as consumed, so the list stays pinned at
  row 0 and Material receives a zero delta (no stretch/glow) `[INFERRED]` from
  the framework's pre-scroll → consume → overscroll ordering.
- Forwarding stops at `NOW_PLAYING`: the sheet-side drag is bounded to
  `[-1, 0]` (`PlayerSheetRules.dragPositionFromSwipeUpTarget`), so the rest of
  that gesture is swallowed — the list does not scroll and the sheet does not
  collapse.
- Release: the observer reports the velocity through the existing
  `PlayerSheetVelocity` helper (historical samples + two-sample fallback) and
  the host settles with `PlayerSheetRules.pullTarget` through the sheet spring.
  A
  held-then-lifted pull reports almost no velocity — measured on device
  2026-11-16: −3.7 px/s after 400 px of travel, well under the 139 px/s fling
  threshold — so the nearest-detent rule needed half the viewport (≈1114 px)
  and made one slow pull snap back. Distance decides instead: past
  `LayoutTokens.sheetPullCommitDistance` (32 dp, ≈89 px / 4% of the measured
  2228 px viewport) the pull commits to `NOW_PLAYING`; under it (touch-slop
  drift, an accidental tap) it snaps back. 32 dp sits above the ~8 dp touch
  slop and far below a deliberate thumb pull, so one attempt is enough. A
  fling still wins over distance, in its own direction: a downward fling
  commits from any distance, an upward flick cancels. The target is always
  `SWIPE_UP_TARGET` or `NOW_PLAYING`, never `PEEK`.
  `onPreFling` is deliberately not used: a scrollable that consumed no deltas
  may never fling, which would leave the sheet stuck half-open.
- A lost release cannot park the sheet: the shell root carries a passive
  all-pointers-up observer (`OmnisonicApp` → `PlayerSheetState.onAllPointersUp`)
  that settles the in-flight drag with zero velocity through the same rule that
  started it (the bounded return commit for the queue, the nearest detent for
  now-playing). It never consumes, and it runs after the dragging surface on
  the Main pass, so a normal release closes the gesture first and the net is a
  no-op. This covers a stream that ends without the surface seeing the up
  (system cancellation, focus loss, the last of several pointers lifting).
- Not eligible at gesture start: nothing is forwarded and the list behaves
  exactly as before (including the normal overscroll). The first such
  non-forwarded user delta is also what clears the reveal-position flag
  (§4.1).
- Empty queue: the empty state is a leaf, so it carries its own vertical drag
  surface (`QueueScreen.kt:318-362`) with the same bounds.

### 4.2 Back chain

- Pure rule `PlayerSheetRules.backDestination(position)`: below
  `NOW_PLAYING - ε` → `NOW_PLAYING`, otherwise → `PEEK`.
- `PlayerSheetState.onBack()` applies it (`PlayerSheetHost.kt:101`) and
  `BackHandler(enabled = sheet.isVisible) { sheet.onBack() }` replaces the old
  unconditional `close()` (`OmnisonicApp.kt:246`). The in-screen arrow keeps
  calling `openNowPlaying()`; both paths now agree.

### 4.3 Orientation and the jump pill

- The shell derives `swipeUpTargetRevealed` with `derivedStateOf`
  (`OmnisonicApp.kt:243-245`) and passes it as `isRevealed` into the queue
  slot, so the queue recomposes on the crossing rather than on every drag
  frame. The parameter defaults to `true`, which is the standalone-route,
  render-test and golden behaviour ("open").
- On the false→true edge the list jumps **instantly** to
  `currentOrderIndex - 1` (`scrollToItem`, not animated) so the current row is
  the second visible row — while now-playing still covers the screen, hence
  invisible. A `positionedForReveal` latch allows one positioning per reveal,
  tolerates rows arriving after the edge, ignores later track advances, and
  resets when `isRevealed` goes false (so every open re-orients)
  (`QueueScreen.kt:139-152`).
- No current entry → no positioning and no pill.
- Pill (`QueueScreen.kt:364`): bottom-end over the list, `shapes.pill` corners,
  cover thumb + one-line ellipsized title + primary equalizer icon, plain
  `clickable(role = Button)` with content description `queue_jump_to`
  ("Jump to %1$s", `strings.xml:69`). Visibility comes from `derivedStateOf`
  over `layoutInfo.visibleItemsInfo`: shown when a current index exists and no
  visible item with that index is fully inside the viewport
  (`QueueScreen.kt:154-165`). Tap → `animateScrollToItem` (animated here,
  because the user initiated it).
- The list carries one extra line of bottom `contentPadding` so the pill can
  never permanently cover the last row.
- It is a plain button, so it needs no gesture twin; the down-swipe's twin is
  the existing back arrow.

### 4.4 Non-regressions

The row's right-to-left remove (now a threshold-commit custom row —
`ui/queue-swipe.md`) and its overflow twin, per-row move up/down,
"Clear remaining", the empty-state copy and button, `ShellRoutes.QUEUE` as a
real destination, the swipe-up target slot staying configurable, the
mini-player and now-playing drag paths, and the sheet's detent geometry are all
unchanged.

## 5. Options rejected

| Rejected | Why |
|---|---|
| Detector on the queue container (copy `playerSheetDrag`) | Consumes on the Main pass as an ancestor of a scrollable — the exact 4e bug class |
| Hand off mid-gesture once the list reaches the top | A fast fling to the top would carry into an accidental return to the player |
| Header as a drag surface at any scroll position | Two meanings for one down-drag on one screen; weakens the header's tap stability |
| `onPreFling` for the release velocity | May never fire when the scrollable saw no deltas → a stuck half-open sheet |
| Changing the full-range settle itself to stop at now-playing | The same settle drives the now-playing and mini-player drags, which must still reach `PEEK`; a bounded variant keeps both behaviours |
| Reusing the bounded nearest-detent rule for the return pull | A slow pull releases near zero velocity, so it needed half the viewport of travel and snapped back (D-21 bug); distance past a small token commits instead |
| Keeping the true-top-only latch (D4 as first written) | The queue opens at the reveal position, so the gesture was dead on open for any queue where the current track is not first — the D-24 device report |
| Re-enrolling whenever the list shows the current row | A track advance or a jump-to-current tap would silently re-arm a handoff the user did not ask for; only the reveal edge arms it |
| Half-viewport / velocity-only commit with no distance floor | A tiny twitch with any drift would commit; 32 dp keeps accidental taps snapping back |
| Centered / minimal-scroll open position | Centering spends half the screen on history; minimal scroll can land the row at the bottom edge |
| Auto-following track advances while open | Moves the list under the reader; the pill carries the new state |
| Header icon or bare FAB for jump-to-current | No track context; the pill also answers "where am I" |
| Grabber above the header | Implies drag-from-anywhere while the gesture is top-only |
| Predictive back now | A custom overlay needs its own callback and progress plumbing; separable follow-up |

## 6. Tests

- `:feature-library` `PlayerSheetRulesTest` (15) — the drag/clamp cases plus
  `backDestination` at every detent, the bounded drag clamp at now-playing, the
  D-21/D-24 return rules (slow pull past the commit distance returns, tiny
  twitch snaps back, down-fling commits from the top, up-flick cancels a long
  pull, a release never reaches `PEEK`, non-positive commit distance rejected),
  the D-25 full-range nearest/fling rule, and a sweep asserting every release
  from every position/velocity is a detent.
- `:feature-library` `QueuePullHandoffTest` (10) — downward forwarding, upward
  deltas belonging to the list, the true top, the unscrolled reveal position, a
  mid-list gesture never enrolling, the per-gesture latch, **a delta after the
  lift being refused**, one release per gesture, reopen on a new down, and
  cancel.
- `:app` `PlayerSheetReleaseTest` (5) — the anchor-after-release suite: a
  post-release delta cannot move or park the sheet (queue pull and full-range
  drag), a lost release still settles, a lost release before the commit
  distance snaps back, and every dragged release rests on a detent. These drive
  `PlayerSheetState` directly because the failure has no event to carry it.
- `:app` `MiniPlayerShellTest` (25) — 13 cases through the real pointer
  pipeline: `swipingDownAtTheQueueTopReturnsToNowPlaying` (short queue),
  `aSlowShortPullFromTheQueueTopReturnsToNowPlaying` (160 px over 4 s → one
  pull returns; fails on the pre-fix half-viewport rule),
  `aPullAtTheRevealPositionOfALongQueueReturnsOnTheFirstAttempt` and
  `aTinySlowTwitchAtTheRevealPositionSnapsBack` (D-24; the long queue opens
  with the current row second, so the first fails with a true-top-only latch),
  `aTinySlowTwitchAtTheQueueTopSnapsBack`,
  `aPullWhoseReleaseNeverArrivesStillSettles` and
  `aLostReleaseBeforeTheCommitDistanceSnapsBack` (the overlay safety net),
  `aCompletedPullNeverCollapsesTheSheet`, `swipingDownOnAScrolledQueueLeavesTheSheetAlone`
  (one user scroll clears the reveal eligibility; the list scrolls and the
  sheet stays at `-1`), `emptyQueueSwipesDownToNowPlaying`,
  `systemBackOnTheQueueGoesToNowPlayingThenCollapses` (through the real
  `OnBackPressedDispatcher`; the route underneath is unchanged),
  `queueOpensAtTheCurrentRow` (60-row queue) and `jumpToCurrentAfterScrolling`
  (scroll away → labelled pill → tap → row visible and pill gone). Every new
  drag uses slow per-event deltas (`down` + N × `moveBy`), not `swipe()` — the
  4e lesson.
- `:app` `PlayerScreensRenderTest` (23) — the queue cases plus the lyrics-pane
  cases (`ui/player-screens.md`): the pill appears with the current row
  off-screen, no pill when every row fits, and the `sheetDrag = null` path opens
  at the current row. The four swipe cases (reveal/follow, commit, spring-back,
  vertical scroll) are `ui/queue-swipe.md` §8.
- `:app` `PlayerScreensGoldenTest` (2) — `now_playing_portrait.png` and
  `queue_portrait.png`; both byte-stable across the sheet work. Goldens are
  written only by `:app:recordRoborazziDebug` and compared only by
  `:app:verifyRoborazziDebug` — plain `test` does neither (R-25, reconfirmed
  2026-11-16).
- Gate: `./gradlew :app:assembleDebug test lint` green; banned-name scan over
  every touched file.

## 7. Where the code lives

- `:feature-library` `player/QueuePullHandoff.kt` — gesture lifecycle
  (eligibility latch, closed gesture, one release), Compose-free and
  unit-tested. Replaces the deleted `QueueSheetPull.kt`.
- `:feature-library` `player/PlayerSheetRules.kt` — `backDestination`,
  `pullTarget`, `settleTarget`, `dragPositionFromSwipeUpTarget`,
  `dragPosition`. Both release rules are total over the detents (D-25).
- `:style` `LayoutTokens.sheetPullCommitDistance` (32 dp) — the distance that
  commits a slow return pull (D-21), read as px in `:app`.
- `:feature-library` `ui/QueueScreen.kt` — `sheetDrag` / `isRevealed`
  parameters, the handoff modifiers, the reveal-position latch
  (`positionedForReveal` + `userScrolledSinceReveal`) and the pill.
- `:feature-library` `res/values/strings.xml` — `queue_jump_to`.
- `:app` `shell/PlayerSheetHost.kt` — the sheet-owned `PlayerSheetState`
  (gesture epoch, `onPointerDown`, `onAllPointersUp`, `release*`, `settleTo`),
  `onBack()`, `dragFromSwipeUpTarget`, `releaseFromSwipeUpTarget`.
- `:app` `shell/OmnisonicApp.kt` — the bounded drag instance for the slot, the
  one fling gate, the derived reveal flag, the shell-root pointer observer and
  the detent-aware `BackHandler`.

## 8. Device-gated / unconfirmed `[UNKNOWN]`

- The feel of a drag crossing from the list into nested scroll (timing of the
  handoff after touch slop) — Robolectric proves the settle target and the
  latch, not the feel. The slow-pull release velocity is measured
  (2026-11-16, `[VERIFIED]`): 400 px over ~900 ms releases at −3.7 px/s against
  a 139 px/s fling threshold, which is why the return commit is distance-based
  (§4.1).
- **Device result (2026-11-16, RMX3085) [VERIFIED by the user's hand]:** the
  D-24 latch and the D-25 release rewrite both hold. From the true queue top,
  ~5 deliberate real-finger pulls returned to now-playing every time; the
  long-pull-never-`PEEK`, tiny-twitch, long-queue reveal-position,
  mini-bar-expand, now-playing-collapse and back-chain checks also passed on
  the same debug build. The moto g32 received the same build but was not
  re-confirmed for this rewrite — **[UNKNOWN]** there.
- **Injection trap, keep this in mind before declaring a gesture fixed:** the
  earlier device matrix was green ~15/15 with `adb shell input draganddrop`
  while a real finger failed ~4/5 on the same build. Injection emits one clean
  stream and **cannot produce a nested-scroll delta after the lift**, which was
  the exact park mechanism (D-25). Synthetic drags prove the settle target and
  the eligibility latch; they say nothing about post-lift ownership. Verify
  gesture defects with a real finger.
- Diagonal drag starts interacting with the row's custom horizontal swipe
  detector (`ui/queue-swipe.md` §7).
- Whether suppressing the top overscroll (no stretch/glow) reads as a frozen
  list rather than as the sheet taking over.
- Pill visibility derived from `layoutInfo` on a very long queue (~1000 rows) —
  no jank measured yet.
- **Standalone route (the one unconfirmed decision):** the pill is present and
  the sheet gesture does not exist there (§3 assumption). Confirmed for the
  empty, short-queue and no-current cases only.
- Predictive back on the custom overlay remains out of scope (D7).
