# Playback controller and event bus — `controller-and-events.md`

Purpose: Pin the command hub that UI, media session and notifications talk to:
its assembly, transport/focus wiring, position ticker, the event bus contract,
and the queue-position persistence round trip that rides on it.

How derived: playback-api contracts (`PlaybackController`, `PlaybackEvents`,
`QueuePlayer`, `PlaybackRenderer`), the implementations in
`playback/controller/`, `playback/events/`, `playback/renderer/`,
`playback/queue/` (`QueuePlayerImpl`, `QueuePersistenceCoordinator`), and the
tests in this repository (`PlaybackControllerImplTest`, `PlaybackEventBusImplTest`,
`LocalPlaybackRendererTest`, `QueuePlayerImplTest`, `QueuePersistenceTest`,
`QueuePersistenceCoordinatorTest`). Evidence categories: own-source reading, in-repository test runs.

Confidence:
- [VERIFIED] `PlaybackEventBusImpl` is a `MutableSharedFlow` with replay 0, a
  buffered overflow policy of drop-oldest (capacity 64), so producers never
  suspend and late subscribers see no history; collectors receive events in
  emission order (bus tests).
- [VERIFIED] `PlaybackControllerImpl` assembles `QueuePlayer` + the local
  `PlaybackRenderer`'s `OmniPlayer` + `AudioFocusHelper` (through the
  `FocusOwnerFactory`/`AudioFocusOwner` seams) + `QueuePersistenceCoordinator`.
  Transport commands flow controller → queue machine → player; queue
  events (track changed / finished / skipped) flow queue → bus.
- [VERIFIED] Focus policy: focus is requested on `play`/`resume` and on skip
  commands that start a session; playback starts only when the request is
  granted; focus is abandoned when the controller goes idle (empty queue,
  natural queue end, explicit stop). Focus callbacks map to
  `onDuck`→`setDuckLinear` + `PlayerCoreState.ducked`, `onPauseForFocus`→pause
  (reason FOCUS, no snapshot flush), `onResumeAfterFocus`→resume.
- [VERIFIED] Position ticker: while a playback session is intended, a coroutine
  in the controller scope ticks every 500 ms, reads the engine position through
  an injectable reader, publishes `PlayerCoreState.positionMs`, emits
  `PlaybackEvent.Position`, and feeds `QueuePlayer.onPositionTick`. Pausing,
  stopping, and queue end cancel it.
- [VERIFIED] Resume-after-idle semantics: when the engine holds no current item
  (restored queue, natural queue end, explicit stop), `resume()` replays the
  queue's current slot through `QueuePlayer.replayCurrent()` instead of
  `fadeResume()`, so the pinned slot and its pending resume position are used.
- [VERIFIED] Queue-position persistence (schema v1): `QueuePlayerImpl`
  publishes `QueueState.positionMs` (pending restore position, or the live
  position once past `minResumePositionMs` = 10 s and strictly inside the
  track; 0 otherwise) with a 5 s publication granularity, so the coordinator's
  debounced snapshot carries it. `QueuePersistence` maps 0 → NULL. Restore
  clamps the position to `[0, durationMs]`, drops values below the threshold,
  and re-applies it at the next start via `ResolvedStream.startPositionMs`.
  User seeks update the pending restore point while idle and clear it while
  playing.
- [VERIFIED] Launch restore (task 7c): `PlaybackLaunchRestore`
  (`playback/.../startup/`) is called from `OmnisonicApplication.onCreate`,
  which launches a bounded (2 s) `restoreQueue()` on the engine scope — the
  shell only uses the in-process controller, so nothing else would apply the
  snapshot at launch. `restoreQueue()` applies a snapshot **at most once per
  process**: `restoreAttempted` is set before the first suspension, and a call
  made when the in-memory queue already holds entries returns `false` too (a
  play may have landed first). Every later caller — notably the media service
  starting after 7e's launch connect binds it — gets `false` and the live
  queue is untouched; both restore paths (app init and service `onCreate`)
  therefore coexist safely. Idle restore still
  sets `playIntent = false` and requests no focus; only a play/resume command
  starts audio and takes focus.
- [VERIFIED] `flush()` call sites (deferral 2): the controller flushes the
  snapshot on explicit pause and on stop; the service adds stop/background
  call sites in T11.
- [VERIFIED] The exposed player's `playWhenReady` requests route through
  `QueueCommandPort.onPlayWhenReady` (controller transport); the port's default
  returns false so a bare `OmniPlayer` consumer keeps direct engine control
  (`OmniForwardingPlayerTest`).
- [VERIFIED] Scrobble points: the controller feeds `ScrobblePointTracker` from
  its event collector; one `ScrobblePoint` per played playback at 95% of the
  duration (playing positions only), re-armed on Started and non-terminal
  TrackChanged, with a natural finish counting as a full play. No history is
  written in Phase 4 — see `stream-resolution.md`.
- [INFERRED] `PlaybackController.play` emits `Started` at command time (the
  queue machine starts the engine asynchronously), not at engine READY.
- [UNKNOWN] On-device behavior: ticker cost under Doze, focus-interrupt
  interactions with the notification, kill-relaunch restore timing. Needs
  hardware (Phase 4 deferred acceptance).

Date: 2026-11-07

Links: `audio-focus.md`, `exposed-player.md`, `service-and-notifications.md`,
`../storage/schema-v2.md`, `../decisions.md`

---

## Assembly

```
PlaybackControllerImpl(scope, renderer, player, queue, events, persistence,
                       focusOwnerFactory, queueSources, tickIntervalMs, positionReader)
  ├── QueuePlayerImpl(engineScope, player, streamResolver, bus, prevThreshold, minResumeMs)
  ├── LocalPlaybackRenderer { playerFactory }        // activate() → OmniPlayer
  ├── AudioFocusHelper(context, callbacks, settings, focusScope)  // as AudioFocusOwner
  └── QueuePersistenceCoordinator(store, scope)      // observe(queue.state), flush()
```

## Event responsibilities

| Event | Producer |
|---|---|
| `Started` / `Resumed` / `Paused(reason)` | controller transport commands |
| `Position` | controller ticker + explicit seeks |
| `TrackChanged` / `Finished` / `Skipped` | queue machine |
| `ScrobblePoint` | `ScrobblePointTracker` fed by the controller's event collector (see `stream-resolution.md`) |
