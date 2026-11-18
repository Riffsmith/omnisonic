# Notification presentation fixes — `notification-presentation-fixes.md`

Purpose: Hand off the `progress/KNOWN_ISSUES.md` P1 notification cluster — missing artwork,
an alerting channel, and the "no permission" observation — with the confirmed
causes, the chosen fix design, the files to change, and the device gates. This
is a standalone bug fix, **not Phase 6 and not part of any phase**.

How derived: source review of `PlaybackNotificationProvider`,
`OmniPlaybackService`, `QueueAwareBridge`, `PlaybackQueueEntry`,
`ImageLoaderModule` and the cover pipeline; a Robolectric read of
`PlaybackNotificationProviderTest`; and Android platform documentation for
notification channels and the `POST_NOTIFICATIONS` media-session exemption. No
device was attached during the investigation (2026-11-17).

Confidence:
- [VERIFIED] the notification has no large icon: the custom provider never calls
  `setLargeIcon` and the exposed metadata carries no artwork identity.
- [VERIFIED] the playback channel is created at `IMPORTANCE_DEFAULT`, so the
  first post of a playback session alerts; `setOnlyAlertOnce(true)` only
  suppresses updates.
- [VERIFIED] Android 13+ exempts media-session notifications from
  `POST_NOTIFICATIONS`; the app declares the permission and never requests it
  at runtime. "Notification shows without permission" is expected behaviour.
- [INFERRED] a Coil-backed artwork loader through the existing `CoverImageKey`
  pipeline is the right seam; it reuses the provider-routed client and the
  cover cache. Not yet implemented.
- [UNKNOWN device] the visual result on the RMX3085 / moto g32, a channel
  migration on an already-installed build, and whether the artwork repaint can
  race a track change. No device was attached for this investigation.

Date: 2026-11-17 (follow-up note added 2026-11-18)

Links: `service-and-notifications.md` (service/session facts),
`media-notification-fix.md` (how the notification came to exist at all, task
7e), `notification-artwork-quality.md` (follow-up: Android 13 shrinks the large
icon, session `artworkData` fix),
`../ui/search-tab-fixes.md` (the other P1 cluster),
`../providers/library-sync-ux.md` (cover sizing context — D-17),
`progress/KNOWN_ISSUES.md`.

---

## Phase boundary (read before coding)

- This is the P1 fix recorded in `progress/KNOWN_ISSUES.md`. It is **outside the
  phase structure**.
- Phase 6 is **not** being started as part of this work. Do not touch
  `.agents/plans/phase-5-tasks.md`, `.agents/plans/phase-plans.md`,
  `.agents/plans/roadmap-ref.md`, any
  `[x]` phase status, or `progress/STATE.md` history sections.
- Finish this fix (or this cluster) and stop for the user; phase work resumes
  only on the user's explicit go-ahead.

## Implementation status (2026-11-17) — implemented, JVM-verified, device gate OPEN

All of A1/A2/A3 are in the working tree (uncommitted). No device was attached
(`adb devices` empty), so every visual/silence claim below is `[UNKNOWN device]`.

