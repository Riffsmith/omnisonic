# Notification artwork quality — `notification-artwork-quality.md`

Purpose: Hand off the `progress/KNOWN_ISSUES.md` P1 report that the cover art in
the media notification is **very blurry** on the Android 13 test device, which
draws the notification's artwork in a square panel card. This document pins the
cause, the chosen fix (publish the cover as session metadata artwork so
SystemUI stops using the platform-shrunk large icon), the files to change, the
tests and the device gate. It is a standalone bug fix, **not Phase 6 and not
part of any phase**.

How derived: source reading of `PlaybackNotificationProvider`,
`NotificationArtworkLoader`/`NotificationArtworkMetadata`, `QueueAwareBridge`,
`OmniForwardingPlayer` and `OmniPlaybackService`; then the platform path the
bitmap takes after the notification is posted — AOSP 13 `Notification.Builder`
size reduction, SystemUI `MediaDataManager` artwork precedence,
`MediaControlPanel` binding, and the media3 1.11.1 legacy-session conversion
(`MediaSessionLegacyStub`, `LegacyConversions`, `MediaSessionCompat`) that
feeds the session metadata to SystemUI. Android platform sources and
`androidx/media` at tag `1.11.1` are the external references. No device was
attached during the investigation (2026-11-18); `adb devices` was empty.

Confidence:
- [VERIFIED] the notification's cover exists only as `setLargeIcon`; the session
  metadata the app publishes carries no artwork data or URI.
- [VERIFIED] `Notification.Builder.build()` shrinks that large icon to
  `notification_right_icon_size` = 48 dp before the notification is posted, and
  `MediaStyle` does not override the reduction.
- [VERIFIED] Android 13 SystemUI reads artwork from the session metadata first
  (art URIs → `METADATA_KEY_ART` → `METADATA_KEY_ALBUM_ART`) and only then falls
  back to `Notification.getLargeIcon()`.
- [VERIFIED] media3 publishes a session `MediaMetadata.artworkData` bitmap into
  the compat metadata as `METADATA_KEY_ALBUM_ART`, and the compat metadata is
  that same object the platform session exposes to SystemUI.
- [INFERRED] the reported blur is the 48 dp large icon upscaled into the 88 dp
  recommendation card (and smeared across the card background).
- [UNKNOWN] whether the device's OEM SystemUI follows the AOSP lookup order
  exactly; and the post-fix appearance everywhere.

Date: 2026-11-18

Links: `playback/service-and-notifications.md` (notification presentation
contract) · `playback/notification-presentation-fixes.md` (D-28, the fix that
added the artwork loader and the silent channel) · `ui/style-tokens.md` ·
`progress/KNOWN_ISSUES.md` P1 notification block · decision D-32 (to be written
when the fix lands).

---

## Symptom (from `progress/KNOWN_ISSUES.md` P1)

- "The image of the cover art shown in the notification panel is very blurry
  compared to the original image. The device is on Android 13 and uses a square
  box in which it displays the cover art."
- Same track, same server: the artwork in the app's own now-playing surface is
  sharp, so the source image and the cover pipeline are not the problem.
- Reported after D-28 added the notification artwork, the silent channel and the
  Settings row; none of those pieces asked for a resolution.

## Phase boundary (read before coding)

- Standalone P1 fix. Phase 6 has not started; no phase status, task checkbox or
  roadmap item changes.
- No commit, push or PR without explicit permission.
- The clean-room rule applies to the new code and to every doc line: borrowed
  behaviour only, Omnisonic naming only.
- Scope is exactly this defect. Local-file artwork (`LocalMediaProvider` has no
  cover URL), notification actions, channel and Settings behaviour are out of
  scope.

## Implementation status

**Implemented and JVM-verified (2026-11-18); device acceptance pending.** The
approved design landed in `:playback`: `SessionArtworkPublisher` +
`encodeSessionArtworkPng`, the provider's `Artwork(bitmap, png)` cache with
both publish call sites, the bridge's looper-posted artwork metadata, and
`OmniForwardingPlayer` delegating. No DI, build, dependency or manifest change.

