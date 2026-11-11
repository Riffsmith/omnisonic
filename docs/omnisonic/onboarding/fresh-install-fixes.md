# Fresh-install / onboarding fixes — `onboarding/fresh-install-fixes.md`

Purpose: root-cause and fix plan for the two entries under
`progress/KNOWN_ISSUES.md` → *Fresh-install / onboarding flow*. Written as a
session brief so a fresh agent can implement both fixes and their tests without
re-deriving the analysis.

How derived: read of the current sources and tests on 2026-11-11 (no code
changed by this document). Line numbers are accurate for that date; re-locate by
symbol if they have moved. Behavior claims cite the file and symbol that
implements them.

Confidence: both root causes are `[VERIFIED]` by source reading and by the
affected tests listed below. Fix designs and their test plans are `[INFERRED]`
from existing project patterns. On-device feel (does the auto-advance beat the
"All stocked up" line) is `[UNKNOWN]` until device testing.

Date: 2026-11-11

Links: `progress/KNOWN_ISSUES.md` · `providers/library-sync-ux.md` ·
`ui/provider-setup.md` · `decisions.md` (D-12, D-16) ·
`.agents/plans/phase-5-tasks.md` · `storage/library-sync-ownership.md`

---

## Status and scope

- **Not task 8.** These are two small defects, not the Phase 5 wrap-up. Land
  them as one change (or two, issue 1 then issue 2) *before* task 8 so the
  wrap-up's full `./gradlew test` and doc pass see final code.
- Modules touched: `:core-data` (one DAO query + one test), `:app` (shell view
  model, one new test, three existing tests), `:feature-settings` (sync step
  view model + its test).
- No schema change, no migration, no new dependency, no new user-facing string,
  no new public entry point beyond one DAO query.

| # | Reported symptom | Root cause | Fix in one line |
|---|---|---|---|
| 1 | After saving the first provider, Home keeps showing **Set up a provider** until the app is restarted. | `ShellViewModel` reads the provider count **once** from a `flow { emit(...) }`, so `hasProvider` never updates in-process. | Add a Room `Flow<Int>` count to `ProviderDao` and consume it in `ShellViewModel`. |
| 2 | After the first library sync finishes, the step stays on "All stocked up" until **Continue** is tapped. | Nothing in `LibrarySyncStepViewModel` reacts to a terminal `DONE`; only the button's `continueIntoApp()` advances. | Auto-advance from the view model on terminal `DONE`, keeping the button as a fallback. |

## Repo context a fresh session needs

- `:app` shell state is `ShellViewModel.state` (`app/src/main/java/app/omnisonic/shell/ShellViewModel.kt`),
  a `combine(shellPreferences.preferences, providerCount)` → `ShellUiState`
  `stateIn(viewModelScope, Eagerly, null)`. `hasProvider` is derived from the
  count. It is passed through `OmnisonicApp.kt` (`OmnisonicShell` →
  `MainTabsPager(hasProvider = …)`) into
  `MainTabsPager.kt` → `HomePlaceholder(hasProvider, onSetup)`
  (`app/src/main/java/app/omnisonic/shell/ShellPlaceholders.kt`, button rendered
  only when `!hasProvider`).
- The first-run sync step lives in `:feature-settings`
  (`app.omnisonic.feature.settings.sync`), is destination-scoped in
  `ShellNavHost`'s `librarySync` composable, and reports completion with a
  one-shot `LibrarySyncStepEvent.Completed` that `LibrarySyncStepRoute` turns
  into `onComplete()` (which pops back to home).
- `LibrarySyncControl` (`core-data/.../sync/LibrarySyncControl.kt`) is the seam
  tests fake; `LibrarySyncStatusRepository` keeps the last progress value
  in memory, so a finished walk stays `DONE` for the rest of the process.
- Documentation conventions and tags are in `docs/omnisonic/00-template.md`.

---

## Issue 1 — Home still offers "Set up a provider" after the first save

### Symptom

