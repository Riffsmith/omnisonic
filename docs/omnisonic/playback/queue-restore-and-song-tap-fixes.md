# Fix brief — launch-time queue restore + Library → Songs tap

Status: **Defect A fixed 2026-11-12 (task 7c, device acceptance [UNKNOWN]);
Defect B fixed 2026-11-12 (task 7d, device acceptance [UNKNOWN]).** This is the authoritative
plan for the two open playback defects in `progress/KNOWN_ISSUES.md` §Playback
(the third item there — stored resume points — is a Phase 6 feature and stays
out of scope). Written after a source review of `:playback`, `:playback-api`,
`:feature-library` and `:app`; every mechanism below is `[VERIFIED]` by reading
the code named in the step, nothing here is device-verified yet.

Confidence tags follow `docs/omnisonic/README.md`: `[VERIFIED]`,
`[INFERRED]`, `[UNKNOWN]`.

---

## Registration (do this in the fixing session)

Two independent defects, two sessions. Insert both lines **above task 8** in
`.agents/plans/phase-5-tasks.md` (the 7b precedent: fix work registers as its
own task and does not delay the phase wrap-up), then claim only one:

- `7c. (fix) Launch-time queue restore — restore the persisted snapshot once
  per process from the app's playback initialisation so the queue and
  mini-player come back after a relaunch; idle restore must never auto-play;
  the exactly-once restore guard this adds is the prerequisite for 7e (media
  notification). Scope: :playback + :app + docs. Device-gated: kill → relaunch
  feel [UNKNOWN].`
- `7d. (fix) Library → Songs row tap plays the song — add a click action that
  queues the loaded section window at the tapped row (mirroring
  DetailViewModel.playFrom: skip unplayable rows, re-anchor the start index).
  Scope: :feature-library (+ :app test double) + docs. Device-gated: tap feel
  [UNKNOWN].`

Read first: `docs/omnisonic/playback/controller-and-events.md`,
`playback/service-and-notifications.md`, `playback/resume-and-bookmarks.md`
(D-18), `playback/media-notification-fix.md` (7e — same launch seam, see the
interaction section below), `ui/gesture-contract.md` (only if a non-tap
gesture is added), `ui/library-browse.md`, `ui/detail-screens.md`.

---

## Defect A — queue and mini-player are not restored after a relaunch

### Symptom and evidence `[VERIFIED on device, recorded in KNOWN_ISSUES]`

Play something, `am force-stop app.omnisonic`, relaunch: Home shows no
mini-player and the queue is empty, even though `queue_state` still holds
`playIndex = 4`, `positionMs = 46,564`. Before the kill the now-playing screen
showed 0:50. A `KEYCODE_MEDIA_PLAY` injection did not start playback either.

### Root cause chain `[VERIFIED by source reading]`

1. `PlaybackControllerImpl.restoreQueue()` (`playback/.../controller/PlaybackControllerImpl.kt:277`)
   applies the snapshot without playing: it sets `playIntent = false`,
   `idle = false`, `engineLoaded = false`, publishes the restored entry and
   position. `PlaybackControllerImplTest` pins that no engine start happens
   and that a later `resume()` replays at the saved position.
2. Its only caller is `OmniPlaybackService.onCreate`
   (`playback/.../service/OmniPlaybackService.kt:78`, via
   `restoreQueueWithin(controller, 2_000)`), i.e. only when the system starts
   the service.
3. **Nothing starts or binds that service at app launch.** `OmnisonicApplication`
   (`app/src/main/java/app/omnisonic/OmnisonicApplication.kt:21`) only resumes
   incomplete library sync; a grep over `app/`, `playback/` and `playback-api/`
   main sources finds no `startService`/`startForegroundService`/`bindService`
   /`MediaController`. The UI talks to the in-process Hilt controller, so the
   process can launch, render the shell and never apply the snapshot.
4. The controller does exist at launch: the shell hoists `MiniPlayerViewModel`
   (`app/src/main/java/app/omnisonic/shell/OmnisonicApp.kt:84`), which injects
   `PlaybackController`, so the playback stack is constructed during first
   composition. Only the restore call is missing.

The mechanism is orthogonal to D-18/task 6b (stream starts at zero); no
regression there.

### Fix (recommended)

Restore the snapshot once from the app's playback initialisation, in-process —
do **not** start the media service just to read a row.

1. **Add an exactly-once guard in `PlaybackControllerImpl`.** Restore is
   applied at most once per process, no matter who calls first
   (app init vs. service start):

   ```kotlin
   private var restoreAttempted = false

   override suspend fun restoreQueue(): Boolean {
       if (released || restoreAttempted) return false
       restoreAttempted = true
       ...
   }
   ```

   Set the flag *before* the first suspension. Both callers run on
   `Dispatchers.Main.immediate` (engine scope), so a flag set before
   `persistence.restoreInto` also covers the window where the service starts
   while the app-init restore is suspended on the Room read. If the first
   attempt finds no snapshot (or times out), the rest of the process does not
   retry — there is nothing to restore that could appear later without a queue
   already existing.

