# Player sheet vs system back — `ui/player-sheet-back-navigation.md`

Purpose: pin down why system back over the expanded player walks the hidden
`NavHost` back stack instead of closing the sheet (the open P1 in
`progress/KNOWN_ISSUES.md`), record the on-device evidence, and give the
implementation brief for the fix — exact files, seam, tests and device
acceptance protocol.

How derived: on-device reproduction on **2026-11-18** on the RMX3085
(Android 13 / API 33) with the debug build that shipped the D-33 on-device
logger; the reproduction was driven through `adb shell input` and read back
from `files/logs/runtime.log` (`Shell:` navigation / user-action lines). The
mechanism was then confirmed against the shell composition in `:app` and the
`androidx.navigation` 2.8.2 back-callback implementation, plus the
`BackHandler` / `OnBackPressedDispatcher` precedence rules. The fix and its
regression tests were implemented on **2026-11-18** and accepted on the same
RMX3085 (§9); the brief below is kept as written, with the one correction in
§4.1.

Confidence:

- The defect and its exact device signature (which press does what, and the
  route order it drains) — `[VERIFIED]` on the RMX3085, logged.
- The root cause (two enabled back callbacks; `NavHost`'s registered later and
  therefore dispatched first; it disables itself at the graph root, which is
  why the sheet finally receives the fourth press) — `[VERIFIED]` against the
  cited files and the 2.8.2 source; the device log matches every prediction.
- The fix and its behaviour — `[VERIFIED device]` for the album-detail path
  (log in §9) and `[VERIFIED device, user manual check]` for the queue variant;
  `[VERIFIED JVM]` for the RED-first regression tests.
- Predictive-back behaviour of the sheet — `[UNKNOWN]` and out of scope (D7).
- Other devices / release builds — `[UNKNOWN]`; only the RMX3085 debug build
  was exercised.

Date: 2026-11-18

Links: `ui/player-sheet.md` (detents, sheet-owned state machine, overlay
wiring), `ui/player-queue-return.md` §4.2 (the detent-aware back chain this
defect defeats), `progress/KNOWN_ISSUES.md` (the open P1 item),
`logging/on-device-logging.md` (the log stream used as evidence), D-25 (sheet
gesture ownership), D-33 (logger).

---

## 1. Defect

System back while the expanded player sheet is visible does **not** collapse
the sheet first. Instead it pops the destination underneath — which the user
cannot see because the sheet covers it — and only once that stack is at its
root does a further press reach the sheet.

