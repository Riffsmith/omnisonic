# Search tab fixes — `search-tab-fixes.md`

Purpose: Hand off the `progress/KNOWN_ISSUES.md` P1 search cluster — the keyboard rising
on Settings after a direct tab tap, and the query field needing to live at the
bottom of the screen — and pin the search-tab interaction contract that the
fix establishes. This is a standalone bug fix, **not Phase 6 and not part of
any phase**.

How derived: source review of `SearchScreen`, `MainTabsPager`, `OmnisonicApp`
(pager/tab plumbing and the shell column), `SearchPageConfig`, `ShellSettingsScreen`
and the manifest activity declaration; a scan of every focus/IME-related test in
`app` and `feature-search` (none exist); and the Android window-insets contract
for `windowSoftInputMode` under targetSdk 36. No device was attached during the
investigation (2026-11-17).

Confidence:
- [VERIFIED] the search field autofocuses from `SearchScreen`'s
  `LaunchedEffect(isActive)`, and `isActive` is fed by the transient
  `pagerState.currentPage`.
- [VERIFIED] a tab tap animates the pager through every intervening page, and no
  code clears focus when the search tab stops being active.
- [INFERRED] that combination raises the keyboard while animating Home → Settings
  with Search never visited. It fits the reported "correct only after visiting
  Search and hiding the keyboard" condition. Not device-reproduced.
- [VERIFIED] the query field is the first child of the search column and there
  is no bottom-anchored variant.
- [VERIFIED] the app applies no window insets anywhere today; the activity is
  `adjustResize`, targetSdk is 36. Bottom-anchored + IME behaviour must be
  handled explicitly and verified on device.
- [UNKNOWN device] the exact IME/bars result on the test phones and the feel of
  the bottom field.

Date: 2026-11-17

Links: `../playback/notification-presentation-fixes.md` (the other P1 cluster),
`../search/data-layer.md` (query/ranking/paging — unchanged by this fix),
`gesture-contract.md` (tab+pager pairing, button twins),
`progress/KNOWN_ISSUES.md`.

---

## Phase boundary (read before coding)

- This is the P1 fix recorded in `progress/KNOWN_ISSUES.md` and is **outside the
  phase structure**.
- Phase 6 is **not** being started. Do not touch `.agents/plans/phase-5-tasks.md`,
  `.agents/plans/phase-plans.md`, `.agents/plans/roadmap-ref.md`, or any phase
  status record.
- Search's data layer (tokenising, ranking, paging, `ProfileScope`) is untouched
  by this fix; only the search **screen** and its pager wiring change.

## Implementation status (2026-11-17) — implemented, JVM-verified, device gate OPEN

B1 and B2 are in the working tree (uncommitted). No device attached, so the
IME/bars behaviour is `[UNKNOWN device]`.