2. **Add a small launch seam in `:playback`.** e.g.
   `playback/.../startup/PlaybackLaunchRestore.kt`:

   ```kotlin
   class PlaybackLaunchRestore(
       private val controller: PlaybackController,
       private val scope: CoroutineScope,        // @PlaybackEngineScope
       private val timeoutMs: Long = 2_000L,     // same bound as the service
   ) {
       fun restore() {
           scope.launch { withTimeoutOrNull(timeoutMs) { controller.restoreQueue() } }
       }
   }
   ```

   Reuse `restoreQueueWithin` (internal to `:playback`) rather than duplicating
   the bound, or move that helper next to the new class. Provide it as a
   `@Singleton` in `PlaybackWiringModule` with `@PlaybackEngineScope
   CoroutineScope` (restore must run on the engine thread — the queue machine
   commands the player inline; see the `PlaybackController.restoreQueue` KDoc).

3. **Trigger it from `OmnisonicApplication.onCreate`,** mirroring the existing
   `librarySyncLauncher.get().resumeIncomplete()` precedent:

   ```kotlin
   @Inject lateinit var playbackLaunchRestore: Provider<PlaybackLaunchRestore>
   // onCreate(): playbackLaunchRestore.get().restore()
   ```

   Application scope (not the root composable / MainActivity) so every
   UI launch is covered and the call site is unit-testable; the controller is
   a singleton, so this adds no second stack.

4. **Do not add auto-play or focus.** `restoreQueue()` already leaves
   `playIntent = false` and requests no audio focus; the shell renders the
   mini-player because `MiniPlayerUiState.isVisible` is
   `resolveNowPlayingEntry(player, queue) != null` (queue entry is restored,
   `isPlaying = false`). Pressing play then goes through `resumeInternal()` →
   `engineLoaded == false` → `QueuePlayer.replayCurrent()` at the pending
   restore position.

### Rejected alternatives

- **Start/bind the service as the restore trigger.** Works but is heavier for
  this defect: a permanently present media session/notification host, Android
  12+ background-start rules to reason about, and a second owner of the restore
  policy. The service path stays as the fallback for system-initiated starts.
  (7e will bind a session-host `MediaController` for its own reasons — see the
  interaction section; the guard keeps both paths single-apply.)
- **Restore from `MiniPlayerViewModel.init` / a `LaunchedEffect` in the
  shell.** Hidden responsibility on a UI VM, and the composition/test host must
  exist before the queue comes back. The Application seam is explicit and
  guarded.
- **Drop the Phase 4 acceptance claim instead.** Rejected by the user-facing
  expectation recorded in `open-questions.md`.

### Implementation checklist (**done 2026-11-12**)

- [x] `PlaybackControllerImpl`: `restoreAttempted` guard; keep the existing
      "no snapshot → false" behaviour and the `released` guard. Also refuses
      a restore once the in-memory queue holds entries (a play may have won
      the race).
- [x] `PlaybackLaunchRestore` + Hilt provider in `PlaybackWiringModule`.
- [x] `OmnisonicApplication` injection + call in `onCreate`.
- [x] Confirm `QueuePersistenceCoordinator` ordering is unchanged: the observer
      starts in `PlaybackControllerImpl.init`, the initial empty state is
      ignored (`entries.isEmpty() && !hasQueue`), and `restoreInto` sets
      `hasQueue`/`latest`/`lastSaved` so the restored state is not re-saved.
      Do not "fix" this by observing before/after differently.
- [ ] `release()` needs no reset (process teardown); do not clear the flag on
      service destroy — the singleton outlives the service in the same process.

### Tests

- `:playback` `PlaybackControllerImplTest` (the existing
  `restoreQueue applies the snapshot without playing and resume replays it`
  and `restoreQueue reports nothing when no snapshot exists` must stay green):
  - second `restoreQueue()` call returns `false` and does not re-apply or
    re-emit `TrackChanged`;
  - "already playing" is still untouched (a restore after `play()` must not
    clobber the live queue; the flag covers this, since `play()` does not
    consume it — add the explicit assertion).
- `:playback` new `PlaybackLaunchRestoreTest` (plain JVM, fake controller):
  calls `restoreQueue()` once; a controller that delays past the bound does not
  block and the call returns promptly (mirror `ServiceQueueRestoreTest`).
- Keep `ServiceQueueRestoreTest` green; the service still restores when it is
  the first thing to start in a process.
- `:app` may gain a Robolectric check only if cheap — not a gate.