One implementation bug was found and fixed rather than worked around: the
first publish call read `session.player` **after** the `Dispatchers.Default`
encode hop, and media3 verifies the application thread on that getter, so the
repaint coroutine died with `IllegalStateException: Player callback method is
called from a wrong thread`. The publisher is now resolved once in
`createNotification` (application thread) and passed down. The bridge's no-op
guard also runs a second time on the looper, where the state is written, so a
stale cross-thread fast-path read cannot cause a duplicate republish.

Device verification is impossible from this session (`adb devices` empty), so
every visual claim below stays `[UNKNOWN device]` until the checklist runs.

### Verification recorded 2026-11-18

- `[VERIFIED JVM]` `:playback:test` — 275 tests, 0 failures.
  - `PlaybackNotificationProviderTest` (15): all 14 pre-existing cases plus
    fresh-load publish, cache-hit republish with the same byte instance, stale
    load publishes nothing, missing cover publishes nothing and is not retried.
  - `OmniForwardingPlayerTest` (10): all 7 pre-existing cases plus current-only
    artwork + identity extras, repeat-publish idempotence, byte replacement,
    and dropping artwork when another entry becomes current.
  - `SessionArtworkPngTest` (2): real PNG signature + decode + dimensions, and
    the 512 px request below the 320 dp media3 session cap at 420 dpi.
- `[VERIFIED JVM]` full gate `./gradlew :app:assembleDebug test lint` — exit 0
  (1162 tests across modules, 0 failures, 4 pre-existing skips).
- `[VERIFIED]` `scripts/check-banned-names.sh` on every touched file — exit 0.
- `[VERIFIED]` no Roborazzi golden was re-recorded for this fix.
- `[UNKNOWN device]` every item in the device checklist below.

---

## Evidence

### E1 — the cover reaches the notification as a large icon only [VERIFIED by source]

`PlaybackNotificationProvider.createNotification` sets title/artist/subtext,
the three fixed transport buttons, the configured slots, and:

```kotlin
val cachedArt = identity?.let { artworkCache[it] }
if (cachedArt != null) builder.setLargeIcon(cachedArt)
```

That is the whole artwork surface. `NotificationArtworkMetadata.REQUEST_SIZE_PX`
is 512, so the bitmap handed to `setLargeIcon` is 512 px — plenty. Nothing else
carries the image: `QueueAwareBridge.metadataFor(entry)` emits title, artist and
the cover **identity** as extras, and `MediaMetadata` has no
`artworkData`/`artworkUri` anywhere in the repo (`grep artworkData` is empty
before this fix).

### E2 — the platform destroys that bitmap at `build()` time [VERIFIED against AOSP 13]

`android.app.Notification.Builder.build()` ends with
`mN.reduceImageSizes(mContext)` (`Notification.java:6571`), and
`Notification.reduceImageSizes` scales a large icon down to
`notification_right_icon_size` (`Notification.java:6777-6813`). In AOSP 13 that
resource is **48 dp** (`core/res/res/values/dimens.xml:771`).

Styles can raise the cap, but `MediaStyle` does not: the base
`Notification.Style.reduceImageSizes` is an empty method
(`Notification.java:7237`) and `MediaStyle` inherits it. The 512 px bitmap is
therefore reduced to 48 dp *before the notification is posted*; the posted
`Notification` carries the small copy, and `EXTRA_REDUCED_IMAGES` makes the
reduction permanent.

At the device's density (~420 dpi, 2.625×) 48 dp ≈ **126 px**.

### E3 — Android 13 SystemUI prefers session metadata artwork, then the large icon [VERIFIED against AOSP 13 SystemUI]

`MediaDataManager.loadMediaDataInBg` picks the card artwork in this order
(`packages/SystemUI/.../media/MediaDataManager.kt:639-649`):

1. a bitmap decoded from `METADATA_KEY_ALBUM_ART_URI`, `ART_URI`,
   `DISPLAY_ICON_URI` (`ART_URIS`, `:76-80`; decoded at `:976-988`);
