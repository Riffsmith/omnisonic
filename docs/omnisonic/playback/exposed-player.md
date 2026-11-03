# Stable exposed player and queue-aware commands — `exposed-player.md`

Purpose: Pin the player architecture that the session and UI bind to: one stable exposed `Player` per `OmniPlayer`, a full effective-order queue timeline, queue-aware next/previous commands routed to the queue-command port, and gapless handoff through `prepareNext` plus an explicit auto-advance adoption step.

How derived: the approved player-architecture decision (stable `ForwardingPlayer` exterior + stable inner bridge, full effective-order queue timeline, `prepareNext` + auto-advance handoff — recorded in `../decisions.md`); direct reading of Media3 1.3.1 `SimpleBasePlayer`/`ForwardingPlayer`/`BasePlayer`/`Timeline` bytecode in the local Gradle cache; behavioral Robolectric tests in this repository (`OmniForwardingPlayerTest`, `OmniPlayerImplTest`, `QueuePlayerImplTest`).

Confidence:
- [VERIFIED] `exposedPlayer()` returns the same `OmniForwardingPlayer` instance for the lifetime of an `OmniPlayerImpl` (until `release()`); `applyEngineConfig()` swaps only the engine behind the stable `QueueAwareBridge`, and a MediaSession bound before a swap still receives events and keeps working (Robolectric `MediaSession` + in-process `MediaController` test).
- [VERIFIED] Media3 1.3.1 `ForwardingPlayer` stores its delegate in a final field, so the delegate must never be swapped; the stable interior is a `SimpleBasePlayer` subclass (`QueueAwareBridge`) whose published state is rebuilt from queue state + engine state on every change.
- [VERIFIED] The exposed timeline is the full effective queue as metadata-only `MediaItem`s (`mediaId` = queue item id, no stream URI/localConfiguration), window/period uid = queue item id, current index = the queue's current entry position; a direct `OmniPlayer` consumer with an empty queue state falls back to the player's own current/prepared entries.
- [VERIFIED] `COMMAND_SEEK_TO_NEXT(_MEDIA_ITEM)` is advertised exactly when the queue machine's skip-next policy says a next exists (repeat OFF bounded by the queue; ALL/ONE wrap), and is withdrawn at the end with repeat OFF; previous commands follow the queue machine's restart-or-go-back policy and exist whenever an entry is current.
- [VERIFIED] `seekToNext/Previous(MediaItem)`, `seekTo(index)`, repeat/shuffle setters route to `QueueCommandPort` (implemented by the controller wiring); the engine's own repeat/shuffle stay untouched and the published repeat comes from the queue state. Media-list mutation commands are never advertised.
- [VERIFIED] The engine keeps a `[current, next]` playlist: `prepareNext` appends the resolved next source without repositioning the current item (retained before the first `play`, joined at start), slides the window after an auto-advance (append following, trim the played head), and trims the tail for a null next (queue end, repeat ONE).
- [VERIFIED] An automatic engine transition onto the prepared item is verified by media id in `OmniPlayerImpl`, reported through `OmniPlayerListener.onAutoAdvance`, validated by the queue machine in `QueuePlayerImpl.onEngineAutoAdvanced` against its expected next, and adopted without resolving or replaying; a mismatched/stale advance resyncs via the existing engine-error path instead of being silently accepted.
- [VERIFIED] Two queued WAVs advance with no `STATE_IDLE`/`STATE_ENDED`/`STATE_BUFFERING` state change between tracks in the Robolectric/ExoPlayer harness (`OmniPlayerImplTest` gapless smoke test); `STATE_ENDED` remains the no-prepared-next case (queue end, repeat ONE).
- [VERIFIED] Engine swaps replay position, play intent, speed/pitch and the prepared next into the new engine; a new engine whose application looper differs from the bridge's looper is rejected with `IllegalStateException` before the old engine is released.
- [INFERRED] `seekTo(index, positionMs)` for a foreign index drops `positionMs` and only jumps (the queue restarts the target entry at its resolved start position); a position-preserving arbitrary jump belongs to the controller wiring.
- [INFERRED] Shuffle is published as disabled because the published order is already the effective order; the shuffle toggle lives on the queue machine.
- [UNKNOWN] On-device session behavior (Bluetooth/lock-screen command surfaces) and very large queue binder cost — no device and no binder measurement this session.

Date: 2026-11-01

Links: docs/omnisonic/playback/stream-cache-identity.md, docs/omnisonic/playback/per-provider-media-clients.md, docs/omnisonic/playback/wake-mode.md, ../decisions.md (stable exposed player).