### Device acceptance (task 7c)

Using the RMX3085 recipe in `.agents/plans/next-session-prompt.md`:

1. Play a track, let it run past 10 s (position publication is 5 s-granular),
   pause (flushes the snapshot), note the position.
2. `adb shell am force-stop app.omnisonic` then launch from the launcher.
3. Expect the mini-player back on every tab with the same track, paused, at the
   saved position (±5 s if the kill was mid-play: force-stop runs no lifecycle
   flush); opening now-playing shows the same elapsed/remaining.
4. Press play → audio resumes from that position, not 0, and focus is taken
   only at that moment.
5. Optional DB check: `adb exec-out run-as app.omnisonic cat databases/omnisonic.db`
   (WAL/SHM too) and confirm `queue_state.playIndex`/`positionMs` unchanged.
6. Also verify the service path still restores: kill the app, then start it via
   the media button/notification path (or `adb shell am startservice` if
   permitted) with no UI — the restored queue must not double-apply.

`[UNKNOWN]`: cold-start cost of constructing the playback stack in
`Application.onCreate`. The shell already constructs it during first
composition; if instrumentation shows a regression, move the trigger to the
first composition behind the same seam — do not change the guard.

---

## Interaction with the media-notification fix (task 7e)

`docs/omnisonic/playback/media-notification-fix.md` fixes the missing media
notification with a process-lifetime session-host `MediaController` bound at
app init. That bind creates `OmniPlaybackService`, whose `onCreate` restore
(`restoreQueueWithin`) is therefore a **second live path** to the same
snapshot.

- The `restoreAttempted` guard from step 1 is a **prerequisite for 7e**, not
  an optimisation. With both paths live, the app-init restore and the service
  restore would otherwise race on `Main.immediate` and apply the snapshot
  twice (duplicate `TrackChanged`, `restoreInto` overwriting its own
  bookkeeping). Land the guard first, or in the same session as 7e.