2. `metadata.getBitmap(METADATA_KEY_ART)`;
3. `metadata.getBitmap(METADATA_KEY_ALBUM_ART)`;
4. `notif.getLargeIcon()` — the fallback.

The app hits branch 4. The URI branch cannot rescue it either: the loader accepts
only `content`, `android.resource` and `file` schemes
(`MediaDataManager.kt:1004-1013`), while provider covers are credential-salted
HTTPS URLs and the app has no content provider for cover files.

### E4 — the card upscales the 48 dp bitmap [VERIFIED dimensions + code]

`MediaControlPanel.bind` uses `data.getArtwork()` for the album view and for
`getScaledBackground()` (`MediaControlPanel.java:633-638`, `:1259+`). The
recommendation card's art slot is `qs_media_rec_album_size` = **88 dp**
(`packages/SystemUI/res/values/dimens.xml:1002`), i.e. ≈ 231 px here — a ~1.8×
upscale of a bitmap the platform already resampled once. The same bitmap feeds
the card's colour extraction and background, so the smear is visible beyond the
thumbnail. [INFERRED that this is the exact visual the report describes; the
density maths is [VERIFIED], the OEM rendering is not.]

### E5 — a session `artworkData` bitmap is exactly what branch 3 reads [VERIFIED against media3 1.11.1]

When the session metadata changes, `MediaSessionLegacyStub` loads the session
bitmap with `sessionImpl.getBitmapLoader().loadBitmapFromMetadata(metadata)`
(`MediaSessionLegacyStub.java:1783`) and sets compat metadata through
`LegacyConversions.convertToMediaMetadataCompat(metadata, …, artworkBitmap)`
(`:1827`). That conversion puts the bitmap on
`METADATA_KEY_DISPLAY_ICON` **and** `METADATA_KEY_ALBUM_ART`
(`LegacyConversions.java:744-747`).

`MediaSessionCompat.MediaSessionImplApi21.setMetadata` then forwards the compat
metadata to the platform session through
`MediaMetadataCompat.getMediaMetadata()`, which parcels the compat bundle and
reads it back as `android.media.MediaMetadata` (bytecode: the method
`MediaMetadataCompat.getMediaMetadata()` builds the framework object from
`writeToParcel`). Bitmap keys survive that trip, so SystemUI's
`getBitmap(METADATA_KEY_ALBUM_ART)` sees our image.

The default `DataSourceBitmapLoader` decodes `artworkData` locally, so no second
network request is involved. media3 also caps session artwork at
`config_mediaMetadataBitmapMaxSize` = **320 dp**
(`MediaSession.java:2847` + AOSP `core/res/res/values/config.xml:3441`), ≈ 840 px
here — a 512 px source passes through untouched.

Corroboration: `androidx/media#168` ("if the largeIcon is used, the image is
scaled down"), `androidx/media#3084` (SystemUI uses the bitmap from the
MediaSession directly), and Expo's `826d655` (Android 13 lock-screen artwork
fell back to a low-resolution large icon when session artwork was absent).

### E6 — the picture lifecycle in this app [VERIFIED by source]

- `QueueAwareBridge` is a `SimpleBasePlayer` bound to the engine application
  looper; the exposed current item's `MediaMetadata` *is* the session metadata.
  `QueueAwareBridge` already calls `invalidateState()` from `markReleased` and
  `publishError`, so republishing after a state edit is an established pattern.
- `SimpleBasePlayer.invalidateState()` verifies the application thread
  (`SimpleBasePlayer.java:3172`) and returns early while a synchronous player
  operation is pending; the pending operation's completion republishes state, so
  the artwork edit is not lost.
- `MediaMetadata.Builder.setArtworkData` **clones** the byte array
  (`libraries/common/.../MediaMetadata.java:243-247`), and
  `mediaItemDataFor`/`metadataFor` run on every `getState()` — that is, on every
  position tick. Rebuilding the artwork metadata per tick would allocate the
  whole image once a second; the current entry's metadata must be cached per
  published artwork.
- The provider's repaints run on `@PlaybackEngineScope`
  (`Dispatchers.Main.immediate`) and the media session thread is that same
  looper, so publishing from the provider needs no extra thread hop — but it
  must not re-enter `createNotification`.