The user-reported path (`KNOWN_ISSUES.md`, P1 "Navigation from the queue/nps
screen") is exactly what was reproduced:

```
Library tab → Albums section → album detail → Play → expand mini player
```

and then repeated system back. Observed: the sheet stays up through the first
three presses while the hidden route walks album → albums section → library
tab → Home tab; the fourth press collapses the sheet, revealing Home rather
than the album.

## 2. Device reproduction and evidence

Device: `RMX3085`, Android 13 (`ro.build.version.sdk=33`), debug APK built
2026-11-18 14:34 (`:app:assembleDebug`, includes the D-33 logger), installed
with `adb install -r -t`. Logs were cleared before the run
(`run-as app.omnisonic rm files/logs/*.log`, `adb logcat -c`).

Steps driven over adb:

1. cold start → `Library` tab → `Albums` section → first album tile;
2. tap `Play` (2-track album);
3. tap the mini player (`content-desc="Expand player"`);
4. four × `adb shell input keyevent KEYCODE_BACK`, dumping the accessibility
   tree between presses.

`runtime.log` for the whole run (`run-as app.omnisonic cat
files/logs/runtime.log`), timestamps in order:

```
2026-11-18T14:45:04.495 INFO  [main] Shell: navigation -> library
2026-11-18T14:45:17.398 INFO  [main] Shell: navigation -> library/{section}
2026-11-18T14:45:28.990 INFO  [main] Shell: navigation -> album/{albumId}
2026-11-18T14:45:41.853 INFO  [main] Playback: play: 2 entries from index 0
2026-11-18T14:45:43.992 INFO  [main] Shell: user action: mini-player:expand
2026-11-18T14:46:22.377 INFO  [main] Shell: navigation -> library/{section}
2026-11-18T14:46:36.531 INFO  [main] Shell: navigation -> library
2026-11-18T14:46:50.867 INFO  [main] Shell: navigation -> home
```

| Press (time) | Sheet after | Route after (logged) | Handler that ran |
|---|---|---|---|
| expand (14:45:43) | now-playing | `album/{albumId}` | — |
| back #1 (14:46:22) | now-playing (still up) | `library/{section}` | `NavHost` pop |
| back #2 (14:46:36) | now-playing (still up) | `library` | `NavHost` pop |
| back #3 (14:46:50) | now-playing (still up) | `home` | `NavHost` pop |
| back #4 (~14:47:05) | collapsed to peek | `home` (no new line) | sheet `BackHandler` |

Two details make the reading unambiguous:

- The sheet is fully expanded (position `0`) at press #1–#3, so a sheet
  handler would have collapsed it *and* logged no route, yet three route lines
  appear while the sheet is still up. The accessibility dump after each of the
  first three presses still has the now-playing tree; after #4 only the mini
  player (`content-desc="Expand player"`) is present.
- Press #4 produced **no** `navigation ->` line at all — the sheet handler ran
  and nothing else did. This is exactly the “only on the Home tab does the
  player close” half of the user report.

No crash was produced (`files/logs/crash.log` absent); this is a routing
defect, not a stability one.

## 3. Root cause

### 3.1 Two enabled back callbacks, one winner

The shell registers its sheet handler in `OmnisonicShell`:

- `app/src/main/java/app/omnisonic/shell/OmnisonicApp.kt:270` —
  `BackHandler(enabled = sheet.isVisible) { sheet.onBack() }`.

Navigation-Compose's `NavHost` registers its own `OnBackPressedCallback` that
calls `popBackStack()`
(`app/src/main/java/app/omnisonic/shell/OmnisonicApp.kt:617-619`,
`ShellNavHost`). Both are on the same activity dispatcher.

`OnBackPressedDispatcher` invokes enabled callbacks in **reverse order of
addition** — the most recently added enabled callback wins and stops the
chain (`developer.android.com/reference/androidx/activity/OnBackPressedDispatcher`).
`activity-compose` states the same for composition order: “the one that is
composed last among all enabled handlers will be invoked”
(`developer.android.com/reference/kotlin/androidx/activity/compose/BackHandler.composable`).

The shell's `BackHandler` is composed at line 270, in the body of
`OmnisonicShell`. The `NavHost` is composed inside `ShellContentArea`
(line 456) inside the `Box` (line 330), i.e. **after** that body call and
before `PlayerSheetOverlay` (line 437). So the `NavHost` callback is added
later and is dispatched first. The sheet handler never sees presses #1–#3.

### 3.2 Why the fourth press reaches the sheet

Navigation only keeps its callback enabled while there is something to pop:
the runtime sets

```
onBackPressedCallback.isEnabled =
    (enableOnBackPressedCallback && destinationCountOnBackStack > 1)
```

(`androidx/navigation/.../NavController.android.kt`,
`updateOnBackPressedCallbackEnabled()`; same in the 2.8.2 artefact). After
press #3 the back stack holds only the `home` root entry — count `1`, callback
disabled — so the dispatch falls through to the shell's handler, which runs
`PlayerSheetState.onBack()` and collapses the sheet. That is precisely the
observed “only works on Home” condition, and it means the bug is **not**
route-specific: any non-root destination underneath reproduces it.

### 3.3 The sheet's own rule is correct

`PlayerSheetState.onBack()`
(`app/src/main/java/app/omnisonic/shell/PlayerSheetHost.kt:154`) applies the
detent-aware rule `PlayerSheetRules.backDestination(position)`
(`feature-library/.../player/PlayerSheetRules.kt:106-111`): below
`NOW_PLAYING` → `NOW_PLAYING`, otherwise → `PEEK`. From the expanded player
(`0`) the correct first back is “collapse to the mini bar”, revealing the album
screen underneath — the user's expected behaviour. Nothing in the sheet or its
rule needs changing; the handler just never runs.

### 3.4 Why the JVM tests missed it

`MiniPlayerBarTest.systemBackOnTheQueueGoesToNowPlayingThenCollapses`
(`app/src/test/java/app/omnisonic/shell/MiniPlayerBarTest.kt:603`) drives the
real `OnBackPressedDispatcher` but `setShell` (line 239) starts the
`NavHost` at `ShellRoutes.LIBRARY` and never navigates. The back stack then has
one destination, the `NavHost` callback is disabled, and the test proves only
the “sheet is the only enabled handler” path — the exact condition under which
the bug does not occur. The suite has no case with a pushed destination
underneath the sheet.

### 3.5 Logging gap (optional instrumentation)

A back press that the sheet handles logs nothing — runtime.log records route
changes and `user action:` lines, and `BackHandler { sheet.onBack() }` emits
neither. During this investigation the absence of a `navigation ->` line plus
the mini-player dump was the proof. Adding
`onUserAction("player:back")` inside the handler (next to the existing
`player:collapse` / `player:open-queue` actions) would make future logs
conclusive on their own. Not required for the fix.

## 4. Fix brief

> **Correction (2026-11-18, after implementation):** the shape sketched in §4.1
> was implemented first and **does not work** on navigation-compose 2.8.2 —
> `NavHost` registers its own `PredictiveBackHandler` (enabled while the back
> stack is deeper than its root), and `NavHostController.enableOnBackPressed`
> only toggles the `NavController` callback, so the dispatcher still held an
> enabled callback and the route kept popping (JVM probe: sheet stayed at `0.0`,
> route fell back to `library`). §9 records the shipped mechanism, which owns
> back at the sheet's visibility edge instead. The rest of §4 is unchanged as
> the record of the rejected options.

### 4.1 Recommended: disable the NavHost's back callback while the sheet is up

> Superseded by §9 — this lever is a no-op against 2.8.2's `NavHost` (see the
> correction above).

