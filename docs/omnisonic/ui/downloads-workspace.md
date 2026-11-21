# Downloads workspace, entry points, and settings (Phase 7 task 9)

Date: 2026-11-21. Scope: task 9a–9d. No schema change (v3).

## Navigation

- One extend-only route, `downloads`, registered after the user confirmed the
  workspace shape. It is not a main tab and keeps the route-table contract
  (`ShellRoutes.ALL`); back pops to the previous destination.
- Three tabs inside the one route: **Queue**, **Offline**, **Rules**. The tab is
  view-model state, so switching tabs never creates another destination or
  another collection subscription.
- Settings > Downloads has an **Open downloads** entry. A download
  notification's content intent opens the same workspace through
  `app.omnisonic.action.OPEN_DOWNLOADS`; `MainActivity` handles the cold
  (`onCreate`) and warm (`onNewIntent`) cases. Device notification/deep-link
  feel remains `[UNKNOWN]`.

## Queue tab

- Rows are read through the profile-scoped workspace repository. Hidden
  providers are excluded; unavailable/offline providers are **not** excluded,
  because queued work must remain visible.
- Each row shows title, provider, status, progress (or an indeterminate bar when
  `progressPercent < 0`) and a sanitized error/retry state. The sixth counted
  failure (`FAILED && errorCount >= 6`) is marked parked; it never disappears.
- Row commands are explicit buttons: pause (pending/paused/active), resume
  (user-paused only), retry (failed only) and cancel (any known status). Cancel
  asks for confirmation. Unknown raw statuses fail closed: no command except
  reading.
- Global pause/resume uses the persisted gate and never rewrites a sticky
  user-paused row. Row commands are single-flight per row; global commands have
  their own gate, so a double tap cannot issue duplicate work.
- Stable lazy keys are the persisted queue row id.

## Offline tab

- Rows show permanent vs rolling, size, date and variant (bitrate when known).
- **Pin** writes `tempCache = false, autoRule = null`; **unpin** writes
  `tempCache = true, autoRule = null`. Neither enqueues, nor creates a queue row,
  nor relies on a duplicate enqueue succeeding. Delete asks for confirmation and
  goes through `OfflineMediaDeletion` (which also cancels an equivalent active
  queue row).
- Stable lazy keys are the persisted offline row id.

## Rules tab

- Favorites, playlist and album rule rows; add and remove are explicit
  controls, and removal is confirmed because the next verified pruning pass is
  destructive. Removing a rule only removes the rule and wakes the service; no
  file or queue row is touched from the UI.
- Candidate playlists/albums are read with the real profile scope, so hidden
  providers do not appear. Empty, loading and failure states are distinct.
- Internal reaction/pruning workers keep their `ProfileScope.Unrestricted`
  reads: hiding a provider changes this UI, never the background wanted set.

## Library entry points

- Album and playlist headers add: primary action (the persisted preferred
  destination), a keep-offline/export menu, and an automatic-download shortcut
  for that album/playlist. Each song row has its own keep-offline/export menu.
- The last explicit choice is persisted as the preferred destination; the
  primary action reads it. A failed write leaves the previous choice
  authoritative.
- Enqueue results are typed counts (queued, blocked by provider status,
  unsupported, destination conflict, empty). The header renders a short notice;
  partial batches stay partial by design. Background work is woken only after a
  commit.
- Song/album/playlist enqueues read their catalog rows unrestricted because the
  user is acting on a row already on screen; the UI itself is scoped where it
  lists rows.

## Settings: download settings and root management

- Settings > Downloads (new section) exposes parallel downloads (1–4, default 2)
  and Wi-Fi-only (default on). Writes go through the persisted
  `DownloadQueueSettingsStore`; the flow re-renders the stored value.
- Storage chips offer Automatic plus each reported app-private root. Tapping a
  different root opens a confirmation dialog; only **Move files** calls
  `OfflineStorage.changeRoot` with the runtime `OfflineRootWorkGate`. The move
  is verified and journaled by that layer — the UI never copies or deletes.
- While moving, an indeterminate progress row is shown; results render moved
  file count or a typed reason (move in progress, target/source unavailable,
  not writable, low space, destination conflict, invalid tree, copy failed,
  commit failed). A failed move keeps the previous active root.
- The UI reloads from persisted settings/root state; it does not assume a
  requested move succeeded. A pending/unavailable journal root is surfaced as
  an error rather than treated as a missing download.

## Accessibility and destructive actions

- Every destructive action (cancel, offline delete, rule removal, root move) has
  a visible button and a confirmation dialog; none is gesture-only.
- Icon-only controls (row overflow, header overflow) have content descriptions.
  TalkBack order and reachability on device remain `[UNKNOWN]`.

## Verification (JVM, 2026-11-21)

- `:core-data` **774/0/0/0** (`DownloadWorkspaceRepositoryTest` 4,
  `LibraryDownloadActionsTest` 6), `:feature-downloads` **29/0/0/0**
  (`DownloadsViewModelTest` 7), `:feature-settings` **70/0/0/0**
  (`DownloadSettingsViewModelTest` 7), `:feature-library` and `:app` suites
  green (169 app tests, including route back and the warm deep-link request).
  Cold `onCreate` handling is compile/Hilt-covered, not a separate navigation
  trace.
- `:core-data:testDebugUnitTest :app:assembleDebug` exit 0; lint on
  `:feature-downloads`, `:feature-library` and `:feature-settings` exit 0.
- Banned-name scan over all 72 touched files clean. Hilt assembly required
  extending the existing `FakeDownloadServiceModule` (the app unit-test component
  replaces the production download bindings) with the new root gate,
  cancellation, work-start, user-actions and enqueue bindings.
- Device layout, TalkBack, notification deep-link feel and real external-volume
  moves remain `[UNKNOWN]`.
- TODO 10 reproduced the full serial gate at 1,777 debug + 1,649 release
  results, zero failures/errors and four live-server skips per variant. The
  sticky-pause VM case proves UI/call behavior; coordinator tests prove the
  production resume-all transition. See `../storage/download-acceptance.md`.
