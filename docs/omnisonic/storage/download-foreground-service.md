# dataSync foreground download service — `download-foreground-service.md`

Purpose: pin the Phase 7 task-4 runtime contract — how the task-1 `DownloadWorkerCoordinator` and the real task-3 `DownloadTransferEngine` run inside a `dataSync` foreground service, what starts and stops the loop, which gates block claims, how retries wake without busy-spinning, and how wake/Wi-Fi locks, notifications and root changes behave.

How derived: the task plan `.agents/plans/phase-7-tasks.md` §TODO 4, own design decision D-42, the task-1 queue contract (`storage/download-queue-lifecycle.md`), the task-2 storage contract (`storage/offline-storage-layout.md`), the task-3 transfer contract (`storage/download-transfer.md`), and the shipped `:feature-downloads` `service` package plus its JVM/Robolectric tests. Behaviour is reasoned from Android's foreground-service and `Notification` APIs; no studied-app code or naming is involved.

Confidence: service/notification shape, trigger coalescing, gate behavior, retry scheduling, lock release, dynamic cap, root drain and the real three-worker engine run are [VERIFIED] by JVM/Robolectric tests. Device background-start denials, OEM process freezing, notification-permission denial, Doze and the platform `dataSync` timeout budget remain [UNKNOWN] without a device trace.

Date: 2026-11-20

Links: `storage/download-queue-lifecycle.md`, `storage/offline-storage-layout.md`, `storage/download-transfer.md`, `.agents/plans/phase-7-tasks.md`, D-42 in `../decisions.md`.

---

## Service and notification

- `DownloadService` (`:feature-downloads`) is `exported=false`, `foregroundServiceType="dataSync"`, `stopWithTask=false`; the app manifest already carries `FOREGROUND_SERVICE_DATA_SYNC` and `WAKE_LOCK`, and the feature manifest adds `ACCESS_WIFI_STATE` (the Wi-Fi lock reads radio state; `WAKE_LOCK` alone does not expose it). [VERIFIED merged debug + release manifests]
- `onCreate` creates the channel and calls `startForeground(1002, …, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` **before** any suspending database/provider work, so a slow start cannot miss the foreground deadline. [VERIFIED order: channel → notification → runtime start]
- Channel `omnisonic.downloads` is `IMPORTANCE_LOW`, silent, no badge, lockscreen-private; notification id **1002** is distinct from playback's `omnisonic.playback.v2`/`1001`. The small icon is a module-owned vector; there is no custom layout. [VERIFIED]
- Pause/resume actions are immutable `PendingIntent.getService` intents with fixed request codes (2101/2102); the content intent is an immutable launcher-activity intent (request code 2100) until task 9 adds a downloads deep link. [VERIFIED immutable identities + targets]
- Aggregate progress (`DownloadNotificationRules`) averages the known worker percentages, clamps to 0–100, and goes indeterminate when any worker reports `-1`, when a gate blocks, or while only pending rows exist. Titles distinguish downloading / waiting / paused / waiting-for-Wi-Fi / waiting-for-storage. [VERIFIED]

## Session and triggers

- `ForegroundDownloadRuntime` is the single process-wide session owner: one lazy `DownloadWorkerCoordinator` built from the persisted worker count, one `SupervisorJob` process scope, one conflated wake channel. Repeated starts only wake the existing session. [VERIFIED dedupe/coalescing]
- Startup recovery runs exactly once before the first claim; `recoverOnStartup()` also refuses to reset rows while any lease is live, so a second service instance cannot park this process's active rows. [VERIFIED]
- Triggers: committed enqueue/resume/retry through the `DownloadWorkStart` seam (production = `DownloadServiceStarter.start()` → `startForegroundService`), app foreground (`MainActivity.onStart`), coalesced default-network connectivity callbacks, and resume after a root change. A denied background start is caught (`IllegalStateException`/`SecurityException`) and logged; rows stay `PENDING`. [VERIFIED JVM; device denial UNKNOWN]
- The service stops itself when no `PENDING`/`ACTIVE` rows remain, and `onStartCommand` returns `START_STICKY` only while work remains (`START_NOT_STICKY` after the queue drains). There is no boot receiver and no battery-optimization exemption. [VERIFIED]
- `onTimeout` and `onDestroy` cancel the session; every worker releases its lease and locks in `finally`, so a platform `dataSync` timeout settles claims instead of crashing. [VERIFIED JVM; real timeout UNKNOWN]

## Gates and scheduling

- Global gates run before any claim: persisted Wi-Fi-only against the `NetworkMonitor` snapshot, then `OfflineStorage.prepare(0)` for root readiness plus the 100 MiB floor. Provider offline/auth/Wi-Fi gating stays in the coordinator's `ProviderRunGate` (production binding = `ProviderStatusTracker.runGate`, never the permissive default). Blocked rows stay `PENDING` with no error increment. [VERIFIED]
- Worker claims use `ProfileScope.Unrestricted` inside the runtime; the loop is an internal worker, so no profile filtering applies. [VERIFIED]
- After a counted failure the loop waits on the wake channel with a timeout equal to the earliest pending due delay (`errorCount × 10 s` anchor), so the sixth failure still parks `FAILED` and no timer/alarm/receiver is needed. [VERIFIED retry wake + final park]
- An uncounted `Blocked` result (e.g. active network loss) requeues the row without an error increment and parks the loop on an **external-trigger counter**, not on worker-settle wakes: only connectivity, app/enqueue triggers, settings changes, resume or root-resume can clear it. This is what prevents a busy spin. [VERIFIED no-spin + external wake]
- Worker-count changes apply live: increases admit new slots immediately, decreases never preempt running leases and only bound later claims. [VERIFIED]
- Each worker acquires a partial wake lock (bounded 6 h safety timeout) and, when the Wi-Fi radio is enabled, a `WIFI_MODE_FULL_HIGH_PERF` lock; both are unrefcounted, released exactly once in `finally` on every outcome including cancellation. [VERIFIED lock release; radio/Doze behavior UNKNOWN]
- The runtime is the production `OfflineRootWorkGate`: a root change stops new claims and waits for active leases to drain before publication/cleanup, then resumes the loop. [VERIFIED]

## Deliberately out of scope

`offline_media` publication, the missing-file/orphan sweep (task 5 adds it to the same pre-claim hook), MediaStore export, automatic rules, offline playback and any downloads UI. No schema or exported-baseline change; `Migrations.ALL` stays empty.

## Evidence

[VERIFIED JVM 2026-11-20] `:core-data` **586/0/0/0** (download package **181**, coordinator **40**); `:feature-downloads` **15/0/0/0** (`ForegroundDownloadRuntimeTest` 10, `DownloadNotificationRulesTest` 4, `DownloadTransferIntegrationTest` 1 — three workers through the real task-3 engine against MockWebServer); `:app` debug **166/0/0/0**, release **40/0/0/0** (download classes 9: service lifecycle 3, merged-manifest/notification 5, app-foreground trigger 1); `:app:assembleDebug` green; banned-name scan exit 0 and public-name diff vs `theme_keys.json` + THEMING.md §4 clean. Device FGS background starts, OEM freezing, notification-permission denial, Doze and the real `dataSync` timeout remain `[UNKNOWN]`.