### Test coverage today [VERIFIED]

- `PlaybackNotificationProviderTest` — 14 cases (channel, legacy-channel
  deletion, cache-hit art, null-result non-fatal, stale identity, configured
  slots, actions). No case asserts anything about the session metadata.
- `OmniForwardingPlayerTest` — 7 cases, including the metadata identity extras.
  No `artworkData` case.
- No test pins the notification large icon's *platform-reduced* state; that is
  an AOSP behaviour, not ours to test.

---

## Why raising the request size cannot fix it

The bitmap is already 512 px at `setLargeIcon`. The loss happens afterwards, in
`Notification.reduceImageSizes`, and is unconditional for the large-icon slot.
A 1024 px request would be reduced to the same 48 dp. The only way to hand
SystemUI a larger bitmap is to stop routing the image through the notification
at all — which is what the session metadata does.

## Chosen design

Publish the cover as **session metadata artwork data** in addition to the
existing large icon:

1. The provider, which already loads and caches the bitmap, publishes it;
2. the bridge attaches the bytes to the **current** entry's metadata and
   republishes state;
3. SystemUI picks branch 3 (`METADATA_KEY_ALBUM_ART`) at 512 px and the card is
   sharp;
4. `setLargeIcon` stays as the pre-13 path and as the fallback if a SystemUI fork
   ignores session artwork.

Sizes and encoding (user-approved 2026-11-18):

- **512 px stays.** No change to `NotificationArtworkMetadata.REQUEST_SIZE_PX`;
  one bitmap serves both the large icon and the session artwork, so no extra
  request, no extra cache entry, and 512 px is below media3's 320 dp limit.
- **PNG.** Lossless re-encode of the already-decoded bitmap
  (`Bitmap.CompressFormat.PNG`). JPEG would be smaller, but album art is opaque,
  the encode is off the session thread either way, and PNG avoids a second
  lossy generation and a quality decision. The session loader decodes either
  format.

### Seam

New file `playback/src/main/java/app/omnisonic/playback/service/SessionArtworkPublisher.kt`:

```kotlin
/**
 * Publishes cover artwork onto the exposed session metadata so Android 13+
 * SystemUI stops rendering the platform-reduced notification large icon.
 * Implementations post to the player's application looper and are idempotent
 * for an unchanged identity.
 */
fun interface SessionArtworkPublisher {
    fun publishSessionArtwork(identity: NotificationArtworkIdentity, png: ByteArray)
}
```

- `internal` visibility is enough (provider and player live in `:playback`).
- `OmniForwardingPlayer` (already `internal`, and it *is* the player bound to the
  session) implements it by delegating to the bridge. `createNotification`
  obtains it with `session.player as? SessionArtworkPublisher`, so no Hilt
  binding, no constructor cycle, and tests with fake players simply no-op.
- Rejected: injecting the publisher into the provider constructor (the player is
  assembled after the provider in the graph, and the session is the honest
  source), and having the bridge pull from a provider-owned holder (the bridge
  would have to encode on the wrong thread).

### Bridge changes (`QueueAwareBridge`)

State:

```kotlin
private data class SessionArtwork(
    val identity: NotificationArtworkIdentity,
    val png: ByteArray,
    val metadata: MediaMetadata,   // built once per artwork, see E6
)

private var sessionArtwork: SessionArtwork? = null
private val looperHandler = Handler(looper)
```

Publish (called from the provider on any thread):

```kotlin
fun publishSessionArtwork(identity: NotificationArtworkIdentity, png: ByteArray) {
    if (sessionArtwork?.let { it.identity == identity && it.png === png } == true) return
    looperHandler.post {
        sessionArtwork = SessionArtwork(identity, png, artworkMetadataFor(identity, png))
        invalidateState()
    }
}
```

- Reference equality on the bytes is the idempotence guard: the provider hands
  over its cached array instance, so a rebuild after a metadata change cannot
  loop. (Value equality would cost a 512 KB compare per repaint for no gain.)
