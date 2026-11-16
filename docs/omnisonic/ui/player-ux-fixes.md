# Player UX fixes: mini-player + now-playing — `ui/player-ux-fixes.md`

Purpose: root-cause and fix brief for the six player-surface UX defects reported
in `.agents/tmp/features-ui-ux-lacks.md` (Mini-player + Now Playing sections,
2026-11-12), registered as Phase 5 tasks **7f** (mini-player) and **7g**
(now-playing). The Queue Screen and Album Screen sections of that report are out
of scope here.

How derived: user device report (2026-11-12, the tmp features file); same-day
code investigation of `:feature-library`, `:app` and `:style`; industry pattern
research for the design targets (Material 3 motion patterns and cross-app
conventions — cited inline per claim; behavior borrowed is generic industry
convention, no studied-app input).

Confidence: root causes `[VERIFIED]` against the cited file:line locations;
fix designs `[INFERRED]` (reviewed against the cited industry patterns);
animation feel, contrast on hardware and predictive-back behavior are
device-gated `[UNKNOWN]` until a device session.

Date: 2026-11-12

Links: `ui/player-screens.md` (behavioral reference for both screens);
`ui/gesture-contract.md` (SwipeZone ownership, button twins, pointer-pass
rules); `ui/style-tokens.md` (MotionTokens, surface styles, progress colors);
`decisions.md` (D-19); `.agents/tmp/features-ui-ux-lacks.md` (source report);
`.agents/plans/phase-5-tasks.md` tasks 7f/7g; `reference/docs/UI_SPEC.md`
§3.8 (now-playing), §3.10 (mini-player).

**Superseded motion design (2026-11-16, D-22):** §3-§5 below are the design
record of tasks 7f/7g and are kept intact as the defect history. The shipped
motion no longer matches them where the player sheet is involved: the
finger-following shell sheet replaced the route-transition expand, the full-range
settle is now the sheet-owned detent rule on one spring (D-25; D-22's
`AnchoredDraggableState` façade was replaced), and the mini bar's self-animated
24 dp lift exists only on the
standalone/no-host path (the hosted bar is a pure function of sheet position).
`ui/player-sheet.md` is the canonical current spec. The 7f fixes themselves
(hairline token, `contentColor` contrast, the no-host lift/expand path, expand
at the settle decision) are still shipped; on the hosted path the sheet owns the
settle and the bar simply exits with it.

Queue row swipe-removal was outside this brief (the Queue Screen report section
was out of scope). It no longer uses Material's `SwipeToDismissBox`: the
threshold-commit custom row is specified in `ui/queue-swipe.md` (D-26,
2026-11-16). Any older mention of a row dismiss box in the player documents is
that history, not current behaviour.

---

## 1. Defects and root causes

| # | Symptom (user report) | Root cause | Location |
|---|---|---|---|
| 1 | Mini-player shows no progress indicator | `MiniPlayerUiState` carries only `entry` + `isPlaying`; no position/duration field | `feature-library/.../miniplayer/MiniPlayerUiState.kt:14-26` |
| 2 | Mini-player play/pause, next, expand controls barely visible | The bar's `Surface` sets `color` but never `contentColor`; `contentColorFor(surfaceContainer.copy(alpha = 0.92f))` returns `Color.Unspecified`, so the `IconButton` icons inherit the Compose default `LocalContentColor` = **black** on a dark bar | `feature-library/.../ui/MiniPlayerBar.kt:99-108` |
| 3 | Drag-up expand feels janky, almost non-existent | The drag animates only a 12 dp lift of the bar itself (`liftPx = spacing.md`, `translationY = -fraction * liftPx`), then a plain `navigate(nowPlaying)` fires with the NavHost **default fade** — no continuity between gesture and screen | `MiniPlayerBar.kt:87,158-172,186`; `app/.../shell/OmnisonicApp.kt:209` |
| 4 | Now-playing cover is not horizontally centered | The inner `Column(Modifier.weight(1f))` sets `horizontalAlignment = CenterHorizontally` but lacks `fillMaxWidth()` — it wrap-sizes, sits start-aligned in the outer column, and the 320 dp art hugs the left | `feature-library/.../ui/NowPlayingScreen.kt:154-157` |
| 5 | Swipe up (queue) / swipe down (collapse) feel trashy | Art drags are threshold-detected (32 dp dominant axis) then plain `navigate(queue)` / `popBackStack()` with default transitions; the art gives zero drag-follow feedback | `NowPlayingScreen.kt:242-262`; `OmnisonicApp.kt:561-575` |
| 6 | Swipe right-to-left = previous (should be next) | `if (dx > 0f) onNext() else onPrevious()` — drag right = next, inverted vs the industry convention | `NowPlayingScreen.kt:255-257` |