**Chosen options (the brief's open questions, decided here):**

1. **Active flag:** `MainTabsPager` now passes
   `isActive = state.settledPage == page && !state.isScrollInProgress`. The
   `SearchRoute` signature is unchanged (no extra parameter), so existing call
   sites and tests keep compiling.
2. **Focus rule:** `feature-search/.../search/SearchFocusRules.shouldRequestFocus`
   is the single decision (`isActive && autofocusOnEntry && IDLE`);
   `SearchScreen` clears focus whenever `isActive` is false.
3. **Inset API:** the shell applies `.navigationBarsPadding().imePadding()` to
   the portrait content column (so content, mini-player and bottom bar rise
   together) and to the landscape rail `Row` (so the rail is inset too).
   `SearchScreen` applies no IME padding — the inset is counted once.
4. **Navigation-bar inset is a new, slightly broader shell change:** the app had
   none at all under targetSdk 36 edge-to-edge; it now pads both shell branches.
   No other screen's content maths changed.
5. **No search Roborazzi golden** was added (optional in the brief). The layout
   is covered by the existing render/golden suite and the device checklist.

**Layout details:** `SearchScreen` is now `body (weight 1f)` → `CategoryChips`
→ `OutlinedTextField`, the bottom block carries an opaque
`MaterialTheme.colorScheme.background`, and the lists keep their existing
bottom `contentPadding` (`spacing.xl`; the body is a sibling, never an overlay).

**Test evidence `[VERIFIED JVM]`:**

- `:feature-search` `SearchFocusRulesTest` 4/4 — inactive, active+IDLE+config,
  every non-IDLE phase, config off.
- `:app` `MainTabsPagerTest` 8/8, including the new
  `directSettingsTapNeverFocusesTheSearchField`. It stops the compose clock,
  clicks the Settings tab, and asserts **no focused node on any stepped frame**
  across the animation, then lets it settle and asserts the Settings screen.
  The test was validated both ways: it **fails** with
  `isActive = state.currentPage == page` (`focusedDuringAnimation != 0`) and
  passes with the settled flag — so it does not fall into the post-settle
  blind spot the brief warns about.
- `:app` 121 debug + 16 release, `:feature-search` 15, `:app:assembleDebug` and
  `lint` green; banned-name scan clean.

**Device checklist:** not run (no device attached) — keyboard/bars scenarios are
`[UNKNOWN device]`. The P1 search entries in `progress/KNOWN_ISSUES.md` stay open.

## Symptoms (from `progress/KNOWN_ISSUES.md` P1)

1. Tapping Settings without visiting Search first raises the keyboard on
   Settings. After visiting Search and hiding the keyboard, the behaviour looks
   correct.
2. The search input box should be at the bottom instead of the top; it must not
   be hidden behind the mini-player or the bottom bar, and must not hide them.

## Evidence

### The autofocus path [VERIFIED by source]

- `app/src/main/java/app/omnisonic/shell/MainTabsPager.kt:78` passes
  `isActive = state.currentPage == page` into `SearchRoute`.
- `feature-search/src/main/java/app/omnisonic/feature/search/ui/SearchScreen.kt:138-142`:
  ```kotlin
  LaunchedEffect(isActive) {
      if (isActive && config.autofocusOnEntry && state.phase == SearchPhase.IDLE) {
          focusRequester.requestFocus()
      }
  }
  ```
  There is no `else` branch, so losing activity never clears focus.
- `MainTabsPager` calls `SearchRoute` without a `config`, so
  `SearchPageConfig.DEFAULT` applies and `autofocusOnEntry` is `true`
  (`style/src/main/java/app/omnisonic/style/SearchPageConfig.kt`).
- Tab taps call `pagerState.animateScrollToPage(index)`
  (`app/src/main/java/app/omnisonic/shell/OmnisonicApp.kt:181-189`). A
  `HorizontalPager` composes and scrolls through the pages between the current
  and target page; `currentPage` advances with the animation. A Home → Settings
  tap therefore makes the Search page (index 2) briefly "active", firing the
  effect and requesting focus, even though the user chose Settings.
- Nothing then moves focus away: the effect keyed on `isActive` does not run a
  cleanup, and Compose does not clear focus just because a sibling page settles
  on top. The IME opened during the animation stays up over Settings.
- This also explains the "works if Search was visited first" observation: after
  the keyboard is dismissed, `LaunchedEffect(isActive)` is not re-run (the key
  has not changed), so no new focus request is made on the later Settings tap.

### The layout path [VERIFIED by source]

- `SearchScreen` is a `Column` whose first child is the `OutlinedTextField`
  (lines 144-176), followed by `CategoryChips` (line 178) and a
  `fillMaxSize()` results/idle body. The field is therefore at the top.
- The search tab is drawn by `MainTabsPager` inside `ShellContentArea`, and the
  shell stacks `ShellContentArea` (weight 1) → mini-player → bottom bar in a
  `Column` (`OmnisonicApp.kt:336-370`). Content and bars cannot overlap by
  construction, so a bottom field inside `SearchScreen` is structurally above
  the mini-player/bottom bar.
- The activity declares `android:windowSoftInputMode="adjustResize"`
  (`app/src/main/AndroidManifest.xml:23-26`), but the app has **no**
  `WindowInsets` / `imePadding` / `statusBarsPadding` / `navigationBarsPadding`
  call sites in main sources, and targetSdk is 36 (Android 15 enforces
  edge-to-edge). A bottom-anchored field needs explicit IME handling; relying on
  `adjustResize` alone is not safe.

### Test coverage [VERIFIED]

- No test in `app/src/test` or `feature-search/src/test` references focus,
  `focusRequester`, `isFocused`, or the keyboard. `MainTabsPagerTest` covers
  swipes, edge exclusion, route arrival and button twins only.
- `SearchViewModelTest` covers state, not layout/focus.
- There is no search golden.

### Baseline state

- `git status` clean on `main` at `25fceec`; no device attached
  (`adb devices` empty), so the keyboard reproduction is code-derived. The
  existing `:app` `MainTabsPagerTest` was run as part of the investigation and
  passes (BUILD SUCCESSFUL).

## Chosen design

### B1. Autofocus only on the settled, user-selected search tab

The fix must keep `SearchPageConfig.autofocusOnEntry` meaningful (a future
style file may turn it off) while never focusing during a pass-through.

1. **Pass settled state, not transient state.** In `MainTabsPager`, compute the
   active flag from the settled page and a non-scrolling pager, e.g.
   `isActive = state.settledPage == page && !state.isScrollInProgress` (the
   pager is only interactive when `showingTabs`; keep that gate if the pager can
   be covered). Alternatively pass a dedicated `isSettledSelected` parameter so
   the semantics are explicit in the composable signature. Do not key autofocus
   on `currentPage`.
2. **Clear focus when leaving.** In `SearchScreen`, extend the effect to clear
   focus when the tab is no longer active:
   ```kotlin
   LaunchedEffect(isActive) {
       if (!isActive) { focusManager.clearFocus(); return@LaunchedEffect }
       if (config.autofocusOnEntry && state.phase == SearchPhase.IDLE) {
           focusRequester.requestFocus()
       }
   }
   ```
   `LocalFocusManager` is already imported/used for the IME "search" action.
3. **Pure rule for testability.** Extract the decision to a small function,
   e.g. `SearchFocusRules.shouldRequestFocus(isActive, phase, config)`, in
   `:feature-search` (or `:style` if it must be shared). Unit-test the matrix
   (inactive, searching, results, config off, active+idle) without Compose. The
   Compose path then only wires flags into it.
4. Keep `ImeAction.Search` / `KeyboardActions` and the trailing clear button as
   they are.

### B2. Bottom-anchored query field with the chrome above the IME

Layout inside `SearchScreen`:

```
Column(fillMaxSize) {
    body (weight 1f)      // idle / searching / error / no-results / results
    CategoryChips(...)    // config-driven, directly above the field
    OutlinedTextField(...) // bottom of the content area
}
```

- Results/idle `LazyColumn`s get a bottom `contentPadding` so the last row can
  scroll clear of the chips/field (the body is a sibling, not an overlay, so
  this is comfort, not correctness).
- Chips stay where they are relative to the field: immediately above it. Both
  sit in the same bottom block so a config change (SINGLE_LINE / WRAPPED) does
  not move the field.
- Give the bottom block an opaque surface/background so list content does not
  show through during overscroll; use existing tokens (`tokens.surfaces…`,
  spacing) — no hard-coded colors/sizes.
- **Insets:** handle this once, at the shell. Apply `.imePadding()` to the
  shell content column(s) (`OmnisonicApp.kt`, both the portrait bottom-bar
  branch and the landscape rail branch) so the **content area, the mini-player
  and the bottom bar all move above the IME together**. The user chose "keep
  mini-player and bottom bar visible above the IME", so do not hide them while
  typing. Do not also apply `imePadding` inside `SearchScreen`, or the inset is
  counted twice.
- Check the shell's navigation-bar inset while you are there: with targetSdk 36
  edge-to-edge, the bottom bar needs `navigationBarsPadding()` (or
  `WindowInsets.safeDrawing`) so the field/bars are not under the gesture bar.
  This is pre-existing shell behavior; keep the change minimal and note it in
  the brief/STATE if it is broader than the search fix.
- Landscape: the rail branch's `Column` gets the same IME treatment; the pager
  width is reduced by the rail already. Verify the field does not collide with
  the mini-player.

## Expected behaviour after the fix

- Fresh launch, tab Home: no keyboard.
- Home → Settings (Search never visited): no keyboard, no search focus, Settings
  renders normally.
- Home → Library → Search: on settle, the query field focuses and the IME opens
  (because `autofocusOnEntry` defaults on). Back/IME dismiss leaves the field
  visible at the bottom.
- Any tab change away from Search closes the IME and clears search focus.
- With the keyboard open on Search: the field is directly above the IME, and the
  mini-player (if visible) and bottom bar stay visible above the IME without
  covering the field.
- Swiping between tabs still works; the tab bar and edge exclusion are
  unchanged.
- Search results/queries/paging behave identically.

## Files likely to change

| File | Change |
|---|---|
| `feature-search/src/main/java/app/omnisonic/feature/search/ui/SearchScreen.kt` | bottom field + chips block, `weight(1f)` body, focus clear, settled-active gate wiring, list bottom padding |
| new `feature-search/.../search/SearchFocusRules.kt` (or similar) | pure focus-decision rule |
| `app/src/main/java/app/omnisonic/shell/MainTabsPager.kt` | pass settled/non-scrolling active flag; keep `SearchRoute` signature or add an explicit parameter |
| `app/src/main/java/app/omnisonic/shell/OmnisonicApp.kt` | `imePadding()` (+ navigation-bar inset if required) on the shell content column(s) |
| `feature-search/src/test/...` | `SearchFocusRulesTest`; layout/focus test if the harness supports it |
| `app/src/test/java/app/omnisonic/shell/MainTabsPagerTest.kt` | direct Home → Settings tap never focuses the query field |
| `feature-search` strings (only if new copy is introduced; none is planned) | — |

Do not change `SearchViewModel`, `SearchRepository`, the DAO queries, paging,
`SearchPageConfig`'s fields/defaults, or the tab definitions.

## Testing and verification

JVM:

1. `SearchFocusRulesTest` — matrix: inactive → never; active + IDLE + config on →
   request; active + non-IDLE → not; config off → not. Pure, no Compose.
2. `MainTabsPagerTest` addition — launch on Home, click the Settings tab directly
   (`onNode(tabItem("Settings")).performClick()`), and prove the search field
   never took focus. Design note to validate first: after the pager settles the
   Search page is disposed, so a post-settle "no focused node" assertion can
   pass even with the bug. The reliable shape is to stop the compose clock
   (`composeRule.mainClock.autoAdvance = false`), perform the click, advance a
   few frames so the pager is mid-animation, assert
   `onAllNodes(isFocused()).fetchSemanticsNodes()` contains no text field, then
   re-enable the clock and let it settle. Confirm this works under Robolectric
   before committing to it; if the animation cannot be stepped reliably, fall
   back to asserting the flag plumbing (that `SearchRoute` receives
   `isActive = false` mid-animation) plus the pure rule, and leave the IME proof
   to the device pass.
3. Layout test — render `SearchScreen` at the phone qualifier
   (`@Config(qualifiers = "w411dp-h891dp")`, as the library tests do) and assert
   the query field's bounds are below the results body's bounds (and, when chips
   are visible, above the field). Assert the field has a non-zero height and is
   reachable; do not assert IME visibility in Robolectric.
4. Existing `MainTabsPagerTest`, `SearchViewModelTest`, `SearchPageConfigTest`
   stay green. `ShellGoldenTest`'s `shell_home_portrait` must not change (Home is
   untouched); if a search golden is added, record it deliberately (R-25).