- Posting, rather than applying inline, keeps `invalidateState()` off the
  provider's `createNotification` stack; `SimpleBasePlayer` republishes to the
  session, which triggers one more notification build, which then publishes a
  no-op.

Metadata attachment — `metadataFor(entry)` becomes
`metadataFor(entry, isCurrent)` and reuses the cached metadata for the current
entry:

```kotlin
private fun metadataFor(entry: PlaybackQueueEntry, isCurrent: Boolean): MediaMetadata {
    sessionArtwork?.let { art ->
        if (isCurrent && art.identity.matches(entry)) return art.metadata
    }
    // unchanged builder: title, artist, identity extras
}
```

with `matches` comparing `providerId` and `imageId` only (the entry has no
`sizePx`; `sizePx` stays a provider-side request concern). `mediaItemDataFor`
passes its existing `isCurrent` flag. The cached metadata is immutable
`MediaMetadata`, so sharing the instance with the `MediaItem` and the
`MediaItemData` is safe.

Result: exactly one entry in the exposed timeline carries artwork bytes; the
current item's metadata is rebuilt at most once per artwork, not per position
tick.

### Provider changes (`PlaybackNotificationProvider`)

- Cache entry becomes `Artwork(bitmap: Bitmap, png: ByteArray)` so a cached
  repaint never re-encodes.
- Fresh load path (`requestArtwork`): after the existing
  `lastIdentity != identity` guard, encode once on `Dispatchers.Default`
  (`Bitmap.compress(PNG, 100, stream)`), store the pair, then repaint **and**
  publish.
- Cache-hit path (`createNotification`): `setLargeIcon(art.bitmap)` as today,
  plus publish `art.png` — this is what restores artwork when the user returns
  to a track whose art is still cached but no longer published (the bridge only
  holds the latest identity).
- Failure path unchanged: no publish, one log, no retry.

Sequence on a track change: queue state change → bridge rebuilds the timeline
without artwork for the new current item → session metadata change → provider
`createNotification` (no cache) → load → encode → repaint the notification and
publish → bridge posts, attaches `artworkData`, republishes → SystemUI updates
the card. Artwork therefore appears one metadata cycle after the notification
repaint — the same visible latency as today, without a second fetch.

### Rejected alternatives

- **`artworkUri` on the metadata.** Provider covers are salted HTTPS URLs; the
  session's `BitmapLoader` would fetch them with its own client (no provider
  headers, no media profile), duplicating traffic and usually failing auth.
  SystemUI's URI branch also rejects `http` outright (E3).
- **A `content://` cover provider.** It would let SystemUI decode full-size art
  itself and avoids the bitmap in the session payload, but needs a new exported
  or grant-managed provider plus URI-permission plumbing for
  `com.android.systemui`, and a file cache for covers. More moving parts than
  the defect justifies; revisit only if the bitmap payload causes trouble (see
  risks).
- **A bigger large icon / a custom notification layout.** The large icon is
  reduced by the platform regardless (E2); a custom layout is not what the
  media card renders.
- **Relying on SystemUI to decode the existing 48 dp icon better.** No such
  seam exists.

## Out of scope

- Local-file playback: `LocalMediaProvider.coverArtUrl` returns `null`, so a
  local track has no notification artwork at all (pre-existing, D-28-era gap).
  Worth a separate P2 item; this fix must not pretend to solve it.
- The notification's own expanded row rendering, actions, channel, Settings
  row, and the pre-13 appearance.
- Any artwork prefetch for the next queue entry (the provider's one-load-per-
  identity rule stands).
- The card's background effect itself: it is SystemUI's `getScaledBackground`
  from the same bitmap, so it sharpens automatically.

## Files likely to change

