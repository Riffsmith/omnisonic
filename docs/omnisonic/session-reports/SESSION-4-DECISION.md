# SESSION 4 — DECISION BRIEF (T13)

- **Date:** 2026-10-31
- **Status:** decision only. No production code or tests were changed in this session.
- **Scope:** T13 stable exposed `Player`, queue-aware commands, engine-swap identity, exposed timeline, and next-item preparation.
- **Sources checked:** `Prompt.md` T13, `reference/ROADMAP.md` §0.3 and §4, the current `playback-api`/`playback` sources, current tests, and Media3 1.3.1 class metadata.

## 1. Verified starting point

- `OmniPlayerImpl.exposedPlayer()` returns `engine.player`, which is a raw `ExoPlayer` wrapper. `applyEngineConfig()` removes the listener, releases that engine, creates a new engine, and returns the new engine's player. A session holding the old player holds a released object. [VERIFIED]
- `OmniPlayerImpl.play()` calls `setMediaSource(singleSource, position)`. The engine timeline therefore contains one item; there is no preloaded next source. [VERIFIED]
- `OmniPlayerListener` currently reports natural completion through `onEngineFinished`; there is no auto-advance callback. [VERIFIED]
- `QueuePlayerImpl` is the queue state machine: it owns `entries`, effective `playOrder`, `playIndex`, shuffle, repeat, and issues `player.play(...)` for the current entry. It does not build media sources. [VERIFIED]
- `PlaybackControllerImpl` does not exist yet. `PlaybackController` is only an interface and has `queueState`, `skipNext()`, `skipPrevious()`, `seekTo()`, repeat/shuffle setters, and transport commands. All of those except queue jumps are sufficient for the T13 command surface; full-queue `seekTo(index)` has no controller method yet. [VERIFIED]
- No `MediaLibraryService`/`MediaSession` is wired in the app yet. [VERIFIED]
- `ForwardingPlayer` in Media3 1.3.1 stores `private final Player player`; all forwarding methods use that field, `addListener()` wraps listeners against that field, and `getWrappedPlayer()` merely returns it. A subclass cannot swap the delegate by changing a field or overriding only `getWrappedPlayer()`. [VERIFIED from cached `media3-common-1.3.1` bytecode]
- Current tests: `./gradlew test --rerun-tasks` passed, 602 tests, 0 failures, 0 errors, 8 skipped. `./gradlew test :app:assembleDebug` was up to date. No device or MediaSession integration test exists. [VERIFIED]
- Current uncommitted tree contains queue implementation/tests and a `QueuePlayer.setQueue(...)` signature change that predate this decision. They are not part of this session. [VERIFIED]

## 2. The decision, verbatim from `Prompt.md`

> DECISION first (present, recommend A):
> A) `OmniForwardingPlayer : ForwardingPlayer` wrapping a stable inner delegate that is swapped
>    on engine change (re-attach listeners, replay state). It overrides getAvailableCommands /
>    isCommandAvailable / seekToNext / seekToPrevious / hasNext... from QueueState and routes
>    them to PlaybackController. Timeline exposed = full queue (MediaItem metadata only) or a
>    2-item window (current + next) — choose one and justify.
> B) Keep raw player, forbid engine swaps while a session exists (loses decoder/offload hot-swap).

The prompt's parenthetical for A is literally contradictory: if the inner delegate is swapped, the delegate is not stable. With `ForwardingPlayer` that is not merely awkward; it is unsafe. The implementable reading is **stable inner bridge, swapped engine behind it**. That distinction is the central implementation finding below.

## 3. Option A analysis

### 3.1 Stable player identity

The public object must be created once and returned forever by `exposedPlayer()` (until it is released):

```text
OmniForwardingPlayer (stable outer, returned to session/UI)
        |
        v
stable inner bridge (listener + state owner)
        |
        v
active AudioEngine / ExoPlayer  <-- only this changes on applyEngineConfig()
```

- The outer `OmniForwardingPlayer : ForwardingPlayer` is constructed around the stable bridge. No session listener is ever attached directly to an engine.
- The bridge attaches one listener to each active engine and reattaches on swap. It replays the session-visible state after the swap.
- Because `ForwardingPlayer`'s own listener wrapper is bound to its final inner field, leaving the inner bridge in place is what keeps existing session listeners alive. Swapping the `ForwardingPlayer` delegate would silently route calls and listener registration to the old engine.
- `OmniPlayerImpl` owns the outer and bridge. `engine` becomes mutable internal state behind the bridge; `exposedPlayer()` never changes identity.