Project gate: `./gradlew :app:assembleDebug test lint`; banned-name scan.

Device acceptance (needs an attached device):

1. Fresh install → Home; tap Settings directly; confirm no IME
   (`adb shell dumpsys input_method | grep -E "mInputShown|mCurFocusedWindow"`).
2. Repeat after visiting Search and dismissing the IME.
3. Tap Search; confirm focus + IME, field at the bottom, results scroll in the
   remaining space, last result reachable.
4. With the IME open on Search, confirm the mini-player (play something first)
   and bottom bar are visible above the IME and none overlaps the other.
5. Swipe Home → Library → Search and tab-tap Search; confirm autofocus only on
   settle.
6. Rotate to landscape (rail) and repeat 3-4.
7. Record the results in `progress/KNOWN_ISSUES.md` + `progress/STATE.md` with tags.

## Risks and constraints

- **IME/inset behaviour is the largest unknown.** There is no existing inset
  handling and targetSdk 36 enforces edge-to-edge on Android 15+. The view
  hierarchy could also shift the mini-player/bottom bar in ways Robolectric
  cannot model. The device pass is mandatory.
- **Double insets**: apply IME padding once (shell). Applying it in both the
  shell and `SearchScreen` produces a dead gap.
- **Autofocus flag semantics**: keep `autofocusOnEntry` respected; gating on
  settled state changes *when* it fires, not whether the config works.