**Chosen options (the brief's open questions, decided here):**

1. **Loader seam:** the app-provided Coil loader. `:playback` owns
   `NotificationArtworkLoader`; `:app` binds `CoilNotificationArtworkLoader`
   (`app/src/main/java/app/omnisonic/image/CoilNotificationArtworkLoader.kt`)
   through `ImageLoaderModule`, which resolves a `CoverImageKey` via the
   existing singleton `ImageLoader`. Verified by `:app:assembleDebug` (the
   Hilt-graph gate, STATE T13), not just module tests.
2. **Identity:** metadata extras. `NotificationArtworkMetadata` (in
   `playback/.../service/NotificationArtworkLoader.kt`) defines
   `providerId` / `coverImageId` / `sizePx` keys and `NotificationArtworkIdentity`;
   `QueueAwareBridge.metadataFor` attaches them to both the `MediaItem`
   metadata and the exposed `MediaMetadata` whenever the entry has a cover.
   No `artworkUri` — URLs stay provider-routed and credential-salted.
3. **Cadence:** one no-art frame, then a repaint. No pre-warm.
4. **Cache:** at most 4 bitmaps (`MAX_CACHED_ARTWORK`), keyed by the identity,
   requested at a bucketed 512 px. Failures are memoized per identity (one log
   line, no retry loop); an in-flight load is tracked per identity; a completion
   whose identity is no longer current never repaints.
5. **Threading:** `createNotification` builds on the media session thread and
   only launches; the loader contract requires implementations to move their work
   off the caller's thread (`CoilNotificationArtworkLoader` uses `Dispatchers.IO`).

**Deviations from this brief (recorded, deliberate):**
- `NotificationArtworkLoader.load` takes the identity data class instead of
  three positional parameters.
- The provider builds the notification **before** publishing its builder for the
  repaint. A loader that resolves inline must not mutate the notification being
  built; the test caught exactly that (the first notification came out with art
  and no repaint was observable).
- The channel's display name stays `notification_channel_playback` ("Playback");
  the v2 id is invisible to users and the legacy channel is deleted, so no new
  string was added.

**Follow-up (2026-11-18, implemented + JVM-verified, device-unverified):** the
artwork this fix adds reached Android 13's media card only as `setLargeIcon`,
and the platform shrinks a notification large icon to 48 dp before posting it;
SystemUI then prefers session metadata artwork and fell back to that reduced
icon, so the panel card showed an upscaled 48 dp bitmap ("very blurry"). D-32
publishes the same 512 px bitmap as session `artworkData`, which fills
`METADATA_KEY_ALBUM_ART` for SystemUI, and leaves `setLargeIcon` as the pre-13 /
fallback path. Root cause, design, tests and the device checklist:
`notification-artwork-quality.md`. The artwork item in this brief's device
checklist still needs that device pass (`[UNKNOWN device]`).

**Test evidence `[VERIFIED JVM]`:**

- `:playback` `PlaybackNotificationProviderTest` 14/14 — channel is
  `IMPORTANCE_LOW` with `sound == null`, no badge, PRIVATE; a pre-created
  `omnisonic.playback` channel is gone after construction while
  `omnisonic.playback.v2` exists; art appears through `onNotificationChanged`
  with id 1001 and is attached immediately on the next build (cache hit); a
  null loader result is non-fatal, sets no large icon and is not retried; a
  stale gated load does not repaint. The old `IMPORTANCE_DEFAULT` assertion was
  replaced — that assertion pinned the bug.
- `:playback` `OmniForwardingPlayerTest` 7/7 — the exposed metadata carries
  `NotificationArtworkIdentity(7, "cover-9")` for a covered entry and none for a
  coverless one.
- `:playback` module 264/264, `:app` 121 debug + 16 release, `:feature-search`
  15, `:feature-settings` 53, `:feature-library` 122; `:app:assembleDebug`,
  `lint` green; banned-name scan clean.
- Settings row: `:app` `NotificationSettingsRowTest` — clicking the row starts
  `ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE` = the app package.

**Device checklist:** not run (no device attached) — every item below is
`[UNKNOWN device]`. The P1 notification entries in `progress/KNOWN_ISSUES.md`
stay open until it passes.

## Symptoms (from `progress/KNOWN_ISSUES.md` P1)

1. The song's image is not displayed in the notification panel.
2. The notification makes a sound when playback starts and when the song first
   appears on the panel.
3. On a fresh install the notification appears even though notification
   permission was never granted; the user wants a show/hide path through
   Android's native menu.

## Evidence

### 1. Artwork is absent by construction [VERIFIED by source]

- `playback/src/main/java/app/omnisonic/playback/service/PlaybackNotificationProvider.kt`
  builds the notification at lines 70-110 with small icon, actions, content
  intent, `setOnlyAlertOnce`, and `MediaStyleNotificationHelper.MediaStyle` —
  there is no `setLargeIcon` call anywhere in the file, and no `IconCompat` /
  bitmap path besides the small icon and action icons.
- The provider reads `session.player.mediaMetadata` (line 63). That metadata is
  produced by
  `playback/src/main/java/app/omnisonic/playback/player/QueueAwareBridge.kt:211-235`
  and contains only `title` + `artist` on both the `MediaItem` metadata and the
  exposed `MediaMetadata`. No `artworkUri`, no `artworkData`, no artwork extras.
- The data to show art already exists end-to-end:
  `PlaybackQueueEntry` carries `providerId` and `coverImageId`
  (`playback-api/src/main/java/app/omnisonic/playback/api/queue/QueueModels.kt:7-17`);
  the mappers populate it (`feature-library/.../playback/QueueEntries.kt:20,33`);
  `QueuePersistence` and `QueueSnapshotRepository` persist it; the now-playing
  and queue screens already build `CoverImageKey(providerId, imageId)` from it
  (`NowPlayingUiState.kt:72`, `QueueScreen.kt:663`).
- Cover URL resolution and the provider-routed client already exist:
  `ProviderClientRegistry.providerFor(id)?.coverArtUrl(imageId, sizePx)` is how
  the app's image loader resolves a `CoverImageKey`
  (`app/src/main/java/app/omnisonic/image/ImageLoaderModule.kt:26`), and
  `:playback` already depends on `:core-data`, `:core-network`, `:provider-api`
  and Coil (`playback/build.gradle.kts`).

### 2. The channel itself alerts [VERIFIED by source + platform contract]

`PlaybackNotificationProvider.ensureChannel()` (lines 130-145) creates
`CHANNEL_ID = "omnisonic.playback"` with `IMPORTANCE_DEFAULT`, badge off and
`VISIBILITY_PRIVATE`, and no explicit sound override. `IMPORTANCE_DEFAULT`
means "make a sound"; the `.setOnlyAlertOnce(true)` on the builder only
suppresses re-alerts for the same notification id, so the first post of a
session (or the first post after the user dismissed it) still alerts.

The existing test enshrines the bug:
`playback/src/test/java/app/omnisonic/playback/service/PlaybackNotificationProviderTest.kt:126`
asserts `IMPORTANCE_DEFAULT`.

Channel behaviour cannot be reliably changed in place: `NotificationChannel`
behaviour (including importance) is fixed once submitted; the API reference for
`createNotificationChannel` allows only a *lowering* of importance and only if
the user has never modified the channel. Google's channel guide states plainly
that importance cannot be changed after creation. A new channel id is therefore
required for existing installs.

### 3. The permission observation is expected behaviour [VERIFIED external]

- Android's notification permission guide: on Android 13+ (API 33), media-session
  notifications are **exempt** from the `POST_NOTIFICATIONS` behaviour change
  ("Media sessions: Notifications related to media sessions are exempt from this
  behavior change").
- media3 implements this: `MediaNotificationManager` carries
  `// POST_NOTIFICATIONS permission is not required for media session related
  notifications` plus `@SuppressLint("MissingPermission")` on its update path.
- The app declares `android.permission.POST_NOTIFICATIONS`
  (`app/src/main/AndroidManifest.xml:9`) and contains no runtime request call
  site (repository scan). So the fresh-install observation is correct and is
  **not a bug**.
- What is missing is discoverability: the channel is user-toggleable in the
  system UI, but the app offers no entry point to it.

### Baseline state

- `git status` clean on `main` at `25fceec` before the investigation.
- `./gradlew :playback:testDebugUnitTest --tests
  "app.omnisonic.playback.service.PlaybackNotificationProviderTest"` → BUILD
  SUCCESSFUL (22 s, run together with `:app` `MainTabsPagerTest`). [VERIFIED]
- `adb devices` lists no device, so none of the device-facing claims below were
  re-observed. [UNKNOWN device]

## Chosen design

### A1. Artwork: carry cover identity in metadata, load behind a seam, repaint

1. **Metadata identity** — in `QueueAwareBridge.mediaItemDataFor`, add the cover
   identity to the exposed `MediaMetadata` for the current item. Recommended
   shape: an extras bundle with `providerId`, `coverImageId` and a bounded
   request size, because Subsonic cover URLs are credential-salted and
   provider-routed (a bare `artworkUri` would bypass the API client's headers /
   mTLS and cannot express local `content://` covers). Set it on the same
   metadata object that the notification reads (`MediaItemData.setMediaMetadata`,
   lines 226-230).
2. **Loader seam** — add a small interface in `:playback`, e.g.
   `NotificationArtworkLoader { suspend fun load(providerId: Long, imageId: String, sizePx: Int): Bitmap? }`,
   with a production implementation in `:app` backed by the existing singleton
   Coil `ImageLoader` (`app/src/main/java/app/omnisonic/image/ImageLoaderModule.kt`)
   and a `CoverImageKey`. Bind it from `:app` (the app already provides
   `ImageLoader`); `:playback` tests pass a fake. This reuses the cover cache,
   the provider-routed OkHttp client, D-17 size bucketing and local-provider
   support with no new networking code.
   - The seam must be injectable into `PlaybackNotificationProvider`; its
     constructor gains one parameter, which will require updating the existing
     test construction (`PlaybackNotificationProviderTest:102`).
3. **Provider behaviour** — in `createNotification`:
   - read the identity from `player.mediaMetadata.extras` (or `artworkUri` if the
     chosen loader settles on URLs);
   - if a bitmap for the current identity is already cached, call
     `builder.setLargeIcon(bitmap)`;
   - otherwise return the notification without art and start **one** load per
     track; when it completes, repaint with
     `callback.onNotificationChanged(MediaNotification(NOTIFICATION_ID, builder…))`
     on the main thread.
   - Keep the fixed id 1001 and `setOnlyAlertOnce(true)`; media3 re-posts the
     same id, so the art appears without a new alert.
   - Ignore a completed load whose identity no longer matches the current
     metadata. Cache at most one bitmap per track (or an LRU of a few) at a
     bounded size (~512 px, bucketed like `CoverSizing`); never keep every
     cover.
   - Failure is non-fatal: no large icon, log once, do not retry in a loop.

**Why not use media3's `DefaultMediaNotificationProvider`?** It already loads
artwork from `artworkUri`/`artworkData` through a `BitmapLoader`, but replacing
the provider would drop the settings-configured notification slot buttons and
the custom channel spec, which are shipped features. Revisit only if the slot
system is simplified.

**Why not decode synchronously?** `createNotification` runs on the session
thread; network/decode there would block notification updates.

### A2. Silent channel: new id, low importance, delete the legacy channel

- `CHANNEL_ID = "omnisonic.playback.v2"` (keep `LEGACY_CHANNEL_ID = "omnisonic.playback"`).
- Create the new channel with `IMPORTANCE_LOW`, `setSound(null, null)`,
  `setShowBadge(false)`, `lockscreenVisibility = VISIBILITY_PRIVATE`.
- In `ensureChannel`, after creating the new channel, delete the legacy channel
  if it exists (`manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)`). The user
  approved deletion on both installs; this prevents a second, still-alerting
  channel in system settings.
- Keep `setOnlyAlertOnce(true)`, `setShowWhen(false)`, the fixed notification id
  and `VISIBILITY_PRIVATE`.
- Sanity: a channel at `IMPORTANCE_LOW` has no sound and no heads-up. An
  explicit `setSound(null, null)` is belt-and-braces and documents intent.

### A3. Native show/hide: a Settings row that opens the system screen

- Add one row/group in `feature-settings/src/main/java/app/omnisonic/feature/settings/ui/ShellSettingsScreen.kt`
  (a new "Playback" or "Notifications" group next to About) labelled e.g.
  "Notification settings".
- On click, launch
  `Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)`.
  This is the native menu the user asked for: channel toggles, sound,
  lockscreen, and the app-level enable switch all live there.
- Do **not** add an in-app on/off switch: an app cannot override the user's
  channel choice, and a local boolean would desync from the system state.
- Do **not** request `POST_NOTIFICATIONS` at runtime as part of this fix. The
  media-session notification does not need it; a prompt would be a permission
  ask with no behavioural effect. (If local/libre audio downloads later post
  their own notifications, that is a separate decision.)
- Strings live in `feature-settings/src/main/res/values/strings.xml`; the screen
  is shell-rendered (`MainTabsPager.kt:81-86`), so no shell wiring changes are
  needed unless a test seam is preferred.
- For testability under Robolectric, assert the launched intent with
  `ShadowActivity`/`ShadowApplication.getNextStartedActivity()`; launching from
  a composable via `LocalContext` is acceptable, or thread an
  `onOpenNotificationSettings: () -> Unit` default parameter if the shadow
  proves awkward.

## Expected behaviour after the fix

- Fresh launch with an empty queue: no notification (unchanged).
- Playback starts: the notification shows title, artist and the song's cover
  art; **no sound** on first appearance or on updates.
- Pause/stop/swipe behaviours are unchanged from
  `service-and-notifications.md` (foreground while playing, non-ongoing when
  paused, re-post on the next playback event after dismissal).
- Notification permission denied but media session active: the media
  notification still appears (platform exemption); the system screen reached
  from Settings is the one place to hide it.
- An install that already had `omnisonic.playback` ends up with only
  `omnisonic.playback.v2` at low importance.

## Files likely to change

| File | Change |
|---|---|
| `playback/src/main/java/app/omnisonic/playback/service/PlaybackNotificationProvider.kt` | artwork read/cache/repaint, new channel id + LOW + silent, legacy channel deletion, loader constructor param |
| `playback/src/main/java/app/omnisonic/playback/player/QueueAwareBridge.kt` | add cover identity to exposed metadata (extras or artworkUri) |
| `playback/src/main/java/app/omnisonic/playback/service/NotificationArtworkLoader.kt` (new) | loader interface (name/location at the implementer's discretion but keep it in `:playback`) |
| `app/src/main/java/app/omnisonic/image/ImageLoaderModule.kt` (or a sibling file) | Coil-backed loader implementation + Hilt binding |
| `playback/src/test/java/app/omnisonic/playback/service/PlaybackNotificationProviderTest.kt` | update construction, channel assertions, add artwork/legacy-channel cases |
| new loader unit test (`:app` or `:playback`) | hit, miss, failure, stale-identity |
| `feature-settings/src/main/java/app/omnisonic/feature/settings/ui/ShellSettingsScreen.kt` + `strings.xml` | notification-settings row |
| `playback/src/main/java/app/omnisonic/playback/player/QueueAwareBridgeTest` (if present) | metadata-identity assertion |

Do not change the manifest, the service registration, the session callback, or
the notification id.

## Testing and verification

JVM (all required before the device pass):

1. `PlaybackNotificationProviderTest`
   - channel: new id, `IMPORTANCE_LOW`, `sound == null`, no badge, PRIVATE;
     legacy channel is absent after construction (pre-create it in the test,
     as a migrated install would have it).
   - artwork: a fake loader returning a bitmap results in
     `Notification.EXTRA_LARGE_ICON` being set; a null loader result leaves it
     unset and does not throw.
   - repaint: with a controllable loader, assert `onNotificationChanged` is
     invoked once the bitmap resolves, with the same id; assert a stale
     identity (different `coverImageId`) does **not** repaint.
   - existing action/slot/metadata assertions stay green.
2. Metadata identity: assert the bridge/exposed metadata carries
   `providerId` + `coverImageId` for the current entry (extend the existing
   bridge test or add a focused case).
3. Settings row: clicking it starts an intent whose action is
   `Settings.ACTION_APP_NOTIFICATION_SETTINGS` and whose
   `EXTRA_APP_PACKAGE` is the app package (Robolectric shadow).
4. Loader: key construction (size bucketing) is pure and unit-testable; the
   Coil-executing part can be exercised with a Robolectric `ImageLoader` or
   left to the provider test's fake.

Project gate: `./gradlew :app:assembleDebug test lint`; banned-name scan over
all touched files (`scripts/check-banned-names.sh`); no new `Log` outside the
existing logger conventions.

Device acceptance (needs an attached device; the earlier session had none):

1. Install over an existing build that has the legacy channel. Confirm system
   settings shows only the new channel, at low importance, and that the first
   playback is silent.
2. Play a track with a cover: the panel shows the cover; next/previous updates
   it. Lock screen / ColorOS media card shows album art (previously `[UNKNOWN]`).
3. Fresh install, permission **not** granted: play a track and record what the
   shade shows; expect the media notification (exemption). Capture
   `adb shell dumpsys notification --noredact | grep -A5 omnisonic.playback`
   and `adb shell dumpsys activity services app.omnisonic` for the record.
4. Settings → Notification settings opens the system screen; disabling the
   channel hides the playback notification and it stays hidden through the next
   track.
5. Kill the app, relaunch, play: art and silence still hold; notification id
   remains 1001.
6. Swipe the notification away while paused, then resume: it returns (standard
   media3 behaviour).

Record every observation with its `[VERIFIED]` / `[UNKNOWN]` tag in
`progress/KNOWN_ISSUES.md` and `progress/STATE.md`.

## Risks and constraints

- **Channel migration**: deleting the legacy channel is irreversible for a
  user's customisations of it, but the user explicitly approved deletion on both
  installs. Do it only once the v2 channel exists.
- **Hilt graph gap**: an app-provided binding injected into `:playback` only
  fails at `:app:assembleDebug` (STATE T13). Run the full app build, not just
  module tests.
- **Repaint races**: a track change or dismissal while the artwork load is in
  flight must not repaint stale art; key the cache and the callback to the
  metadata identity and ignore mismatches. Avoid a repaint loop on failure.
- **Memory**: cap the bitmap size (D-17 bucketing, ~512 px for the notification)
  and keep at most a small number of bitmaps.
- **Artwork for local providers**: `CoverImageKey` resolution supports local
  `content://` covers only through the Coil path; do not hand-roll a plain
  OkHttp fetch as the primary implementation.
- **Test drift**: `PlaybackNotificationProviderTest:126` currently asserts the
  buggy importance; changing it is part of the fix, not a weakened assertion —
  state that in the commit/progress note.
- **No device available in the investigation session**: all visual/silence
  claims remain `[UNKNOWN device]` until the acceptance run.

## Unresolved questions

1. **Loader seam shape**: app-provided Coil loader (recommended) vs a
   `:playback`-local loader over `ProviderClientRegistry` + `BitmapFactory`.
   The second avoids the app binding but loses the cover cache and local
   `content://` support. Decide before writing code; if the app binding is
   chosen, verify `:app:assembleDebug`.
2. **Extras vs `artworkUri`** for the identity. Extras keep provider-routed
   loading; `artworkUri` would also feed external controllers but needs a
   resolvable URL at metadata-build time. Recommended: extras.
3. **Repaint cadence**: whether to pre-warm the artwork when a track becomes
   current (before the notification is created) or accept one no-art frame and
   the callback repaint. The latter is simpler and matches media3's own default
   provider.
4. **Channel name copy**: keep "Playback" as the channel display name; confirm
   no new user-facing wording is needed for the v2 channel (a duplicate name
   with a v2 id is invisible to users, and the legacy one is deleted).

## Acceptance criteria

- [ ] A playing track's notification carries the song's cover as the large
      icon, including after a track change, with no extra alert.
- [ ] No sound is emitted on notification creation, updates, or the first
      appearance of a new track; the channel is `IMPORTANCE_LOW` and the legacy
      channel is gone.
- [ ] Settings contains a row that opens the system notification settings for
      the app; the media notification remains exempt from
      `POST_NOTIFICATIONS` and this behaviour is documented rather than
      "fixed".
- [ ] Existing action/slot/metadata/notification-id behaviour is unchanged.
- [ ] Full JVM gate, banned-name scan and the device checklist above are
      recorded.
- [ ] `progress/KNOWN_ISSUES.md` P1 notification items are updated with the observed
      result; they are not closed until the device acceptance pass passes.

## Where implementation starts

1. Read `service-and-notifications.md` and this brief.
2. Implement **A2 (channel)** first: it is isolated, and its test change is
   mechanical (`PlaybackNotificationProviderTest` channel case).
3. Add the metadata identity (A1 step 1) and its bridge/metadata test.
4. Add the loader seam + `:app` binding, then the provider artwork path and the
   repaint test.
5. Add the Settings row (A3) and its intent test.
6. Run the gate; then the device checklist.
7. Update `progress/KNOWN_ISSUES.md`, `progress/STATE.md`,
   `progress/CHANGELOG.md`, this brief, `service-and-notifications.md`,
   `.agents/memory/findings.md`, and only then add the decision entry to
   `docs/omnisonic/decisions.md` (the user asked to wait until the fix lands).