**Recommended bridge:** a small `Player` implementation owned by `:playback`, preferably `SimpleBasePlayer`-backed (available in media3-common 1.3.1) or an equivalent stable `BasePlayer` bridge. `SimpleBasePlayer` already provides the state/listener scheduling needed for a synthetic timeline and lets the bridge publish state changes when the engine or queue changes. The outer `ForwardingPlayer` remains the required public type and is where queue-aware overrides live.

This is not just a naming preference. A custom player bridge must implement the full `Player` surface; `SimpleBasePlayer` reduces that to a state object plus command handlers and is the smallest defensible bridge.

### 3.2 Engine swap sequence

`applyEngineConfig()` must stop treating the exposed player as the engine player. A safe sequence:

1. Capture session-visible state from the current engine: current source/entry, prepared next source/entry, current position, `playWhenReady`, speed/pitch, volume, track-selection parameters, and audio attributes.
2. Detach the bridge's raw-engine listener.
3. Create the new engine. Prefer preparing it at the saved position before releasing the old engine; if creating two engines concurrently is unacceptable for audio focus/decoder ownership, pause and detach the old engine first, then create/prepare the new one. Do not release until the bridge can read a valid new player.
4. Build the new engine playlist as `[current, next]`, apply saved transport state, and attach the bridge listener.
5. Point the bridge at the new engine and invalidate state.
6. Release the old engine only after the bridge no longer references it.

The outer object, its application looper, and all session listeners stay unchanged. The stable looper must be an explicit invariant: the bridge's `getApplicationLooper()` must equal the engine's application looper. Today `ExoEngineFactory` builds on the caller's looper; T13 must enforce the same looper for every swap or reject the config rather than expose a moving looper.

Repeat and shuffle must not be replayed into the engine. They belong to `QueueState`, and the engine remains `REPEAT_MODE_OFF`/shuffle disabled. This prevents two repeat owners.

**Release semantics:** release the bridge and active engine; mark the outer released; clear listeners. `release()` is terminal. The stable identity promise applies to the lifetime of one `OmniPlayerImpl`, not after release.

### 3.3 Exposed timeline: choose full queue

**Recommendation: expose the complete effective queue as the timeline, in playback order.**

Build a read-only `QueueTimeline : Timeline` from `QueueState.effectiveOrder()`:

- one window/period per effective-order entry;
- window UID and period UID = `queueItemId`;
- `MediaItem.mediaId` = `queueItemId.toString()`;
- `MediaMetadata` = title, artist, album, and artwork only if a resolvable artwork URI is supplied; no direct stream URI;
- duration from the active engine for the current item, otherwise `PlaybackQueueEntry.durationMs`, otherwise `C.TIME_UNSET`;
- flags per entry: playable/seekable for normal tracks; live/radio entries can be marked dynamic and non-seekable;
- `currentMediaItemIndex` = `QueueState.playIndex`.

Why this is the better choice:

1. **One projection of one owner.** `QueueState` remains authoritative; the player timeline is a derived read-only view. A 2-item window would be a second, partial queue representation with different indices.
2. **Queue commands fall out naturally.** `hasNext`, `hasPrevious`, next/previous indices, `seekTo(index)`, and item count all map directly to `QueueState`. A 2-item window requires exceptions for the previous item and cannot represent an arbitrary queue jump through the standard player API.
3. **Correct session semantics.** Generic `MediaController` consumers, Bluetooth, and future lock-screen surfaces can see queue length and position. They are not forced to believe the queue contains only two tracks.
4. **Less timeline churn.** The queue timeline stays the same across a track transition; only the current index changes. A 2-item window rebuilds its timeline on every advance.
5. **The engine remains a 2-item implementation detail.** ExoPlayer still holds `[current, next]` for gapless. The wrapper presents the full queue while the engine does not need to hold thousands of MediaSources.

The real tradeoff is binder size for very large queues. Metadata-only items are small, but a pathological 10k-entry queue can still be expensive. T13 should not build a hybrid now. Measure the full-queue timeline in the MediaSession test and in a large-queue test. If a real binder limit is reached, revisit the window as a separate decision behind the same queue-derived command layer.

