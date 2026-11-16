# Playback service and notification surface — `service-and-notifications.md`

Purpose: Pin how `OmniPlaybackService` is discoverable, how its media session
publishes the app entry point, and how the playback notification reuses that
entry point so a notification tap opens the app.

How derived: the app manifest, `OmniPlaybackService`,
`PlaybackNotificationProvider`, and the Robolectric tests
`OmniPlaybackServiceTest` and `PlaybackNotificationProviderTest` in this
repository. Media3's documented manifest contract for `MediaLibraryService` is
the external reference for declaring both service actions.

Confidence:
- [VERIFIED] the service declares both `androidx.media3.session.MediaLibraryService`
  and `android.media.browse.MediaBrowserService` intent actions.
- [VERIFIED] the media session's `sessionActivity` is built from the package
  launch intent and is non-null when the service boots.
- [VERIFIED] the launch `PendingIntent` is immutable and carries
  `FLAG_UPDATE_CURRENT`; the saved intent is `ACTION_MAIN` with
  `CATEGORY_LAUNCHER`, targets `MainActivity`, and keeps
  `FLAG_ACTIVITY_NEW_TASK`.
- [VERIFIED] `PlaybackNotificationProvider` uses `session.sessionActivity` as
  the notification content intent, so the notification and the session share
  one entry point.
- [VERIFIED] `onCreate` starts queue restore on the service scope with a 2 s
  `withTimeoutOrNull` bound instead of blocking the caller; `onTrimMemory(UI_HIDDEN)`
  launches the snapshot flush without waiting, and teardown keeps a bounded
  flush before the scope is cancelled (FIX-14).
- [VERIFIED] `restoreQueueWithin` returns `false` when a stuck store exceeds
  the bound, leaving the empty in-memory queue (`ServiceQueueRestoreTest`).
- [VERIFIED] Registration needs a controller bind (task 7e): media3
  `MediaSessionService.addSession` is reached only from a controller connect
  (`onBind` → stub → `onGetSession`), the legacy browse action, or a
  media-button `onStartCommand`; a plain `startForegroundService` registers
  nothing. `playback/service/PlaybackSessionHost` therefore builds one
  process-lifetime `MediaController` against
  `SessionToken(ComponentName(context, OmniPlaybackService::class.java))` from
  `OmnisonicApplication.onCreate`, and the session in `onCreate` registers on
  that connect. The controller is never released (releasing it unbinds the
  service, which media3 then stops while nothing plays).
- [VERIFIED] The session-host connect skips itself when `Build.FINGERPRINT`
  contains `robolectric`: the shadow `bindService` delivers a null
  `ComponentName` to `onServiceConnected` and media3 throws an NPE while the
  looper drains, which broke every `:app` Robolectric test that boots the real
  application class. Test-environment probe only; device behaviour is the real
  bind.
- [VERIFIED] Service-side restore is now the **fallback**, not the only path
  (task 7c): the app applies the snapshot from `PlaybackLaunchRestore` at
  launch, and `PlaybackController.restoreQueue()` applies at most one snapshot
  per process — a service start after an app launch gets `false` instead of
  re-applying it. A service start that is the first thing in the process (no
  UI, system-initiated) still restores normally.
- [INFERRED] legacy `MediaBrowserCompat` clients can discover and bind the
  service through the platform `MediaBrowserService` action; media3 maps the
  legacy connection onto the new session API.
- [UNKNOWN] Launcher, lock-screen and Android Auto presentation,
  notification-tap behavior after process death, and the observed
  notification/FGS feel while playing on a real device (task 7e device
  acceptance not run).

Date: 2026-11-06

Links: `controller-and-events.md`, `../remediation/PROGRESS.md`

---

## Notification registration

Media3 arms its notification machinery only when a controller connects to the
service's session; the app UI talks to the in-process Hilt controller instead,
so nothing used to bind the service and no notification was ever posted.

`PlaybackSessionHost` (task 7e) closes that gap:

```kotlin
// OmnisonicApplication.onCreate
playbackSessionHost.get().connect()

// PlaybackSessionHost.connect() — one-shot, main thread, never released
token = SessionToken(context, ComponentName(context, OmniPlaybackService::class.java))
MediaController.Builder(context, token).buildAsync()
```

- The connect is one-shot per process (`PlaybackSessionHost` guard); a second
  call is a no-op.
- It runs on the main thread because `MediaController` connects on the
  application looper.
- A failed connect logs and leaves the app without a notification; it never
  throws.
- The controller reference is held for the process lifetime. Releasing it
  would unbind the service, and media3 stops a service with no bound
  controller while nothing is playing.
- The connect is behind `ControllerConnector` (bound in
  `PlaybackWiringModule` to `ServiceControllerConnector`) so tests can observe
  the bind without a service.

The bind also starts the service, so its `onCreate` restore runs at launch
behind the exactly-once guard (`controller-and-events.md`); the app-init
restore stays as a safety net for a failed or slow bind.

Binding alone is not sufficient — the session's `onConnect` must also grant a
usable player command set. `SessionCallback.onConnect` builds its result with
`MediaSession.ConnectionResult.AcceptedResultBuilder(session, controllerInfo)`
and only adds the notification buttons' custom session commands.