Remove the competitor instead of relying on registration order. In
`OmnisonicShell`, next to the existing handler
(`OmnisonicApp.kt:270`):

```kotlin
BackHandler(enabled = sheet.isVisible) { sheet.onBack() }

// The sheet owns back while it is visible. Navigation's own callback is
// enabled whenever the back stack is deeper than its root and, being
// composed inside the NavHost below, is added to the dispatcher after the
// handler above — so without this it wins and pops the hidden route.
LaunchedEffect(sheet.isVisible) {
    controller.enableOnBackPressed(!sheet.isVisible)
}
```

Why this shape:

- `NavHostController.enableOnBackPressed(Boolean)` is the supported lever
  (`developer.android.com/reference/androidx/navigation/NavHostController`,
  added 2.1.0; `controller` is typed `NavHostController`, which is the public
  overload — the `NavController` implementation method is
  `@RestrictTo(LIBRARY_GROUP)`).
- It works regardless of future composition order: if the overlay or the
  `NavHost` moves, the disabled/enabled state still follows sheet visibility.
  Ordering is the bug, so a fix that only flips the ordering is a latent
  regression.
- `sheet.isVisible` is already read for the `BackHandler`, so the effect
  restarts exactly on the peek/non-peek crossing (it is
  `viewportPx > 0 && position < PEEK - ε`, `PlayerSheetHost.kt:56`). At peek
  the effect restores `true`, and normal route popping resumes.
- `BackHandler` stays called unconditionally with an `enabled` argument, as
  `activity-compose` requires; only the NavHost gate is toggled.
- Nothing else registers a back callback in the app (one `BackHandler`, one
  `NavHost`), so the dispatcher has no third competitor.

Edge cases to keep in mind while implementing:

- Use the same controller instance the `NavHost` received — the shell's
  `navController` parameter is the test seam and the production
  `rememberNavController()`; do not resolve a second one.
- The flag is a plain `Boolean` on the controller; Navigation recomputes the
  callback on every back-stack change, so re-enabling cannot strand a pending
  pop. `popBackStack()` itself is untouched.
- While the sheet is visible the platform has no route-pop progress callback;
  the sheet has no predictive-back progress either (D7, out of scope), so this
  is no regression, but do not claim predictive back for the sheet.
- The standalone `ShellRoutes.QUEUE` route has no sheet host
  (`OmnisonicApp.kt:738` in `ShellNavHost`), so the gate is irrelevant there.

### 4.2 Alternative (smaller, weaker)

Move the `BackHandler` into `PlayerSheetOverlay`
(`PlayerSheetHost.kt:235`), which is composed after `ShellContentArea`
(`OmnisonicApp.kt:437`): the sheet's callback would then be added last and win
the LIFO dispatch. It is a smaller diff, but it codifies “register after the
`NavHost`” — the same ordering assumption that produced this bug — and would
silently break again if the overlay is hoisted or the `NavHost` moves inside
the sheet. Use only if 4.1 is rejected for API reasons.