Fresh install → provider setup form saves successfully → library sync step →
home. The **Set up a provider** button is still there. Killing and reopening the
app removes it. `progress/KNOWN_ISSUES.md` labels the post-restart body copy
alone (no button) as correct.

### Root cause `[VERIFIED]`

`app/src/main/java/app/omnisonic/shell/ShellViewModel.kt:33`:

```kotlin
private val providerCount = flow { emit(database.providerDao().count()) }
```

That flow emits exactly once, at collection. `ProviderDao.count()`
(`core-data/src/main/java/app/omnisonic/core/data/db/dao/ProviderDao.kt`, last
query) is a one-shot `suspend fun`. Nothing observes the `providers` table, so
the `combine` in `ShellViewModel` never re-runs after the setup form inserts the
row, and `ShellUiState.hasProvider` stays `false` for the life of the process.
`ShellViewModel` is app-scoped (created once in `OmnisonicApp`), so the stale
value survives navigation between setup, the sync step, and home.

The issue's suggested scope ("`:app` `ShellViewModel`") is **incomplete**: the
DAO lives in `:core-data`, so the fix spans both modules.

### Fix

1. `core-data/.../db/dao/ProviderDao.kt` — add a sibling observable query next
   to `count()` (import `kotlinx.coroutines.flow.Flow`):

   ```kotlin
   @Query("SELECT COUNT(*) FROM providers")
   fun countFlow(): Flow<Int>
   ```

2. `app/src/main/java/app/omnisonic/shell/ShellViewModel.kt` — consume it:

   ```kotlin
   private val providerCount = database.providerDao().countFlow()
   ```

   Delete the now-unused `kotlinx.coroutines.flow.flow` import. Keep the
   `combine` and `stateIn(..., Eagerly, null)` exactly as they are: Room's
   `Flow` emits the current count immediately and re-emits on any `providers`
   write, and `combine` already maps it into `hasProvider = count > 0`.

3. Leave `ProviderDao.count()` in place. `ProviderSetupService.save` uses it for
   `displayOrder`; that call must stay one-shot.

### Why this shape (alternatives considered)

- **Re-query on screen resume / navigation.** Rejected: Home is a tab, not a
  destination with a lifecycle hook, and the value would still be stale while
  the user sits on Home after adding a provider from settings.
- **`database.invalidationTrackerFlow("providers")` + `count()`.** Works, and
  `LibraryInvalidation` already uses the tracker for catalog tables. Rejected
  here because it needs a bespoke flow, a debounce decision and a second
  `count()` call for what Room generates from a `Flow` return type. Keep it as
  the fallback if a Flow-returning `COUNT(*)` query misbehaves on the pinned
  Room version (it does not; Room observes every table named in the query).
- **Caching the count in prefs at save time.** Rejected: duplicates DB state in
  DataStore and would still go stale on provider deletion.

### Edge cases

- **Provider deletion.** The Flow re-emits `0`, so the button returns. There is
  no delete UI yet, but this is why a live count is the right primitive.
- **Emission on start-up.** The first emission arrives with the initial query, so
  the existing `null` initial `ShellUiState` window is unchanged (shell already
  renders nothing for `null`).
- **No schema/migration impact.** Returning `Flow` from a `@Query` changes no
  database artifact.
- **Other `hasProvider` consumers.** Only `HomePlaceholder` today; any future
  consumer gets the live value for free.

### Tests