**The 2-item window, if it were chosen**, would behave as follows:

- `getMediaItemCount()` = 1 or 2; index 0 is current, index 1 is next.
- `getCurrentMediaItemIndex()` is always 0; `getNextMediaItemIndex()` is 1 when a next exists; `hasNext()` is true, but `hasPrevious()` is false even when the queue has a previous entry, so it must be overridden from `QueueState`.
- `seekToNext()` still has to route to `PlaybackController`, not `ExoPlayer`; after the queue advances, the window is rebuilt as the new `[current, next]`. The session sees a new two-item timeline every track.
- `seekToPrevious()` cannot use the engine timeline; it must route to the queue. `hasPrevious`/`getPreviousMediaItemIndex` are queue-derived special cases.
- `seekTo(index)` is limited to 0/1; an arbitrary queue jump must be a controller command, not a player seek.
- Queue edits are invisible except for the current/next window; a controller can incorrectly infer that the queue is only two entries.
- On queue end the window shrinks to one item; repeat mode is still derived from `QueueState`, not the window.

This can be made to work, but it is not simpler once command correctness is included. It is only preferable if the product explicitly refuses to expose queue length through the session and accepts the extra override surface. That is not the case here.

### 3.4 Queue-aware commands

The wrapper's command layer should be a projection of `QueueState`, with engine availability only for commands that genuinely belong to the engine.

- `getAvailableCommands()` / `isCommandAvailable()`:
  - start with the bridge's transport/getter commands;
  - add `SEEK_TO_NEXT` and `SEEK_TO_NEXT_MEDIA_ITEM` when the queue has a next item under the same policy as `QueuePlayerImpl.skipNext` (end with repeat OFF is excluded; repeat ALL/ONE may wrap);
  - add previous commands when the queue can go back, wrap, or restart the current item through `QueuePlayerImpl.skipPrevious`;
  - add `SEEK_TO_MEDIA_ITEM`/`SEEK_TO_DEFAULT_POSITION` for the full-queue timeline;
  - remove media-list mutation commands (`SET`, `ADD`, `REMOVE`, `MOVE`, `REPLACE`) from the externally advertised list; queue edits go through the controller/service callbacks, never through the engine playlist.
- `seekToNext()`, `seekToNextMediaItem()` -> `PlaybackController.skipNext()`, not the engine's next command.
- `seekToPrevious()`, `seekToPreviousMediaItem()` -> `PlaybackController.skipPrevious()`. `QueuePlayerImpl.skipPrevious` already distinguishes "restart if deep into the track" from "go to previous"; keep that policy consistent with `hasPrevious()` and document which media3 command maps to restart versus previous-item semantics.
- `hasNext()`, `hasNextMediaItem()`, `hasNextWindow()` -> queue-derived.
- `hasPrevious()`, `hasPreviousMediaItem()`, `hasPreviousWindow()` -> queue-derived.
- `getNextMediaItemIndex()`, `getPreviousMediaItemIndex()` -> effective-order index or `INDEX_UNSET`; wrap only when the queue policy says wrap.
- `getCurrentMediaItemIndex()`, `getMediaItemCount()`, `getMediaItemAt()`, `getCurrentMediaItem()`, `getCurrentTimeline()` -> queue timeline and `playIndex`.
- `seekToDefaultPosition(index)`, `seekTo(index, position)`, `seekToMediaItem(index)` -> controller queue jump plus position seek. The current `PlaybackController` interface lacks a queue-index jump; T13 should add a narrow command to the controller (or a small command port adapted by the controller) rather than letting the engine seek its 2-item playlist.
- `getRepeatMode()` / `getShuffleModeEnabled()` -> derive from `QueueState`; `setRepeatMode()` / `setShuffleModeEnabled()` -> controller/QueuePlayer. Do not call the engine's repeat/shuffle setters, or the engine and queue will disagree.
- `getPlaybackParameters()`, `getVolume()`, `getAudioAttributes()`, `getTrackSelectionParameters()`, `getCurrentTracks()` -> active engine state, captured and replayed across swaps where session-visible.
- `getCurrentPosition()`, `getBufferedPosition()`, `getDuration()`, `getTotalBufferedDuration()`, `getContentPosition()` -> active engine for the current item; queue entry duration is only a fallback when the engine has none.
- `playWhenReady`, `playbackState`, `isPlaying`, `isLoading`, `playerError`, suppression reason -> active engine, with the bridge invalidating state on engine events.
- `getMediaMetadata()` -> current queue `MediaItem` metadata, so lock-screen metadata does not depend on the engine's MediaSource metadata.

