# Fix brief — media notification never appears (session-host controller)

Status: **implemented in task 7e (2026-11-12), then corrected.** Layer 1 below
(the missing controller bind) was real but not sufficient: after the bind
landed, the notification still never appeared, because the session's
`onConnect` granted controllers an empty player-command set (see
[Root cause — layer 3](#root-cause--layer-3-the-empty-command-grant-2026-11-12)).
Both fixes are in and verified on the RMX3085.

Authoritative plan for the "no notification while playing" defect in
`progress/KNOWN_ISSUES.md` §Playback. Written after a source review of `:app` /
`:playback` / `playback-api` **and** a decompile of the pinned media3 session
library (method below); every mechanism is tagged per
`docs/omnisonic/README.md`.

---

## Registration (do this in the fixing session)

Insert this line **above task 8** in `.agents/plans/phase-5-tasks.md` (the 7b
precedent: fix work registers as its own task and does not delay the phase
wrap-up), then claim it:

- `7e. (fix) Media notification never appears — bind a process-lifetime
  session-host MediaController at app init so OmniPlaybackService's session
  registers with media3's notification machinery (authoritative plan:
  docs/omnisonic/playback/media-notification-fix.md). Scope: :playback + :app
  + docs. Device-gated: notification/lock-screen/FGS feel [UNKNOWN].`

Task 8 (phase wrap-up) is NOT part of this task. Do not roll other work into
it and do not re-order it.

Read first: `playback/service-and-notifications.md`,
`playback/controller-and-events.md`, `playback/queue-restore-and-song-tap-fixes.md`
(defect A — the same root-cause family), `progress/STATE.md` T11 media3 facts.

---

## Symptom and evidence

Audio plays (mini-player, now-playing screen, queue all live), but the
notification shade and lock screen show nothing — no media card, no
notification — even though the user granted `POST_NOTIFICATIONS` to the app
manually before checking (RMX3085, 2026-11-11 user report).

Corroborating repo facts [VERIFIED]: `progress/KNOWN_ISSUES.md` already
records that no playback service runs and no notification exists during the
task-6b device check; the wakelock is held by the controller, not a service.

## Root cause chain — layer 1, the app never talks to the session service [VERIFIED by source]

1. The UI drives the **in-process Hilt controller**
   (`PlaybackControllerImpl` → `OmniPlayerImpl`/ExoPlayer). Playback never
   touches the media session.
2. `OmniPlaybackService.onCreate`
   (`playback/.../service/OmniPlaybackService.kt:70-86`) builds the
   `MediaLibrarySession` on the exposed player facade and registers
   `PlaybackNotificationProvider` — but only if the service is created, and
   **nothing ever creates it**: a grep over all main sources finds zero
   `SessionToken` / `MediaController` / `startService` / `startForegroundService`
   / `bindService` call sites; the only references are tests
   (`app/src/test/.../OmniPlaybackServiceTest.kt`,
   `playback/src/test/.../OmniForwardingPlayerTest.kt`).
   `OmnisonicApplication.onCreate`
   (`app/src/main/java/app/omnisonic/OmnisonicApplication.kt:21-24`) only
   resumes library sync. Same root-cause family as the queue-restore defect
   (`queue-restore-and-song-tap-fixes.md` defect A).
3. Because no controller ever binds and the system never learns about the
   session, no media-button intent is ever routed here either — the service
   is dead weight, and the notification (which only the service can post) is
   never created. Permission is irrelevant when nothing posts.

## Root cause chain — layer 2, media3 1.11.1 notification mechanics [VERIFIED by decompiling the pinned AAR]

Method: `unzip` + `jadx 1.5.6` over
`~/.gradle/caches/modules-2/files-2.1/androidx.media3/media3-session/1.11.1/fbf8c41ab8a5670ed042bd8f644df472ba0967fb/media3-session-1.11.1.aar`
(`MediaSessionService`, `MediaSessionService$MediaSessionServiceStub`,
`MediaNotificationManager*`). Facts:

1. **Registration is the gate.** `MediaSessionService.addSession(session)` is
   the only way a session enters the notification machinery, and it is reached
   from exactly three places:
   - `onBind` with action `androidx.media3.session.MediaSessionService` →
     service stub → controller connect → `onGetSession` → `addSession`
     (a `MediaLibraryService` bind lands on the same path through its stub);
   - `onBind` with legacy action `android.media.browse.MediaBrowserService` →
     `onGetSession` → `addSession` immediately;
   - `onStartCommand` **only for media-button / custom-action intents** whose
     session resolves via `onGetSession` → `addSession`.
2. **A plain start is a verified no-op.**
   `startForegroundService(Intent(context, OmniPlaybackService::class.java))`
   with no media action and no internal uid extra returns `START_STICKY`
   without registering anything. "Just start the service" does not fix this.
3. Once added, `MediaNotificationManager` builds its **own internal
   in-process `MediaController`** on the session token (connection hint
   `"androidx.media3.session.MediaNotificationManager"`) and refreshes the
   notification on `Player.Events` `containsAny({4, 5, 14, 0})` =
   `PLAYBACK_STATE_CHANGED`, `PLAY_WHEN_READY_CHANGED`,
   `MEDIA_METADATA_CHANGED`, `TIMELINE_CHANGED` (constant values confirmed by
   field-declaration order via `javap` on `androidx.media3.common.Player`).
   The notification therefore reflects the **exposed player facade**
   (`PlaybackStack.sessionPlayer`, `PlaybackWiringModule.kt:73-76`,
   provided as `@ExposedPlayer`), which the live controller already drives —
   metadata included (`QueueAwareBridge.kt:218-227` sets title/artist).
4. `shouldShowNotification(session)` requires the internal controller
   connected AND a non-empty timeline AND `playbackState != STATE_IDLE`.
   For an idle player the default mode is
   `SHOW_NOTIFICATION_FOR_IDLE_PLAYER_AFTER_STOP_OR_ERROR` (3): show only if
   the player had been prepared and the user has not dismissed the
   notification.
5. When showing with foreground required, media3 posts via
   `ContextCompat.startForegroundService(startSelfIntent)` +
   `Util.setForegroundServiceNotification(service, id, notification,
   FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, "mediaPlayback")` — it starts the
   FGS itself. Our manifest already satisfies the API 34 contract
   (`foregroundServiceType="mediaPlayback"`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
   exported service with both actions, `stopWithTask=false`).
6. External confirmation: androidx/media#197 — without a `MediaController`
   built against the service's `SessionToken`, the connect path never runs and
   notifications never appear (maintainer statement).

## Root cause — layer 3, the empty command grant (2026-11-12)

Found on device with a debug build after layer 2 was fixed: the service was
bound, the session registered, `MediaController` connected — and no
notification and no foreground service. [VERIFIED by instrumentation and
`dumpsys`]

1. The live controller's `availableCommands` was
   `[SET_TRACK_SELECTION_PARAMETERS]` only (media3 injects that one command
   unconditionally), so `COMMAND_GET_TIMELINE` was absent and the controller's
   timeline was empty at every update — while the session's own player reported
   60 items and a full command set.
2. Cause: `OmniPlaybackService.SessionCallback.onConnect` called
   `super.onConnect(...)` and forwarded its `availablePlayerCommands`. For an
   implementation that overrides only the deprecated synchronous `onConnect`,
   that default is `MediaSession.Callback.onConnect` →
   `getDeprecatedDefaultConnectionResult()`, which returns an accepted result
   with `availableSessionCommands = EMPTY` and
   `availablePlayerCommands = EMPTY`.
3. media3 applies that grant twice over: each `PlayerInfo` sent to a controller
   is filtered by `intersect(granted, player)` — no `COMMAND_GET_TIMELINE`
   means `Timeline.EMPTY` — and the controller's own command set is the same
   intersection. `MediaNotificationManager.shouldShowNotification` requires a
   non-empty timeline on its internal controller, so the manager never posts.
4. Why it hid: the first `ConnectionState` is delivered in-process unfiltered,
   so a controller briefly sees the real timeline right after connecting; the
   next filtered update drops it. And playback is unaffected because the UI
   drives the in-process player. [VERIFIED]
5. Fix: build the result with
   `AcceptedResultBuilder(session, controllerInfo)` (trusted →
   `DEFAULT_PLAYER_COMMANDS`, untrusted → read-only set) and only add the
   notification session commands to it. Guarded by a regression test that
   asserts a controller is granted `COMMAND_GET_TIMELINE`.

## Ruled out

- **POST_NOTIFICATIONS** — granted on device, and no notification was ever
  posted at all (the manager never existed).
- **Notification channel** — `PlaybackNotificationProvider.ensureChannel`
  pre-creates `omnisonic.playback` (importance DEFAULT, no badge); media3's
  own ensure no-ops on it.
- **Manifest** — service exported with both actions, FGS type + permissions
  present, `MediaButtonReceiver` declared.
- **Provider content** — `MediaStyle` + session token, fixed id 1001,
  metadata from the exposed player; `ic_stat_playback` exists (module builds).
- **Command availability** — `SessionCallback.onConnect` passes through the
  player's available commands; commands affect which buttons render, not
  whether the notification posts.
- **Provider/manager ordering** — `setMediaNotificationProvider` in service
  `onCreate` lazily creates the manager with our provider; any later
  `addSession` uses it. No ordering bug.

## Fix (recommended)

Keep the in-process playback architecture. Add a **process-lifetime
session-host `MediaController`** whose only job is to bind the service so the
session registers. Sketch (final shape is the fixing session's):

```kotlin
/**
 * Binds the app to the media session service with a process-lifetime
 * controller. Media3 arms its notification machinery only when a controller
 * connects (see media-notification-fix.md), so without this bind the session
 * in OmniPlaybackService never registers and nothing posts.
 */
@Singleton
class PlaybackSessionHost @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun connect() {
        // idempotent; main thread only; never released for the process lifetime
        val token = SessionToken(context, ComponentName(context, OmniPlaybackService::class.java))
        MediaController.Builder(context, token).buildAsync().addListener(
            { runCatching { /* keep the controller referenced */ } },
            MoreExecutors.directExecutor(),
        )
    }
}
```

Invariants the implementation must hold:

1. **One connect per process** (guard; `OmnisonicApplication.onCreate` is the
   trigger, mirroring `librarySyncLauncher.get().resumeIncomplete()`).
2. **Main thread** — `MediaController` needs an application looper;
   `Application.onCreate` is main. Never call from a background dispatcher.
3. **Never released** — releasing unbinds the service, and media3 stops a
   service with no bound controller while no session plays (androidx/media#389).
   Keep the controller referenced for the process lifetime.
4. **Failure-tolerant** — a failed connect means "no notification", not a
   crash. Wrap builder + future; log, never throw.
5. **Fake-able seam** — extract the connect call behind a
   `fun interface ControllerConnector` (default = the real builder) provided by
   `PlaybackWiringModule`, so `:app`/`:playback` tests can stub it (see the
   Robolectric note under Tests).
6. No manifest, channel, provider, or session-callback changes. `:app` already
   depends on `libs.media3.session` (`app/build.gradle.kts:104`); `:playback`
   has media3-session (it hosts the service).

### Trigger decision point (confirm with the user before coding)

- **At app launch (recommended):** one code path; the service-side queue
  restore (`OmniPlaybackService.kt:78`) then runs at launch too, which
  subsumes the visible half of task 7c. Cost: a bound idle service for the
  process lifetime (no FGS unless playing — battery noise is minimal).
- **At first playback:** lighter idle footprint, but the trigger needs a
  playback-lifecycle hook (more call sites), and 7c stays as written.

## Expected behaviors after the fix (do not "fix" these)

- Fresh launch with an empty queue: **no notification** (timeline empty).
- After a launch-time restore: a **paused** notification appears (timeline
  non-empty, state READY ≠ IDLE, `ongoing=false` while paused). Standard
  player behavior, not a bug.
- While playing: notification + media FGS (`dumpsys activity services` shows
  the service), lock-screen and ColorOS media cards populate, notification
  metadata updates per track.
- Swipe-dismiss while paused: stays gone until the next playback event;
  while playing media3 re-posts on the next tracked event.
- Task swipe-away (`onTaskRemoved`): media3 pauses players and stops only
  when nothing is playing (`!isPlaybackOngoing() || !isAnySessionPlaying()`).
- A live FGS while playing also exempts the app from the ColorOS freezer
  during playback — crosses into the battery-optimization open question
  (`open-questions.md`), noted, not solved here.

## Interaction with task 7c (queue restore)

- Launch-connect starts the service → `restoreQueueWithin` applies the
  snapshot at launch → queue + mini-player return via the service path.
- The 7c brief's `restoreAttempted` exactly-once guard in
  `PlaybackControllerImpl` is still **required**: with both paths live, the
  app-init restore and the service restore race on the `Main.immediate`
  engine scope, and the guard makes the second call a no-op.
- If launch-connect is chosen, 7c can shrink to "the exactly-once guard +
  keep the service path" (its `PlaybackLaunchRestore` seam becomes redundant).
  The fixing session confirms with the user before dropping any 7c piece.

## Rejected alternatives

- **Drive all UI playback through the `MediaController`** — the canonical
  media3 architecture and the right long-term direction, but a shell-wide
  refactor of every controller consumer + test doubles; wrong size for this
  defect. Keep as a later-phase roadmap candidate.
- **`startForegroundService` with a plain intent** — verified no-op for
  registration (layer 2, fact 2); also fights the service's
  `initialStartIntentProcessed` / self-intent uid bookkeeping.
- **Hand-rolled platform notifications / `MediaSessionCompat`** — media3 owns
  the notification + FGS lifecycle (type, timeouts, dismissal); duplicating it
  re-introduces the bugs media3 already fixed.
- **`setShowNotificationForIdlePlayer` tuning** — orthogonal; the default
  (AFTER_STOP_OR_ERROR) matches expectations.

## Implementation checklist

- [ ] `PlaybackControllerImpl`: land the 7c `restoreAttempted` guard first
      (or together) — the launch-connect makes the double-restore race real.
- [ ] `PlaybackSessionHost` + `ControllerConnector` seam in
      `playback/.../service/` (KDoc cites this brief; no obvious comments).
- [ ] Hilt provider in `PlaybackWiringModule`; `OmnisonicApplication` injects
      `Provider<PlaybackSessionHost>` and calls `connect()` in `onCreate`.
- [ ] Robolectric escape hatch **only if** the empirical check below fails.
- [ ] Existing tests stay green: `OmniPlaybackServiceTest`,
      `PlaybackNotificationProviderTest`, `ServiceQueueRestoreTest`,
      `OmniForwardingPlayerTest`.

## Tests

- `:playback` plain-JVM `PlaybackSessionHostTest` over the connector seam:
  connect is called at most once per process; a connector that fails (throws
  or a failed future) is swallowed (no crash); a second `connect()` is a no-op.
- **Robolectric risk, resolve empirically as the FIRST implementation step:**
  `MediaController` connect over a `SessionToken(ComponentName)` goes through
  `bindService` + `onServiceConnected`; STATE.md T11 recorded an NPE there
  under Robolectric ("onServiceConnected receives a null ComponentName"), while
  in-process token connects work (`OmniForwardingPlayerTest` connects fine).
  `OmnisonicApplication.onCreate` runs in the default-config `:app` Robolectric
  tests, so: land the connect, run the full `:app` suite.
  - If green → done.
  - If the service-bind path crashes during looper pumping → gate the
    **production connector default** on a Robolectric check (e.g. a
    `Build.FINGERPRINT` robolectric probe inside the connector), documented as
    test-only; do not touch the app class or the tests' configs.
- `:app` no new main-code tests beyond the above (the smoke test boots
  `MainActivity` under `HiltTestApplication`, which replaces the real
  application class — `OmnisonicApplication.onCreate` does not run there).

## Device acceptance (task 7e, RMX3085 recipe in `next-session-prompt.md`)

1. Grant notifications (already done), launch the app, play a track: the
   notification appears with title/artist, prev/play-pause/next work, and the
   metadata updates when the track changes.
2. `dumpsys activity services app.omnisonic` shows the foreground service
   while playing; background the app → playback continues; the shade card and
   lock screen/ColorOS media card populate.
3. Tap the notification → the app opens (session activity path, unchanged).
4. Pause → notification stays, non-ongoing; swipe it away → gone until the
   next playback event.
5. Kill the app, start playback again → notification returns (fresh session).
6. Re-check the queue-restore acceptance of 7c if that task has not landed
   yet: relaunch shows the restored paused queue + mini-player (service path).

## Documentation and state to update (fixing session)

- `playback/service-and-notifications.md` — add the registration requirement
  (controller bind) + the session-host host; mark the launch-connect.
- `playback/controller-and-events.md` — the exactly-once restore rule.
- `progress/KNOWN_ISSUES.md` — delete this defect's entry.
- `docs/omnisonic/README.md` — move this brief's row to "fixed" wording.
- `.agents/memory/open-questions.md` — close the session-host item (and note
  the ColorOS freezer interplay).
- Task line `[x]` in `.agents/plans/phase-5-tasks.md`, `progress/STATE.md`,
  `progress/CHANGELOG.md`, a `findings.md` entry (media3 registration facts,
  Robolectric outcome), and advance `next-session-prompt.md`.

## Decisions the fixing session must confirm (with the user if they disagree)

- Session-host trigger: app launch (recommended) vs first playback.
- 7c folding: keep the in-process restore as a safety net behind the
  exactly-once guard, or reduce 7c to the guard only.
- The Robolectric escape hatch (only if the empirical check fails) and its
  exact form (connector-level probe).