**New** `core-data/src/test/java/app/omnisonic/core/data/ProviderCountFlowTest.kt`
(Robolectric, extend `DatabaseTestBase`, model it on `LibraryInvalidationTest`):

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderCountFlowTest : DatabaseTestBase() {

    @Test
    fun `count flow starts at zero and follows inserts and deletes`() = runBlocking {
        db = OmnisonicDatabaseFactory(appContext).openInMemory()
        try {
            val counts = Channel<Int>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.IO) {
                providerDao.countFlow().collect { counts.send(it) }
            }
            assertThat(withTimeout(5_000) { counts.receive() }).isEqualTo(0)

            newProvider(id = 1)
            assertThat(withTimeout(5_000) { counts.receive() }).isEqualTo(1)

            providerDao.deleteById(1)
            assertThat(withTimeout(5_000) { counts.receive() }).isEqualTo(0)

            collector.cancelAndJoin()
        } finally {
            db.close()
        }
    }
}
```

Notes: `counts.receive()` on the first value guarantees the observer is
registered before the insert; a bare `delay(...)` would be flakier. Use
`runBlocking`/real time (Room's executor is not the test scheduler, so `runTest`
virtual time does not help). Close the DB in `finally`.

**New** `app/src/test/java/app/omnisonic/shell/ShellViewModelTest.kt` — the
regression that the count is wired live, not re-probed:

```kotlin
@Test
fun hasProviderFollowsProviderRows() = runBlocking {
    val db = OmnisonicDatabaseFactory(
        ApplicationProvider.getApplicationContext(),
    ).openInMemory()
    try {
        val vm = ShellViewModel(
            shellPreferences = tempShellStore("shell-vm"),
            database = db,
        )
        assertThat(withTimeout(5_000) { vm.state.first { it != null } }!!.hasProvider).isFalse()

        db.providerDao().upsert(<ProviderEntity with id = 1>)

        assertThat(withTimeout(5_000) { vm.state.first { it?.hasProvider == true } }!!.hasProvider).isTrue()
    } finally {
        db.close()
    }
}
```

Implementation notes: `viewModelScope` is `Dispatchers.Main`, so set
`Dispatchers.setMain(Dispatchers.Unconfined)` in `@Before` and `resetMain()` in
`@After` (this is how a plain, non-Compose VM test gets deterministic
collection). `tempShellStore(tag)` already exists in
`app/src/test/java/app/omnisonic/shell/LibrarySyncTestDoubles.kt`. Build the
`ProviderEntity` directly or reuse the shape from `DatabaseTestBase.newProvider`
(not visible to `:app`; copy the few required fields). If DataStore's read makes
the very first `state` emission slow, the `withTimeout` wrapper already covers
it. `:app` already depends on Room's runtime and Robolectric, and
`androidx.test:core` (for `ApplicationProvider`) is available to its tests.

**Do not** assert on the rendered button here; `ShellTabNavigationTest`
(`homeTabTapRestoresHomePane`) already covers the `hasProvider = false` render.

### Acceptance for issue 1

- Save the first provider in one session; Home shows no **Set up a provider**
  button without a restart.
- New DAO flow test and shell VM test pass.
- No schema version change, no migration, `ProviderDao.count()` still used by
  `ProviderSetupService`.

---

## Issue 2 — the sync step waits for a Continue tap after a successful walk

### Symptom

Fresh install → setup saved → sync step runs → phase reaches `DONE`, the step
shows "All stocked up" and a **Continue** button. The user must tap it to reach
home, even though the walk succeeded and the step is the only thing left in
onboarding. `progress/KNOWN_ISSUES.md` asks for auto-advance on completion.

### Current behavior `[VERIFIED]`

- `LibrarySyncStepViewModel` (`feature-settings/.../sync/LibrarySyncStepViewModel.kt`)
  resolves the provider in `init`, starts a full sync when the provider has no
  `lastFullSyncAt`, then collects `sync.progress` and copies it into `_state`.
  It never inspects `isComplete`; navigation happens only through
  `continueIntoApp()`, called from the Continue button.
- `isComplete` = `!isRunning && (phase == DONE || (phase == null && recordedComplete))`.
- `canContinue` = `loaded && (isComplete || isFailed || providerId == null)` —
  the failure and provider-less paths deliberately reuse the same button
  ("Continue anyway" copy for failures).
- `LibrarySyncStepRoute` (`LibrarySyncStepScreen.kt`) collects `viewModel.events`
  in a `LaunchedEffect(viewModel)` and calls `onComplete()`, which the shell
  maps to `popUpTo(librarySync) { inclusive }` → `home`.
- `_events` is `MutableSharedFlow(extraBufferCapacity = 1)` — `replay = 0`.

### Root cause

There is no reaction to the terminal state. `DONE` only changes what the screen
draws; it does not complete the onboarding step or emit `Completed`. The walk is
owned by the launcher, so nothing else notices either.

### Recommended design

**Auto-advance in the view model** (not a `LaunchedEffect(state.isComplete)` in
the route): the step is an onboarding completion, so it belongs with the
view model's existing `continueIntoApp()` contract and is testable in
`:feature-settings` without Compose. Keep the Continue button rendered as a
fallback; it will normally be visible for at most one frame.

Edits:

1. `LibrarySyncStepViewModel` — add a private helper:

   ```kotlin
   /** Terminal success finishes the step for the user; failures still ask. */
   private fun maybeAutoAdvance() {
       if (_state.value.isComplete) continueIntoApp()
   }
   ```

   Call it in two places:
   - at the end of the `init` block's first state update (the
     `loaded/providerId/recordedComplete` update), so a provider whose walk
     already finished before this screen opened advances immediately. Do **not**
     only call it inside the `progress.collect` block: on that path `progress`
     is `null` and `isComplete` is true via `recordedComplete`.
   - after the `_state.update` inside `sync.progress.collect`, but only on the
     accepted path (the existing provider-id filter already returns early for
     foreign progress).

2. Make the completion event replayable: change

   ```kotlin
   private val _events = MutableSharedFlow<LibrarySyncStepEvent>(extraBufferCapacity = 1)
   ```

   to `MutableSharedFlow<LibrarySyncStepEvent>(replay = 1)`.

   Why this is required, not cosmetic: `continueIntoApp()` sets
   `continuing = true` synchronously before launching
   `completeOnboardingStep(...)` + `tryEmit(...)`. With `replay = 0`, an event
   emitted before `LibrarySyncStepRoute`'s `LaunchedEffect` subscribes is
   **dropped** (SharedFlow keeps nothing for absent subscribers). That happens
   in `init` when the provider already has `lastFullSyncAt`, and in tests whose
   fake `providers()` returns without suspending. The route then never navigates,
   `continuing` is already `true`, and the fallback Continue button calls
   `continueIntoApp()` only to be swallowed by the guard — an inert button on a
   screen with no other exit. Replaying the single `Completed` event closes that
   hole; the event is consumed by the one route instance, and re-entering the
   step creates a new destination-scoped view model, so there is no double
   navigation.

   If the team dislikes replaying events, the alternative is to drop the
   auto-advance from the view model and instead have `LibrarySyncStepRoute` call
   `viewModel.continueIntoApp()` from `LaunchedEffect(state.isComplete)`; state
   is a `StateFlow`, so a late subscriber still sees it. That moves the
   regression coverage from a plain VM test to a Compose test in `:app`, which
   is why it is the second choice.

3. Do **not** change `canContinue` or the failure/provider-less copy. The
   auto-advance keys off `isComplete` only, so `FAILED`, `BLOCKED`, `SKIPPED`
   and the no-provider case still wait for the user.

4. Keep `LibrarySyncStepScreen` as is (the `isComplete` result line and the
   Continue button stay). Auto-advance is immediate — no artificial delay. The
   completion confirmation is the home screen itself; adding a sleep would need
   a UX token and a scheduler-aware test for no functional gain. This is the one
   product judgment in the fix; if the owner wants the "All stocked up" beat
   visible, add a single named delay constant in the view model and assert with
   the test scheduler.

### Edge cases / pitfalls

- **Already-complete provider at open.** `isComplete` is true before any
  progress emission (`recordedComplete`); the `init` call site covers it.
- **Late subscription.** See the `replay = 1` rationale above. This is the
  pitfall most likely to ship a dead Continue button.
- **Foreign progress.** The collect block filters `progress.providerId != state.providerId`;
  keep the auto-advance inside the accepted path so a DONE for another
  provider cannot complete onboarding.
- **Idempotence.** `continueIntoApp()`'s `continuing` guard and
  `completeOnboardingStep` are both idempotent; the helper can therefore be
  called on every state change without extra bookkeeping.
- **Backgrounded app.** The view model collects on `viewModelScope` regardless
  of UI lifecycle, so a walk that finishes while the app is in the background
  completes the step; the replayed event is delivered when the route's
  `LaunchedEffect` resubscribes.
- **Don't auto-advance on `SKIPPED`/`BLOCKED`.** Those are user decisions
  ("Continue anyway"), and the issue asks only for completion.
- **`loaded == false`.** `isComplete` is false while `!loaded`; no early jump
  before the provider is resolved.

### Tests — `:feature-settings`

Extend `feature-settings/src/test/java/app/omnisonic/feature/settings/sync/LibrarySyncStepViewModelTest.kt`
(existing 8 tests; keep them green):

1. **Update** `completion enables Continue` → assert the stored onboarding step
   is completed and `Completed` is emitted *without* calling
   `continueIntoApp()`; rename to `terminal completion auto-advances`.
2. **Update** `a recorded complete sync is adopted without starting a walk` →
   additionally assert `Completed` is emitted and `fullSyncCalls` is empty.
3. **New** `failure does not auto-advance` — publish
   `FAILED` via `FakeLibrarySyncControl.publish`; `runCurrent()`; assert no
   events and `canContinue` is still true (manual Continue-anyway path).
4. **New** `a provider-less install does not auto-advance` — empty
   `providers()`, no progress; assert no events, `canContinue` true.
5. **New** `completion followed by a manual continue emits once` — publish
   `DONE`, then call `continueIntoApp()`; assert exactly one `Completed`
   (guards against double navigation now that both paths exist).
6. **New** `completion emitted before a collector subscribes is still delivered` —
   construct the view model with `initialProgress = terminalProgress(DONE)` so
   the `UnconfinedTestDispatcher` main runs `init` and emits during
   construction; subscribe to `events` afterwards, `runCurrent()`, assert the
   `Completed` event arrives. This is the deterministic pre-fix failure for the
   `replay = 1` change (with `replay = 0` the list stays empty); it is stronger
   than the Compose route test, which can win the subscription race by luck.

When adding the event assertions, subscribe to `events` immediately after
constructing the view model (before `publish`) in tests 1, 3–5 so they prove the
live path rather than the replay cache.

The file is JUnit 5 (`@BeforeEach`, `runTest`, `UnconfinedTestDispatcher`); reuse
its local `FakeLibrarySyncControl`, `provider(...)`, `terminalProgress(...)`,
`shellStore()` and `runCurrent()` helpers. One existing test doubles as a
replay regression: `continue completes the onboarding step and emits completion
once` builds the view model with an initial `DONE` progress and subscribes to
`events` **after** construction, so with `replay = 0` it would see nothing. It
must stay green as written (its two explicit `continueIntoApp()` calls become
no-ops after the auto-advance).

### Tests — `:app` (behavior and regressions)

The three existing files are in `app/src/test/java/app/omnisonic/shell/`;
the last item is a new file there.

1. `ProviderSetupNavigationTest.firstRunWalksSetupThenSyncThenHome` — becomes
   the end-to-end auto-advance regression. **Do not** keep the completed fake:
   with a control already in `DONE`, the step view model auto-advances during
   composition, so `awaitRoute(nav, LIBRARY_SYNC)` (100 ms polling) will almost
   certainly miss the route and time out. Instead:
   - give the fake a provider **without** `lastFullSyncAt` and no initial
     progress (step shows `Preparing`/walk and stays);
   - after `awaitRoute(nav, LIBRARY_SYNC)`, `syncControl.publish(syncCompletedProgress())`;
   - remove `onNodeWithText("Continue").performClick()` and
     `awaitRoute(nav, ShellRoutes.HOME)` directly;
   - rename to `firstRunWalksSetupThenSyncThenAutoAdvancesHome`.
   Add a `syncPendingProvider()` helper in `LibrarySyncTestDoubles.kt`
   (`lastFullSyncAt = null`, leave `lastResult` at its `NEVER` default) if none
   exists.
2. `ProviderSetupNavigationTest.syncStepViewModelIsOnlyCreatedWhenTheRouteOpens` —
   currently relies on the default double (completed progress + done provider)
   and asserts "Continue" exists. With auto-advance that navigates home
   immediately. Fix by keeping the completed fake **only if** you assert the
   route is left (i.e. that auto-advance fired), which duplicates test 1;
   preferred: give this test a pending/incomplete control so the route stays,
   and replace the "Continue" assertion with
   `assertThat(nav.currentDestination?.route).isEqualTo(ShellRoutes.LIBRARY_SYNC)`.
   The test's purpose is view-model creation timing, not completion.
3. `ShellNavHostTest.everyRouteTableRouteResolves` — navigates to every route in
   `ShellRoutes.ALL`, including `librarySync`, and asserts the current route
   after `waitForIdle`. It supplies `testLibrarySyncStepViewModel()`'s default
   (completed) double, so auto-advance would pop the route and break the
   assertion. This is the strongest argument for changing the **default** in
   `LibrarySyncTestDoubles.testLibrarySyncStepViewModel()` to an
   incomplete/pending control (no progress, provider without `lastFullSyncAt`,
   which renders `Preparing` and never auto-advances). Tests that need the
   completed state pass `FakeLibrarySyncControl(initialProgress = syncCompletedProgress(), initialProviders = listOf(syncDoneProvider()))`
   explicitly. Check every call site of `testLibrarySyncStepViewModel()` after
   the change (`ShellNavHostTest`, `ShellTabNavigationTest`, `MainTabsPagerTest`,
   `MiniPlayerBarTest`, `ProviderSetupNavigationTest`); only the ones that open
   the route care, but the default should be the inert one.
4. **New** `app/src/test/java/app/omnisonic/shell/LibrarySyncStepRouteTest.kt` —
   wiring check for the composable: construct
   `LibrarySyncStepViewModel(FakeLibrarySyncControl(initialProgress = null, initialProviders = listOf(syncDoneProvider())), tempShellStore(...))`
   **before** `setContent`, render `LibrarySyncStepRoute(viewModel, onComplete = { completed = true })`,
   `waitForIdle()`, assert `completed` is true. Keep it, but do not rely on it
   as the late-subscriber regression (Robolectric may run the view-model
   `init` after the `LaunchedEffect` subscribes); test 6 in the
   `:feature-settings` list is the deterministic one for that.

### Acceptance for issue 2

- A fresh install reaches home after the walk hits `DONE` with no tap.
- A failed/blocked/skipped walk and a provider-less install still require the
  explicit button.
- The `Completed` event survives being emitted before the route subscribes.
- No double navigation, no `IllegalStateException` from popping a destination
  twice, and the manual Continue path still works.
- `LibrarySyncStepViewModelTest` covers auto + manual + failure + no-provider.

---

## Build and test command sheet

Per-module first (fast feedback), then the shell build:

```bash
./gradlew :core-data:testDebugUnitTest --tests '*ProviderCountFlowTest*'
./gradlew :feature-settings:testDebugUnitTest --tests '*LibrarySyncStepViewModelTest*'
./gradlew :app:testDebugUnitTest --tests '*ShellViewModelTest*' \
    --tests '*ProviderSetupNavigationTest*' --tests '*ShellNavHostTest*' \
    --tests '*LibrarySyncStepRouteTest*'