When `QueueState` changes (append, remove, move, shuffle, repeat, queue jump), publish a timeline/current-item/available-command update. When the engine changes state, publish only the engine-derived state changes. On an auto-advance, let the queue update drive the media-item transition; suppress the raw engine transition so the session does not receive two transitions for one track change.

**Command routing dependency:** there is no `PlaybackControllerImpl` yet. T13 should define the wrapper against a narrow queue-command port (or against `PlaybackController` plus the missing jump method), and test it with a fake. It must not grow a second controller or let the wrapper mutate `QueuePlayerImpl` directly. The real controller wiring can land with T10 without changing the wrapper contract.

### 3.5 Gapless next-item preparation

Add to `OmniPlayer`:

```text
fun prepareNext(stream: ResolvedStream?)
```

Semantics:

- `play(stream, entry)` remains the current-item start.
- `prepareNext(next)` must not restart or reposition the current item. The engine playlist becomes `[current, next]`; use `addMediaSource` for the next source, or `replaceMediaItem` on the second slot when the next source changes. Do not call `setMediaSource` just to add the next item.
- If `prepareNext` arrives before `play`, retain it and build `[current, next]` when playback starts.
- If `next` is null, trim any prepared tail; do not invent a repeat source.
- On queue edits that change the next entry, call `prepareNext(newNext)`; a same-source call is a no-op.
- On manual current changes, clear the old prepared next and prepare the new next through the normal queue start path.
- On stop/release, clear both current and prepared next.

**Auto-advance handoff**

ExoPlayer will advance from the first item to the prepared second item without going through `STATE_ENDED`. The current `onEngineFinished` callback is therefore not sufficient. Add:

```text
OmniPlayerListener.onAutoAdvance(entry: PlaybackQueueEntry)
```

and a queue-side method such as:

```text
QueuePlayer.onEngineAutoAdvanced(entry: PlaybackQueueEntry)
```

The intended flow:

1. Engine transitions automatically to the prepared item.
2. `OmniPlayerImpl` verifies the new item against its prepared next entry, updates `currentEntry`/`currentStream`, and calls `onAutoAdvance(entry)`.
3. `QueuePlayerImpl` validates that the entry is the expected next item, advances `playIndex` without resolving or replaying it, emits `Finished(previous)` plus `TrackChanged(AUTO_ADVANCE)`, and asks for the following next entry.
4. `OmniPlayerImpl.prepareNext(following)` slides the engine window forward: append the following source, then trim the already-played item. Keep the current item at the head of the engine window after trimming.
5. `STATE_ENDED` remains the no-prepared-next case (queue end, repeat ONE, or a deliberately unprepared wrap). `onEngineFinished` keeps its current meaning.

If the auto-advanced entry does not match the queue's expected next entry, do not silently accept it. The queue and engine have diverged; stop/resync to the queue's current entry and surface the error through the existing error path. This is the race that T13 must not paper over.

**Replay-gain interaction:** `OmniPlayerImpl` currently applies ReplayGain inside `play()` before starting the source. With a preloaded next item, the sink processor is shared, so a per-track gain change must occur at the auto transition. Applying the next gain when the next item is merely prepared would change the current track's gain early; applying it only after the transition can affect the first samples of the next track. This is not solved by the wrapper. T13 should verify the behavior with ReplayGain off for the gapless smoke test, and the implementation must define whether per-track RG is applied on transition or deferred to the T14 processor work. Do not claim sample-accurate per-track RG from a global sink processor.

**Other preparation limits:** do not pre-open a live/radio stream as a "next" item; skip preparation for entries whose resolved stream is live/radio. Preparation may start a network request early, so the controller should request next resolution only after the current start is committed and should cancel it on queue changes. Offload and codec changes can disable gapless; T15 is the place to report `offloadEffective` honestly.

### 3.6 Name reconciliation

Recommendation: **update the roadmap §0.3 table to match the code; do not rename code.**

The code's names are already the better fit:

- `QueuePlayer` is a queue state machine (entries, order, repeat, shuffle) that commands `OmniPlayer`.
- `OmniPlayer` is the per-renderer source builder, cache/sink configuration layer, and session/UI facade that will swap engines.
- `PlaybackRenderer` is the output-device seam that activates an `OmniPlayer`.

Proposed §0.3 table wording:

| Layer | Omnisonic name | Job |
|---|---|---|
| Controller | `PlaybackController` | queue commands, state flows, sleep timer, chapter state |
| Output seam | `PlaybackRenderer` | local/Cast implementations; activates one `OmniPlayer` |
| Queue machine | `QueuePlayer` | entries, effective order, shuffle/repeat; commands `OmniPlayer` |
| Media3 facade and source builder | `OmniPlayer` (stable `ForwardingPlayer`) | source building, cache/sink configuration, session/UI surface, invisible engine swaps |
| Engine | `AudioEngine` | one ExoPlayer instance on its own looper |

The current table's "per-renderer player = QueuePlayer builds media sources" is the line that is false in the code. Renaming `QueuePlayerImpl` would churn T7/T8 contracts and tests for no behavior gain. The table should stop assigning source building to the queue machine.

Session rule 7 says only `docs/omnisonic/` may be edited this session, and roadmap edits are reserved for T24. Therefore this brief records the target wording; it does not edit `reference/ROADMAP.md`. If T13's fix is expected to perform that table edit, the session rule needs an explicit exception.

## 4. Option B analysis

Option B keeps `exposedPlayer()` as the raw engine player and forbids `applyEngineConfig()` while a session exists.

What it preserves:

- lowest implementation cost;
- no synthetic timeline, no bridge, no command overrides;
- the raw ExoPlayer already provides a correct single-item transport surface.

What it loses:

- **T13's identity test cannot pass.** The exposed player changes on every swap; a session bound to the old instance holds a released player. Deferring the swap avoids the crash but does not make the player stable.
- **Decoder/offload/build-setting hot swaps are effectively unavailable.** A `MediaLibraryService` session usually exists for the life of the background service. "Forbid while a session exists" means settings that require a new engine (decoder preference, offload mode, buffer sizing, wake mode) apply only after the service and session are torn down and rebuilt.
- **No queue-aware session commands.** The raw engine has a one-item timeline and no queue owner. Adding a two-item raw playlist would give gapless, but next/previous still belong to ExoPlayer, not `QueueState`; a Bluetooth or lock-screen next can move the engine outside the queue's intended order. There is no stable wrapper in which to correct this.
- **Crossfade/future renderer work is blocked.** Crossfade needs more than one engine behind the same session-facing object; a raw session reference cannot survive that. The roadmap's "no UI or session code holds a raw ExoPlayer" rule is violated.
- **Auto/Wear are not inherently broken by B** because browsing goes through the library service's browse tree, but their transport commands and queue position still suffer from the raw-engine timeline. Option A is the only option that keeps those surfaces correct.

Option B is therefore a temporary escape hatch, not a viable T13 implementation. It would be appropriate only if the project explicitly chose to drop hot engine swaps, queue-aware session transport, and crossfade compatibility.

## 5. Compatibility and scope

- **Future Auto/Wear:** both timelines are compatible with browse-tree-driven browsing. Full queue is not required for Auto/Wear itself; it is required for standard `MediaController` queue semantics, queue position, and arbitrary queue jumps. The 2-item window works only with extra command overrides and hides queue length.
- **Crossfade:** Option A is the compatible choice. The stable wrapper can present one session while `OmniPlayer` owns multiple engines; the queue machine is unaffected. Option B's raw session reference blocks engine changes and therefore crossfade.
- **Overkill:** full-queue timeline construction and a stable bridge are more code than a 2-item window, but the 2-item window does not avoid the hard parts (queue-aware availability, previous handling, arbitrary jumps, timeline rebuilds). Full queue gives one invariant instead of several special cases. Do not implement crossfade, Cast, Wear, or a full session service in T13; only preserve the seams.
- **Not in T13:** MediaSession/service wiring, audio focus, queue persistence, notification, replay-gain redesign, per-engine processor chains, FFmpeg/offload work. Those remain T14/T15 and later Phase 4 tasks.

## 6. Test plan for the T13 fix