| File | Change |
|---|---|
| `playback/src/main/java/app/omnisonic/playback/service/SessionArtworkPublisher.kt` | new seam (fun interface + KDoc) |
| `playback/src/main/java/app/omnisonic/playback/service/PlaybackNotificationProvider.kt` | `Artwork(bitmap, png)` cache, PNG encode on `Dispatchers.Default`, publish on load and on cache hit, `session.player as? SessionArtworkPublisher` |
| `playback/src/main/java/app/omnisonic/playback/player/OmniForwardingPlayer.kt` | implement `SessionArtworkPublisher`, delegate to the bridge |
| `playback/src/main/java/app/omnisonic/playback/player/QueueAwareBridge.kt` | `SessionArtwork` state, looper-posted publish, `metadataFor(entry, isCurrent)` with the cached current-item metadata |
| `playback/src/test/java/app/omnisonic/playback/service/PlaybackNotificationProviderTest.kt` | publisher fake + publish/no-publish cases |
| `playback/src/test/java/app/omnisonic/playback/service/SessionArtworkPngTest.kt` | new: PNG encode/decode round-trip (`@GraphicsMode(NATIVE)`) |
| `playback/src/test/java/app/omnisonic/playback/player/OmniForwardingPlayerTest.kt` | `artworkData` attachment, identity extras, current-only, idempotence |

No DI, build, dependency or manifest change is expected. `:app:assembleDebug`
still runs in the gate to prove the graph is untouched.

## Testing and verification

### JVM

`PlaybackNotificationProviderTest` (extend, keep the 14 existing green):

- a cache hit publishes the cached identity **and** the same bytes instance it
  puts on the large icon;
- a fresh load publishes exactly once, after the notification repaint, with the
  identity that is still current;
- a stale load (identity swapped while the load was in flight) publishes
  nothing;
- a failed load publishes nothing and stays non-retrying.

`OmniForwardingPlayerTest` (extend, keep the 7 existing green):

- after `publishSessionArtwork`, the session's current `mediaMetadata`
  `artworkData` equals the published bytes and the identity extras are intact;
- only the current entry's item carries `artworkData`; the other timeline items
  do not;
- publishing the same identity and the same array instance does not emit a
  second `onMediaMetadataChanged` (idempotence, no repaint loop);
- a second identity replaces the bytes on the current item; a queue change that
  selects another entry drops the artwork from the exposed metadata.

`SessionArtworkPngTest` (new): `Bitmap.compress(PNG)` output is non-empty,
decodes back to the same dimensions, and the 512 px case stays within the
`config_mediaMetadataBitmapMaxSize` expectation used by the session loader.
If native graphics is not on `:playback`'s test classpath, this is the one
place to add the test-only dependency or to move the case into a native-graphics
class — verify before writing the assertions, and do not weaken them into
"bytes are non-null".

Gate:

```
./gradlew :playback:test :app:assembleDebug test lint
export OMNISONIC_BANNED_PATTERNS='…'   # from git config/CI secret
scripts/check-banned-names.sh <touched files>
```

No Roborazzi golden changes are expected (no Compose surface changes); do not
re-record goldens for this fix.

### Device (`[UNKNOWN device]` until run)

Run on the Android 13 test device with a Navidrome session:

- the card's artwork is sharp at the card's own size, visually comparable to the
  app's now-playing cover;
- track change: art swaps within the usual notification latency, never shows
  the previous track's art, no flash of the blurry icon after the first cycle;
- pause/play, shuffle, next/previous do not clear or restore the art
  incorrectly;
- the fallback path still looks right where SystemUI uses the notification
  large icon (pre-13 device or a launcher/SystemUI that ignores session art);
- the playback service does not crash or log `TransactionTooLargeException`
  during ~10 track changes (see risks);
- lock screen and shade agree; no extra alert or sound;
- `dumpsys media_session` shows the current session's metadata carrying an
  album-art bitmap (`METADATA_KEY_ALBUM_ART`).

## Risks and constraints

- **OEM SystemUI divergence.** realme UI's SystemUI is an AOSP fork; if it reads
  only `METADATA_KEY_ART` (media3 fills `DISPLAY_ICON` + `ALBUM_ART`, not `ART`),
  the card keeps using the large icon. The device pass decides; the documented
  fallback is the rejected `content://` provider, not a cramped hack.
- **Bitmap in the session payload.** Each metadata update parcels a 512 px
  bitmap to `system_server`. Android transfers large bitmaps out of band, and
  512 px sits below the platform's own 320 dp cap, but this is the one
  observable risk. If the device pass shows transaction failures, drop
  `REQUEST_SIZE_PX` to 320 (a one-constant change) before considering a provider.