Supporting facts `[VERIFIED]`:

- Position data already flows for defect 1: `PlaybackControllerImpl`'s ticker
  publishes `PlayerCoreState.positionMs` every 500 ms
  (`playback/.../controller/PlaybackControllerImpl.kt:381-416`,
  `DEFAULT_TICK_INTERVAL_MS = 500L`), and `MiniPlayerViewModel` already
  combines `controller.playerState` — no playback-layer change is needed.
- The NavHost sets no transitions anywhere (`OmnisonicApp.kt` `ShellNavHost`),
  so navigation-compose 2.8.2's default fade runs on every player-surface
  navigation. Per-destination `enterTransition` / `popExitTransition` are
  supported by the pinned version and currently unused.
- The gesture direction in defect 6 was also recorded backwards in
  `ui/player-screens.md` (§ art gestures). `reference/docs/UI_SPEC.md` §3.8
  says only "horizontal drag = track change" without a direction, so this was
  an implementation choice, now corrected (D-19).

## 2. Industry pattern targets (design sources)

- **Expand = a takeover that slides from the bottom.** Material 3 assigns the
  container-transform pattern to card→detail expansions and states that
  bottom-anchored surfaces enter from the bottom and exit downward
  (https://m3.material.io/styles/motion/transitions/transition-patterns). A
  cross-app survey draws the same conclusion: "now playing is a full-screen
  takeover, not a page… dismissed by a downward swipe back into the mini
  player. Treating it as a normal pushed screen with a back button is the
  wrong model" (https://www.mobileappdesign.io/blog/music-app-ui).
- **Mini-player conventions** (same survey + Spotify's current design):
  artwork thumbnail, title/artist, one or two transport controls, a whole-bar
  tap/drag target, and a **thin progress hairline** docked to an
  edge of the bar (Spotify moved the hairline to the bar's bottom edge in its
  2021 redesign — https://www.androidpolice.com/2021/07/28/spotify-is-getting-a-redesigned-mini-player-ui-with-a-more-modern-look).
  The compact bar carries previous / play-pause / next (user decision
  2026-11-12): the former expand chevron was redundant with the whole-bar
  tap/drag target, so its slot now holds previous. The bar itself keeps the
  "Expand player" label and button twin.
- **Swipe-on-artwork direction is unambiguous:** right-to-left = next,
  left-to-right = previous in Spotify
  (https://www.howtogeek.com/spotify-gestures-every-subscriber-should-know/),
  Apple Music (iOS 26.1 artwork swipe —
  https://www.macworld.com/article/2918525/apple-music-finally-gets-a-swipe-gesture-to-change-tracks-and-ive-never-been-happier.html),
  YouTube Music (https://www.xda-developers.com/youtube-music-swipe-album-art-change-songs/)
  and Poweramp's player guide.
- **Gestures are never the only way to act** — already enforced by the §1.3
  button-twin rule (`ui/gesture-contract.md`); nothing to change.
- **Scrubbing seeks on release** — already correct in `SeekRow`; do not regress
  it.

## 3. Fix design — task 7f: mini-player

Scope: `:style` (+1 token), `:feature-library` (state + bar), `:app` (tests +
goldens). No playback-layer change.

1. **State extension** (`MiniPlayerUiState`): add `positionMs: Long`,
   `durationMs: Long?` and a derived `progress: Float` with the same clamp
   semantics as `NowPlayingUiState.progress` (duration null/≤0 → 0f).
   `from(player, queue)` reads `player.positionMs` and
   `player.durationMs ?: current?.durationMs` (the entry-duration fallback
   already exists in `NowPlayingUiState.from` — mirror it).
2. **Progress hairline** (`MiniPlayerBar`): a 2 dp determinate track pinned to
   the **bottom edge** of the bar, full width —
   `tokens.colors.progressTrack` background, `progressActive` fill sized by
   `progress`. The height is a new token
   `LayoutTokens.miniPlayerProgressHeight` (2 dp default) per the
   style-tokens rule that no literal dimension lives in a composable. Smooth
   the 500 ms ticks with `animateFloatAsState` (default spec; tuning is
   device-gated) and freeze the animation while a drag is settling. The
   hairline is non-interactive: seek lives on the now-playing screen; an
   interactive mini-player seek is a Phase 9 candidate.
3. **Contrast**: set `contentColor = MaterialTheme.colorScheme.onSurface` on
   the bar's `Surface` — this alone fixes the black icons (defect 2) and
   future-proofs any added control. Keep `surfaces.miniPlayer` alpha 0.92;
   raising the container to `surfaceContainerHigh` is a Phase 9 style choice,
   not needed for the fix.
4. **Drag feel**: raise the drag response from 12 dp to a visible lift —
   `translationY = -fraction * tokens.spacing.xl` (24 dp) plus a subtle scale
   (1.0 → 1.03, same fraction). `MiniPlayerExpandRules` stays untouched (the
   pure rules are correct; the lift magnitude is presentation).
5. **Settle timing**: call `onExpand()` **when `settleTarget` returns
   EXPANDED**, before the spring animation completes (the route slide-up from
   7g takes over the motion); keep the spring settle for the COLLAPSED
   target. The bar keeps its current swipe-zone claim/velocity logic.
6. **Unchanged on purpose**: the whole-bar tap/drag targets, twin
   registration, hidden-on-now-playing shell rule. The three-control layout is
   now previous / play-pause / next — the expand chevron from 7f was replaced
   on 2026-11-12 (user request; §5).

Known interaction to check on device: the shell hides the mini-player the
moment the now-playing route becomes current (`OmnisonicApp.kt:201`), so the
bar vanishes as the screen starts sliding up. The slide-up should mask this;
if the device session shows a pop, the follow-up is to delay the bar's exit
until the transition finishes — not part of 7f.

## 4. Fix design — task 7g: now-playing

Scope: `:style` (+1 motion token), `:app` (NavHost transitions),
`:feature-library` (screen fixes).

1. **Route transitions (defects 3 + 5):** add
   `MotionTokens.nowPlayingRoute = MotionTween(durationMs = 300)` rendered
   through `easingProfile.toEasing()` (STANDARD default). Wire per-destination
   transitions in `ShellNavHost`:
   - `nowPlaying`: `enterTransition = slideInVertically(tween) { it } + fadeIn`,
     `popExitTransition = slideOutVertically(tween) { it } + fadeOut` — the
     screen slides up from the bottom and collapses back down (matching the
     mini-player drag axis and predictive back's expectation).
   - `queue`: same enter spec (slides up **over** the still-visible
     now-playing screen) and the mirrored pop.
   - Every other route keeps the defaults. This is what makes the art drag
     up/down and the collapse/queue buttons feel animated — the screens
     themselves need no animation code for this.
2. **Centering (defect 4):** `Modifier.fillMaxWidth()` on the inner weighted
   `Column` in `NowPlayingScreen` — one line; the goldens prove it.
3. **Swipe direction (defect 6):** invert to
   `if (dx < 0f) onNext() else onPrevious()` (right-to-left = next, the D-19
   default), and swap the twin mapping so `art-previous`/`art-next` labels
   match the new actions. A user-facing direction setting is reserved for
   Phase 9 (gesture configuration, G7) — name it then, run the naming diff
   when it lands.
4. **Drag-follow feedback (defect 5 feel):** while a horizontal drag is in
   progress on the art, translate the art horizontally by a clamped fraction
   of the accumulated dx (≤ 24 dp) and spring it back on release, so the
   gesture is visible before the 32 dp threshold fires. Do not restructure
   the gesture detection (keep `detectDragGestures`, dominant-axis threshold,
   twins, and the pointer-pass rules from the 4d/4e bug records: never
   consume on an ancestor's Main pass).
5. **Out of scope:** shared-element cover morph (see §5), queue-screen row
   fixes, interactive mini-player seek, favorite write path (still disabled).
   The finger-following player sheet landed separately as task 7h
   (`ui/player-sheet.md`); 7g's route transitions remain the motion for direct
   navigation to those routes.

## 5. Alternatives considered and deferred

- **Shared-element cover morph (Spotify-style container transform):** Compose
  1.7 has `SharedTransitionLayout` and nav-compose 2.8 exposes the animated
  scope, so the art could morph from the bar thumbnail into the full-screen
  art. Deferred as a stretch goal: it needs a `SharedTransitionLayout` wrapped
  around the shell plus shared-element modifiers across two destinations, and
  the route slide-up already delivers the dominant feel improvement. Revisit
  after 7f/7g device verification.
- **Finger-following player sheet (the Spotify/YouTube Music model):** the
  drag fraction positions the now-playing surface directly instead of
  navigating. Not attempted in Phase 5 (route-based architecture); **shipped
  2026-11-12 as task 7h** after the user's mid-drag comparison showed 7f/7g
  still moved only the compact bar. The sheet is a shell-level overlay because
  NavHost transitions are not seekable; model, geometry and tests:
  `ui/player-sheet.md`. The configurable swipe-up target (the studied UX
  end-state's single-page composition, GESTURES.md §4.3) stays Phase 9.
- **Chevron removal:** shipped 2026-11-12 on user request — the expand chevron
  was redundant beside a whole-bar tap/drag target, and the slot is better used
  by previous. The bar keeps the "Expand player" content description, custom
  action and click target, so the gesture still has its button twin.

## 6. Test plan

- `:feature-library` `MiniPlayerViewModelTest` — position/duration/progress
  mapping (+2 cases); `MiniPlayerExpandRulesTest` unchanged.
- `:app` `MiniPlayerBarTest` — hairline present with the progress colors,
  progress rendered from state, content color set (semantics + capture).
- `:app` `PlayerScreensRenderTest` — direction flip (art drag left → next);
  centering is visual and covered by goldens.
- `:app` `ShellNavHostTest` — navigation wiring unchanged; transition specs
  are device-gated feel and asserted only as configuration if testable.
- `:style` `StyleTokensTest` — `miniPlayerProgressHeight` and
  `nowPlayingRoute` defaults; **naming diff checklist** (ground rules) for
  both new public names against `theme_keys.json`.
- `:app` `MiniPlayerGoldenTest` + `PlayerScreensGoldenTest` — re-record
  goldens (deliberate visual change; 411 × 891 dp portrait).
- Gate per task: `./gradlew :<module>:testDebugUnitTest` +
  `:app:assembleDebug`; banned-name scan over touched files.

## 7. Device acceptance checklist ([UNKNOWN] until a device session)

- Drag-expand: bar lift visible, slide-up overlaps the settle, no pop from
  the bar hide (§3 known interaction).
- Collapse/queue: slide-down/slide-over feel, predictive back on
  `nowPlaying`/`queue` (targetSdk 36).
- Hairline: contrast over the translucent bar, no seam against the bottom
  bar, landscape/rail rendering.
- Swipe: right-to-left = next feels natural; drag nudge does not fight the
  threshold; accidental-trigger rate unchanged or better.
- Contrast: mini-player controls legible on the device's actual art/backdrop
  combinations.