1. **Stable identity and controllability**
   - Build a `MediaSession` around `exposedPlayer()`.
   - Capture the player reference, attach a `Player.Listener`, play, then call `applyEngineConfig()`.
   - Assert `session.player === exposedPlayer()` before and after; the listener still receives post-swap events; the player remains playable/seekable; position and play intent are restored.
   - The existing `OmniPlayerImplTest` engine-swap assertion `exposedPlayer() !== firstPlayer` must change to `===`; that is a behavior change, not a weakened test.
2. **Available commands**
   - Queue `[1, 2]`, current at 0, repeat OFF: `SEEK_TO_NEXT` and `SEEK_TO_NEXT_MEDIA_ITEM` are available.
   - Current at the last item, repeat OFF: both are unavailable.
   - Repeat ALL/ONE: wrap policy matches `QueuePlayerImpl.skipNext`; repeat ONE natural finish still restarts rather than auto-advanced.
   - Previous commands follow the queue/restart policy; no engine repeat/shuffle is used.
3. **Queue timeline**
   - Full queue order with shuffle off and on; count, current index, next/previous indices, `seekTo(index)`, and queue edits.
   - Metadata-only `MediaItem`s; no direct stream URI leaks.
   - Large-queue sanity test; record binder/timeline cost rather than asserting a fabricated limit.
4. **Gapless smoke test**
   - Resolve and queue two WAVs with ReplayGain off and offload disabled.
   - Start the first, prepare the second, and observe state changes.
   - Assert no `STATE_IDLE` and no intervening `STATE_BUFFERING`; the queue advances through `onAutoAdvance`, not by re-calling `play`.
   - This is the proof the prompt requires; if the Robolectric/ExoPlayer harness cannot prove it, record that honestly rather than claiming a pass.
5. **Auto-advance and divergence**
   - Normal auto-advance updates `playIndex`, emits `Finished` and `AUTO_ADVANCE`, and prepares the following entry.
   - A stale/mismatched prepared entry is rejected/resynced rather than accepted.
   - Queue end and repeat ONE still use `onEngineFinished`.
6. **Engine swap state replay**
   - Current and prepared-next playlist, position, `playWhenReady`, speed/pitch, volume, track selection, and listener continuity.
   - Repeat/shuffle remain queue-owned; the new engine is not configured with repeat/shuffle.
7. **Queue edits**
   - Append/remove/move/reshuffle update available commands and timeline without restarting the current item.
   - A changed next entry replaces the prepared source; removing next trims it.

## 7. Recommendation

**Choose Option A, with a stable `ForwardingPlayer` exterior, a stable inner bridge, and a full effective-order queue timeline.**

Reasons:

1. It is the only option that satisfies the roadmap's "session and UI never see a raw engine" and "engine swaps invisibly" rules.
2. It preserves decoder/offload hot swap and keeps crossfade possible.
3. Full queue is a single derived projection of the existing `QueueState`; it avoids a second queue representation and makes next/previous/jump commands consistent.
4. The 2-item window is not actually cheaper once queue-aware command correctness is included; it mainly hides queue length and creates new edge cases.
5. The main cost, a synthetic timeline plus a stable bridge, is bounded in `:playback` and testable without a device.

The stable bridge is a necessary correction to the prompt's wording: do not attempt to swap the `ForwardingPlayer` delegate. The engine is what swaps; the bridge and the exposed player remain.

## 8. Decision requested

Please approve or reject:

- **Option A** (stable exposed player + engine swap + queue-aware commands), with:
  - **full effective-order queue timeline**, not a 2-item exposed window;
  - `prepareNext` and an explicit auto-advance handoff;
  - roadmap §0.3 table updated to match the code (deferred to T24 under the current session rules).

If approved, T13 can be implemented next. If the full queue is rejected, the only consistent alternative is the 2-item window with the extra command overrides described above; Option B should be selected only if hot engine swaps and crossfade are deliberately dropped.

## 9. Limitations of this brief

- No implementation or failing-test reproduction was performed; this session is a decision gate, not a fix.
- No real device, Bluetooth controller, MediaSession service, or crossfade run was performed.
- The MediaSession binder-size behavior for very large queues is not measured.
- Per-track ReplayGain at a gapless boundary remains an implementation risk; it needs a real processing test and may belong with T14.
- No playback/controller implementation was changed, so the current green test result describes the pre-T13 tree.