- **Never build on the deprecated `super.onConnect`.** That default returns an
  accepted result whose `availablePlayerCommands` is
  `Player.Commands.EMPTY`. It looks harmless because playback keeps working
  (the UI drives the in-process player), but media3 filters the timeline out of
  every state update sent to a controller without `COMMAND_GET_TIMELINE`.
- `MediaNotificationManager.shouldShowNotification` requires a non-empty
  timeline **on its own internal controller**, so with the empty grant the
  manager stays silent and no notification is ever posted, even though the
  service is bound and the session is registered. [VERIFIED — RMX3085,
  2026-11-12: controllers reported only `SET_TRACK_SELECTION_PARAMETERS`,
  timeline 0, `startForegroundCount=0`; after the grant fix the notification
  posts on id 1001 and the media FGS starts.]
- A trusted controller gets `DEFAULT_PLAYER_COMMANDS`, an untrusted one the
  read-only set; both include the read commands the notification needs.
- `OmniPlaybackService.currentCallback` exposes the callback for the regression
  test that asserts the grant (`OmniPlaybackServiceTest`).

## Manifest contract

`app/src/main/AndroidManifest.xml` declares one playback service:

```xml
<service
    android:name="app.omnisonic.playback.service.OmniPlaybackService"
    android:exported="true"
    android:foregroundServiceType="mediaPlayback"
    android:stopWithTask="false">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaLibraryService" />
        <action android:name="android.media.browse.MediaBrowserService" />
    </intent-filter>
</service>
```

- The media3 action is the required `MediaLibraryService.SERVICE_INTERFACE`.
- The platform action is the legacy `MediaBrowserService` interface. Declaring
  both lets clients find the same service through either API without a second
  service entry (a second entry can make the app appear twice in controller
  lists).
- `exported=true` is required for external controllers (system media controls,
  Android Auto, legacy browsers). The foreground type stays `mediaPlayback`.

## Session activity

`OmniPlaybackService.onCreate()` builds the library session and sets its
activity before publishing it:

```kotlin
val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
val sessionActivity = launchIntent?.let {
    PendingIntent.getActivity(
        this,
        SESSION_ACTIVITY_REQUEST_CODE,
        it,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
```

- Source of the entry point: the package launch intent, so the system resolves
  whichever activity the manifest marks as the launcher. The playback module
  never names the app activity directly.
- `FLAG_IMMUTABLE`: no consumer fills in extras; the pending intent is
  read-only. `FLAG_UPDATE_CURRENT` keeps the launch intent current. Required on
  API 31+ when creating a pending intent without a mutability flag.
- If the package has no launch intent (not a valid state for an installed app),
  the service leaves `sessionActivity` unset instead of crashing.
- The request code is a fixed service constant; there is one activity pending
  intent per service process.

## Notification tap path

`PlaybackNotificationProvider.createNotification()` sets:

```kotlin
.setContentIntent(session.sessionActivity)
```

There is no separate notification-only navigation intent. The notification
therefore opens the same app entry point the session publishes, including after
the user taps the collapsed or expanded notification. The provider does not
change the notification layout, actions, channel, or id for this fix.

## Startup and teardown

`OmniPlaybackService.onCreate()` creates one `serviceScope` on
`Dispatchers.Main.immediate` and launches two jobs: the wake-lock collector over
`controller.playerState` and `restoreQueueWithin(controller, 2_000)`.

- Restore no longer runs through `runBlocking` on the boot path. A store that
  takes longer than the bound is abandoned, leaving the empty queue exactly as
  a store with no snapshot would. The session is built immediately afterwards;
  a completed restore republishes queue state through the controller, so
  binding does not depend on restore finishing first.
- `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` launches `persistence.flush()` on the
  service scope and returns. The service never blocks the memory callback on
  disk I/O.
- `onDestroy` keeps a bounded `runBlocking` flush (2 s) before cancelling the
  scope and releasing the session and wake lock. That is teardown, not a
  startup path; the bound keeps a stuck store from delaying process death
  indefinitely.

## Tests

`OmniPlaybackServiceTest`:

- `serviceDeclaresMedia3AndPlatformMediaBrowserIntentActions` queries the
  package manager for both service actions and asserts the service is returned
  for each.
- `serviceBootsOnTheExposedPlayerAndTearsDown` additionally asserts
  `session.sessionActivity` is non-null, the pending intent is immutable, and
  its saved intent is the package launch intent (`ACTION_MAIN`,
  `CATEGORY_LAUNCHER`, `MainActivity`, `FLAG_ACTIVITY_NEW_TASK`). It also swaps
  in a persistence mock whose `flush()` delays 2 s and asserts `onTrimMemory`
  returns in under 1 s while the flush has started.
- `ServiceQueueRestoreTest` pins the restore bound: a controller whose
  `restoreQueue()` delays 10 s returns `false` from a 50 ms bound in under 1 s.

`PlaybackNotificationProviderTest` continues to pin notification identity and
actions; the content intent is supplied by the session activity above.