### 4.3 Rejected options

| Option | Why not |
|---|---|
| Intercept `OnBackPressedDispatcher` globally / `fallbackOnBackPressed` | Replaces the whole back chain for one overlay; breaks route pops elsewhere |
| Conditional composition of `NavHost` or hoisting it above the sheet | Re-composes the route host; loses the “route underneath stays composed” property the sheet reveal depends on |
| `android:enableOnBackInvokedCallback="false"` | Removes predictive back app-wide, not just under the sheet |
| Popping routes manually from the sheet handler | Treats the symptom; the hidden stack still changes on every press |
| Relying on a fourth-press workaround / “back twice” copy | The drain order is data-dependent (deep links can add entries) |

## 5. Tests to add with the fix

Extend `:app` `MiniPlayerBarTest` with a deep stack, which the current suite
never builds:

1. `systemBackOverTheSheetDoesNotWalkTheRouteBeneath`
   - `setShell(playingState())` (start `LIBRARY`), then
     `nav.navigate(ShellRoutes.album(1L))` and `waitForIdle`;
   - expand the player (`"Expand player"`), wait for
     `sheet.isVisible`;
   - `pressBack()` → assert `nav.currentDestination?.route` is still
     `ShellRoutes.ALBUM` **and** the sheet settled at `NOW_PLAYING`;
   - `pressBack()` → route still `ALBUM`, sheet at `PEEK`/invisible;
   - `pressBack()` → route now `LIBRARY` (the sheet no longer owns back).
   This case must fail on the pre-fix code (first press would already pop to
   `LIBRARY`), so write it RED first.
2. `systemBackOnTheQueueOverADeepStack...`
   - same deep stack, open the queue, `pressBack()` → sheet at
     `NOW_PLAYING`, route unchanged; `pressBack()` → sheet peek, route
     unchanged.
3. Keep the existing root-stack test unchanged; it is the “only handler”
   regression guard and documents why the pair matters.

`nav.currentBackStack.value.size` may be asserted as a cheap extra proof that
the route count did not shrink. The album route needs only the `Long` nav
argument; a detail ViewModel rendering no rows is fine for this test — put the
assertions on the route and the sheet, not on album content.

Gate: `./gradlew :app:assembleDebug test lint` plus the banned-name scan over
touched files, as usual.

## 6. Device acceptance protocol (after the fix)

On the same debug build and path (`Library → Albums → album → Play → expand`):

1. back #1: sheet collapses to the mini player, the **album** screen is
   revealed; `runtime.log` shows **no** `navigation ->` line.
2. back #2: pops album → Albums section; sheet stays at peek.
3. back #3 → Library tab; back #4 → Home tab; back #5 → system exits (or the
   launcher), as before.
4. Queue variant: open the queue from the player, back → now-playing (no
   route line), back → collapse (album revealed).
5. Regression checks: system back with the sheet closed still pops routes at
   every depth; a route pop with the sheet up is impossible; tab switching,
   mini-player expand/collapse and the queue's own back arrow are unchanged.

Record the pass in `KNOWN_ISSUES.md` and `progress/STATE.md`; until then the
P1 stays open. This investigation is device-verified; the fix is now too (§9).

## 7. Where the code lives

- `app/src/main/java/app/omnisonic/shell/OmnisonicApp.kt` — the sheet's back
  callback and its visibility-edge `DisposableEffect` (line 270 area), the
  sheet/overlay wiring (lines 240-270, 437), the
  `Box` + `ShellContentArea` order (lines 330-437), `NavHost`
  construction (lines 617-619) and `ShellNavHost` (line 593).
- `app/src/main/java/app/omnisonic/shell/PlayerSheetHost.kt` — `isVisible`
  (line 56), `onBack()` (line 154), `PlayerSheetOverlay` (line 235); no change
  expected.
- `feature-library/src/main/java/app/omnisonic/feature/library/player/PlayerSheetRules.kt`
  — `backDestination` (lines 106-111); no change expected.
- `app/src/test/java/app/omnisonic/shell/MiniPlayerBarTest.kt` — `setShell`
  (line 239), `pressBack` (line 444), the root-stack back test (line 603).
- `progress/KNOWN_ISSUES.md` — P1 “Navigation from the queue/nps screen”.

## 8. Out of scope / unconfirmed

- Predictive-back animation or progress for the custom sheet overlay (D7 in
  `ui/player-queue-return.md`).