- **Pager state semantics**: `settledPage` updates at the end of the settle;
  while a user drags the pager to Search and holds, autofocus will wait until
  release — that is the desired behaviour (matches "only when actually on the
  tab"). Confirm no duplicate trigger from both the page-derived flag and the
  route-derived `scrollToPage` path.
- **Test flakiness**: the mid-animation focus assertion depends on Robolectric's
  clock control; validate it early. A flaky test is worse than the pure-rule
  test plus a device checklist.
- **Scope creep**: no search data-layer or tab-config changes; no redesign of
  the search screen beyond moving the field and handling insets.

## Unresolved questions

1. Exact shell inset approach: `imePadding()` alone vs
   `windowInsetsPadding(WindowInsets.safeDrawing)` plus IME; decide while
   implementing and keep it to the content column.
2. Whether the chips belong visually grouped with the field (recommended, both
   in the bottom block) or stay under the results as a filter strip. The user
   only specified the field, so the least-change option is chips above the
   field.
3. Whether to add a search golden (would make the bottom-field layout
   regression-visible). Optional; goldens update deliberately (R-25).

## Acceptance criteria

- [ ] A direct Home → Settings tab tap never focuses the query field or raises
      the IME; the same holds from any tab when Search was never selected.
- [ ] Leaving the Search tab always clears search focus and closes the IME.
- [ ] The query field is at the bottom of the search content, with chips above
      it, above the mini-player and bottom bar, and reachable/usable with the
      IME open; the mini-player and bottom bar stay visible above the IME.
- [ ] Search results/idle/error states and paging are unchanged apart from
      padding.
- [ ] Pure focus-rule tests pass; the focus regression test (or its documented
      fallback) is in place; the full gate and banned-name scan pass.
- [ ] Device checklist recorded; `progress/KNOWN_ISSUES.md` P1 search items updated and
      only closed when the device pass is green.

## Where implementation starts

1. Read this brief and `gesture-contract.md` (tab+pager ownership).
2. Implement B1 first: extract `SearchFocusRules`, wire the settled flag in
   `MainTabsPager`, add the focus clear in `SearchScreen`, and add the pure-rule
   tests. This is the smallest change that removes the reported keyboard
   defect.
3. Then B2: move the field/chips to the bottom block, add the body weight and
   list padding, then the shell `imePadding`/navigation-bar handling.
4. Add the `MainTabsPagerTest` regression case (validate the clock strategy
   first); then the device pass.
5. Update `progress/KNOWN_ISSUES.md`, `progress/STATE.md`,
   `progress/CHANGELOG.md`, this brief, `.agents/memory/findings.md`, and only
   then add the decision entry to `docs/omnisonic/decisions.md` (the user asked
   to wait until the fix lands).