./gradlew :core-data:testDebugUnitTest :feature-settings:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Before finishing, per the project ground rules: naming diff for the new DAO
symbol (`countFlow`) and the banned-name scan on every touched file:

```bash
# reads the pattern from `git config omnisonic.bannedPatterns`; CI supplies
# OMNISONIC_BANNED_PATTERNS instead
scripts/check-banned-names.sh <touched files>
```

No new user-facing copy is added; if any string is added, it must be fresh
Omnisonic copy and go through the same naming review.

## Existing guards that might look relevant but are not

- `DaoSqlBoundedListTest` scans parameterized `IN` lists only; a `COUNT(*)` Flow
  is outside it.
- `ProfileScopeQueryTest` excludes `providers`; no change needed.
- `DatabaseTestBase.newProvider` is the row builder for the `:core-data` test;
  it is not visible from `:app` (build the entity directly there).
- `ProviderDao.count()` stays for `ProviderSetupService.save`'s `displayOrder`.

## Documentation to update with the fix

Do these in the same change; they are part of the fix's definition of done.

1. `progress/KNOWN_ISSUES.md` — remove both entries from *Fresh-install /
   onboarding flow* (the file's convention is to delete resolved issues). If the
   section becomes empty, drop the heading.
2. `docs/omnisonic/providers/library-sync-ux.md` — update the first-run sync
   step paragraph: a terminal `DONE` now completes the onboarding step and
   advances to home on its own; failure/skip and provider-less installs still
   show the explicit button; completion is delivered through a replayed
   `Completed` event. Bump the Date and the confidence line to cite the new
   tests.
3. `docs/omnisonic/ui/provider-setup.md` — its *Entry and exit* section still
   claims "On completion the shell navigates to `home`", which has been wrong
   since the sync step landed (first run goes setup → `librarySync` → home;
   only the settings path goes straight home). Correct that sentence while
   updating the same flow, and add one line that `hasProvider` is a live Room
   count so the Home empty state clears as soon as the row is written.
4. `docs/omnisonic/README.md` — add this file to the subject map (row
   `**Onboarding**`).
5. `progress/STATE.md` and `progress/CHANGELOG.md` — record the fix as a small
   task entry under the current phase (do not renumber or start task 8).
6. `.agents/memory/findings.md` — record the two durable lessons:
   *"a one-shot `flow { emit(dao.query()) }` used for shell state never updates;
   use a Room `Flow` for anything the shell gates on"* and *"a `replay = 0`
   SharedFlow plus a `continuing` guard turns a lost completion event into an
   inert fallback button — replay terminal events or drive completion from
   state"*.
7. `.agents/plans/phase-5-tasks.md` — register the work as a new entry (for
   example `7b`) **above** task 8, and tick it when done. Task 8 is untouched.

No entry in `decisions.md` is needed: this restores intended behavior, it does
not pick a new architecture. If the owner chooses the delayed-beat variant, that
is a UX decision worth a one-line record.

## Session checklist (order matters)

1. Read `progress/KNOWN_ISSUES.md` (this section), then this file.
2. Issue 1: add `ProviderDao.countFlow()`, wire `ShellViewModel`, add the two
   tests, run `:core-data` and `:app` targeted tests.
3. Issue 2: add `maybeAutoAdvance()` + both call sites + `replay = 1`; add the
   five `:feature-settings` tests; fix the three `:app` tests and add the route
   test; change the `testLibrarySyncStepViewModel()` default to the inert fake.
4. Update the docs listed above, including the stale `ui/provider-setup.md`
   sentence.
5. Full `./gradlew :core-data:testDebugUnitTest :feature-settings:testDebugUnitTest :app:testDebugUnitTest`
   and `./gradlew :app:assembleDebug`; banned-name scan on touched files.
6. Report file:line of each change, tests added/updated, commands run, and any
   check that could not run. Do not start task 8.

## Copy-paste prompt for the new session

> Fix the two *Fresh-install / onboarding flow* issues in
> `progress/KNOWN_ISSUES.md` using the plan in
> `docs/omnisonic/onboarding/fresh-install-fixes.md`. Follow it in order:
> (1) make `ShellViewModel.hasProvider` a live Room provider-count flow and add
> the DAO + view-model tests; (2) auto-advance the first-run sync step on
> terminal `DONE` from `LibrarySyncStepViewModel`, keeping the manual button for
> failure and provider-less paths, and make the `Completed` event survive a late
> subscriber. Update the three affected `:app` shell tests and the
> `testLibrarySyncStepViewModel()` default as described in the document, then
> update the listed docs. Do not touch task 8 and do not start the Phase 5
> wrap-up.