- **Repaint loop.** Guarded twice: the bridge ignores an unchanged
  identity+instance, and the provider never `requestArtwork`s for a cached
  identity.
- **Allocation churn.** `setArtworkData` clones bytes and `getState()` runs on
  every tick; the cached `MediaMetadata` per artwork is what keeps this to one
  build per track (E6). Do not "simplify" it back into `metadataFor` without
  caching.
- **Reentrancy.** Publishing must be looper-posted, never inline from
  `createNotification`.
- **Pre-13.** Unchanged behaviour; `setLargeIcon` still drives the media
  notification there.
- **No second fetch** is a hard requirement: the encode must consume the bitmap
  the loader already produced, not re-request the cover.

## Remaining open questions (non-blocking; recommended default in brackets)

1. **Local covers**: does a local-provider notification artwork path belong in
   this fix or a separate P2? [Separate item; `LocalMediaProvider.coverArtUrl`
   returning null is the gap, not the notification.]
2. **Payload size**: keep 512 px for every density, or derive it from the card's
   88 dp × density like `CoverSizing` does? [Keep 512; the platform cap is far
   above it and a second size class would duplicate cache entries.]
3. **Artwork for the *next* entry**: prefetch so a track change is instant?
   [No; one load per current identity, matches D-28 and the provider cache.]
4. **Decision id**: D-32 (D-31 is reserved for the queue brief). [Write the row
   only when the fix lands, matching the queue decision's rule.]

## Acceptance criteria

- [x] `[VERIFIED JVM]` the current entry's exposed `MediaMetadata` carries the
      512 px cover as `artworkData`, and only the current entry's does; the
      platform-reduced large icon is no longer what the card should display on
      Android 13+ `[UNKNOWN device]` for the rendered result.
- [x] `[VERIFIED JVM]` `setLargeIcon` remains for pre-13 and as a fallback; no
      pre-13 behaviour change (the same bitmap still paints the large icon).
- [x] `[VERIFIED JVM]` exactly one cover encode per fresh track, zero extra
      network requests (the encode consumes the loader's bitmap), and a cache
      hit republishes the cached byte instance.
- [x] `[VERIFIED JVM]` no repaint loop, no duplicate `onMediaMetadataChanged`
      for an unchanged artwork instance, and no artwork bytes on non-current
      timeline items.
- [x] `[VERIFIED JVM]` all pre-existing `PlaybackNotificationProviderTest` (14)
      and `OmniForwardingPlayerTest` (7) cases stay green (15 and 10 now), the
      new cases pass, `:playback:test` is 275/275, the full gate and the
      banned-name scan are clean, and no golden changed.
- [ ] Device checklist recorded on the Android 13 device; the
      `progress/KNOWN_ISSUES.md` P1 item stays **open** until it passes.
      `[UNKNOWN device]` — `adb devices` was empty on 2026-11-18.
- [x] Docs updated with the result: `service-and-notifications.md`
      presentation contract, this brief's status, README row, `progress/STATE.md`
      and `CHANGELOG.md`, `.agents/memory/findings.md`; D-32 in `decisions.md`.

## Where implementation starts

1. Read `service-and-notifications.md` §"Notification presentation contract"
   and this brief; re-confirm the git tree before touching code.
2. Add `SessionArtworkPublisher` and the bridge state/publish path, with
   `OmniForwardingPlayer` delegating — the metadata half is testable without the
   provider (`OmniForwardingPlayerTest`).
3. Extend `QueueAwareBridge.metadataFor` with the current-entry cached metadata;
   verify the identity extras still round-trip (`OmniForwardingPlayerTest`).
4. Switch the provider cache to `Artwork(bitmap, png)`, add the
   `Dispatchers.Default` encode and both publish call sites; extend
   `PlaybackNotificationProviderTest`.
5. Add `SessionArtworkPngTest` (resolve the native-graphics question first).
6. Run the gate, then the device checklist; record the outcome in the docs and
   only then write D-32.