- Whether the same ordering defect affects any future shell-level overlay
  that adds its own callback — the recommended gate removes the class for the
  sheet only.
- Any device other than the RMX3085 / API 33 debug build; release-build
  behaviour remains `[UNKNOWN]` (only the debug APK was exercised).

## 9. Implemented and verified (2026-11-18)

### 9.1 Shipped mechanism

The sheet **owns back at its visibility edge**, not through composition order:
while `sheet.isVisible`, the shell registers its own `OnBackPressedCallback` on
the activity dispatcher and removes it again at peek. Because it is added when
the sheet opens — after the `NavHost` has registered its callback — the
dispatcher's newest-first dispatch reaches the sheet first. The callback's
lifetime is the visibility edge, so nothing depends on where the handler sits
in the tree.

```kotlin
// OmnisonicShell, where the old BackHandler was
val backDispatcherOwner = LocalOnBackPressedDispatcherOwner.current
val lifecycleOwner = LocalLifecycleOwner.current
val sheetVisible = sheet.isVisible
DisposableEffect(backDispatcherOwner, lifecycleOwner, sheet, sheetVisible) {
    val dispatcher = backDispatcherOwner?.onBackPressedDispatcher
    if (!sheetVisible || dispatcher == null) return@DisposableEffect onDispose {}
    val callback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = sheet.onBack()
    }
    dispatcher.addCallback(lifecycleOwner, callback)
    onDispose { callback.remove() }
}
```

`enableOnBackPressed` was removed (dead on this library version, §4.1
correction). `PlayerSheetState`, `PlayerSheetRules`, the overlay geometry and
every route are untouched. `[VERIFIED JVM]` the two new cases fail on the
pre-fix code with the sheet frozen at `0.0` / `-1.0` and pass with the
callback; `[VERIFIED device]` the device log below.

### 9.2 Tests

`app/src/test/java/app/omnisonic/shell/MiniPlayerBarTest.kt`
(`MiniPlayerShellTest`), both RED-first against the old code:

1. `systemBackOverTheSheetDoesNotWalkTheRouteBeneath` — pushes
   `ShellRoutes.album(1L)`, expands the player; back #1 settles at `PEEK` with
   the route still `ALBUM`, back #2 pops to `LIBRARY`.
2. `systemBackOnTheQueueOverADeepStack` — same pushed album route, opens the
   queue; back #1 → `NOW_PLAYING`, back #2 → `PEEK`, with
   `currentBackStack.value.size` unchanged on both.

Counts: `MiniPlayerShellTest` 27 (25 existing + 2 new); full `:app` suite 145
tests / 30 classes, 0 failures; `:app:assembleDebug test lint` exits 0 (serial
gate, log `/tmp/back-fix-gate.log`); banned-name scan clean on both touched
files.

### 9.3 Device acceptance (RMX3085, Android 13 / API 33, debug APK)

Same path as §2 (`Library → Albums → 1920 → Play → expand`), fresh install,
`runtime.log` cleared, `adb logcat -c`. `runtime.log` for the back chain:

```
2026-11-18T15:26:55.386 INFO  [main] Shell: navigation -> library/{section}
2026-11-18T15:27:14.002 INFO  [main] Shell: navigation -> album/{albumId}
2026-11-18T15:27:43.155 INFO  [main] Shell: user action: mini-player:expand
2026-11-18T15:30:40.991 INFO  [main] Shell: navigation -> library/{section}
2026-11-18T15:30:44.702 INFO  [main] Shell: navigation -> library
2026-11-18T15:30:47.179 INFO  [main] Shell: navigation -> home
```

| Press | Sheet after | Route after | Handler |
|---|---|---|---|
| back #1 (~15:30:38) | collapsed to peek; **album detail revealed** | `album/{albumId}` (no line) | sheet callback |
| back #2 (15:30:40) | peek | `library/{section}` | `NavHost` |
| back #3 (15:30:44) | peek | `library` | `NavHost` |
| back #4 (15:30:47) | peek | `home` | `NavHost` |

Screenshots confirmed the now-playing sheet over the album detail before the
first press and the album detail with the mini player after it. The queue
variant (expand → queue → back → now-playing → back → peek with the album
revealed) was checked manually on the same device by the user and reported
working; no `runtime.log` excerpt was captured for that pass.

Still `[UNKNOWN]`: predictive-back progress for the sheet (D7, out of scope),
release builds, other devices.