- If 7e's launch-connect lands first, the queue still returns after a relaunch
  through the service path, and this brief's `PlaybackLaunchRestore` seam
  becomes redundant. Default: keep it as a guarded safety net (it makes the
  behaviour independent of the notification fix's own bug surface); do not
  drop it without confirming with the user.
- Whichever path wins, the user-visible acceptance is identical: a relaunch
  shows the restored **paused** queue + mini-player at the saved position.
  Record that check once, in the session that lands it; the other task's log
  says `[UNKNOWN]` until re-checked rather than claiming it twice.

---

## Defect B — tapping a song in Library → Songs does nothing

### Symptom and evidence `[VERIFIED on device, recorded in KNOWN_ISSUES]`

Library → Songs lists the library, but a tap produces no ripple, no playback
and no navigation.

### Root cause chain `[VERIFIED by source reading]`

1. `SongListBody` (`feature-library/.../ui/LibrarySectionScreen.kt:324`)
   renders `SongRow` with thumb/title/artist/duration and **no `clickable`**;
   it takes only `content` + `onLoadMore`.
2. `SongRow` (`LibrarySectionScreen.kt:353`) is a plain `Row` with padding.
3. The `LibrarySection.SONGS` branch of `LibrarySectionRoute`
   (`feature-library/.../ui/LibrarySectionRoute.kt:94-107`) passes no callback,
   while the album/artist/genre/playlist branches all hand `onClick` into
   `CoverGridBody` → `CoverTile`'s `Modifier.clickable`.
4. No other route exposes a song action, so outside a detail screen a song is
   currently unplayable.

The detail screens already implement the intended semantics:
`DetailViewModel.playFrom(rowIndex)`
(`feature-library/.../detail/DetailViewModel.kt:111`) snapshots the **loaded
window**, maps rows through `SongEntity.toQueueEntry()`
(`feature-library/.../detail/QueueEntries.kt:12`, drops rows with a null
`externalId`, which the stream resolver cannot resolve), re-anchors the start
index by `externalId`, then `controller.play(entries, start)`.

### Fix (recommended)

Put the action where the list already lives: `LibraryBrowseViewModel` owns the
songs pager and is shell-hoisted, so no `:app` wiring change is needed.

1. **`LibraryBrowseViewModel`** (`feature-library/.../browse/LibraryBrowseViewModel.kt`):
   inject `PlaybackController` as a second constructor parameter and add

   ```kotlin
   fun playFrom(rowIndex: Int) {
       val content = songs.value as? BrowseUiState.Content ?: return
       val song = content.items.getOrNull(rowIndex) ?: return
       val entries = content.items.mapNotNull { it.toQueueEntry() }
       val start = entries.indexOfFirst { it.externalId == song.externalId }
           .takeIf { it >= 0 } ?: return
       viewModelScope.launch { controller.play(entries, start) }
   }
   ```

   Same rules as `DetailViewModel`: only the loaded window is queued (paging
   beyond it stays a later-phase item), unresolvable rows are skipped, the
   tapped row is found by identity, and a no-content/no-match call is a no-op.
   Move `QueueEntries.kt` to a shared package
   (`feature/library/playback/`) so `browse` does not import from `detail`;
   behaviour is unchanged (`internal` is module-wide either way).

2. **`LibrarySectionRoute`** SONGS branch: pass
   `onSongClick = viewModel::playFrom` into `SongListBody` (hoist it alongside
   the other branches' `onOpen*` callbacks).

3. **`SongListBody` / `SongRow`**: add `onSongClick: (Int) -> Unit`; render with
   `itemsIndexed(content.items, key = { _, song -> song.id })` and give the
   whole row `Modifier.clickable { onSongClick(index) }` (the ripple the user
   expects; `CoverTile` already sets the precedent, and `Row` is already
   `fillMaxWidth` with `heightIn(min = listRowMinHeight)`). Do not make the
   thumb a separate target. If an `onClickLabel` is added for a11y it needs a
   string resource, not a literal.

4. **Gesture contract**: tap only, no long-press/appbar action — so no visible
   twin is required (`ui/gesture-contract.md`). Explicitly out of scope: play
   next, shuffle from the list, favorite, download, long-press menus, a song
   detail screen.

5. **Nothing to change in `:app` main code**: `ShellNavHost` already passes the
   shared `browseViewModel` into `LibrarySectionRoute`. Only the test double
   needs the new constructor argument.

### Test updates and new coverage

- `feature-library` `LibraryBrowseViewModelTest` constructs
  `LibraryBrowseViewModel(source)` at ~10 sites: add `FakePlaybackController()`
  (already exists in `PlayerScreenTestDoubles.kt`), ideally through a local
  `viewModel(source)` helper so the fake is in one place.
- New VM tests (copy `DetailViewModelTest` lines 125–152):
  - `playFrom` queues the loaded songs and starts at the tapped row;
  - rows with `externalId = null` are skipped and the start index re-anchors;
  - `playFrom` before content / out-of-range is a no-op (no `plays`);
  - after `loadMore`, the queue contains the appended window too.
- `:app` `LibraryBrowseTestDoubles.testBrowseViewModel()` needs a controller:
  use the existing `NoopPlaybackController` (`DetailTestDoubles.kt`).
- One UI-level check for the actual regression (the row had no click action):
  add a small Compose test in `:app` (like `DetailScreenRenderTest`) with a
  browse source that returns a songs page and a recording controller; assert
  the song title node `assertHasClickAction()` and that tapping calls
  `play` with the tapped index. Keep it to one song page.

### Device acceptance (task 7d)

1. Library → Songs, tap a row: ripple on the row, audio starts for that song,
   mini-player appears with title/artist, the tapped row is the queue head.
2. Tap a different row: playback switches, queue starts there and keeps the
   following loaded rows.
3. Open album/artist/genre/playlist detail play actions and the queue screen —
   unchanged (same `playFrom` semantics, separate call site).
4. Scroll to load a second page, then tap a row in the appended window: its
   loaded index is the start index (no jump to the first page).

---

## Documentation and state to update (fixing session)

Defect A: `playback/controller-and-events.md` (restore call sites + the
exactly-once rule), `playback/service-and-notifications.md` (service restore is
now the fallback, not the only path), `.agents/memory/open-questions.md`
(remove the launch-time restore item), `progress/KNOWN_ISSUES.md` (delete the
entry).

Defect B: `ui/library-browse.md` (song row action, queue = loaded window,
unplayable-row rule), `ui/detail-screens.md` (cross-reference the shared
`playFrom` semantics), `progress/KNOWN_ISSUES.md` (delete the entry).

Both: the new task line ticked in `.agents/plans/phase-5-tasks.md`,
`progress/STATE.md`, `progress/CHANGELOG.md`, a
`.agents/memory/findings.md` entry (what was ruled out, the guard rule, the
constructor/test-double fallout), this README row advanced to "fixed", and
device-gated items tagged `[UNKNOWN]` until the phone session.

## Decisions the fixing session must confirm (with the user if they disagree)

- Restore-at-launch shows a **paused** queue and mini-player at the saved
  position, and only a play tap starts audio/focus. No "resume automatically"
  behaviour is introduced.
- Exactly-once-per-process is the guard (not "restore whenever asked"); the
  service keeps calling `restoreQueue()` but becomes a no-op after launch.
- Songs queued from the section list are the **loaded window only**, matching
  detail screens; queueing the whole section is not part of this fix.
- A force-stopped play can restore up to ~5 s behind (position publication
  granularity; force-stop performs no flush). Acceptable; verified on device,
  not "fixed" by adding a kill handler.
