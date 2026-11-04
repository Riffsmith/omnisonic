# Audio focus — `audio-focus.md`

Purpose: Pin how the local renderer requests and reacts to audio focus: media/music attributes, duck-versus-pause on transient loss, pause on permanent loss, and the resume-only-if-focus-paused rule.

How derived: the Media3 engine is built with `handleAudioFocus=false`, so focus is app-owned; Android `AudioManager` focus contract (`AudioFocusRequest`, `AUDIOFOCUS_*`) plus Robolectric `ShadowAudioManager` probes in this repository (`AudioFocusHelperTest`). The studied app is not a source.

Confidence:
- [VERIFIED] `AudioFocusHelper` (`playback/focus/`) owns one permanent-gain `AudioFocusRequest` with `AudioAttributes` usage `USAGE_MEDIA` and content type `CONTENT_TYPE_MUSIC`. The content-type variation seen in the study is deliberately not reproduced.
- [VERIFIED] `setWillPauseWhenDucked(false)` is required: Media3/Android otherwise report a duckable loss as a full transient loss, which would pause instead of ducking.
- [VERIFIED] Transient loss (plain or can-duck) ducks to `0.2` linear by default, or pauses when the `TransientLossAction` setting says `PAUSE`; permanent loss pauses.
- [VERIFIED] Focus gain unducks first and resumes only when the pause came from focus, never after a user pause.
- [VERIFIED] An explicit `requestFocus()` clears stale paused-by-focus and duck state. A permanent loss never produces a spontaneous gain, so without this an app that lost focus could resume on a later unrelated gain.
- [UNKNOWN] Real-device behavior for call interrupts, assistant ducking, and headset/Bluetooth handoffs. Needs hardware.

Date: 2026-11-03

Links: `sink-processors.md`, `settings-persistence.md`, `../decisions.md`

---

## Policy

| Focus event | Playing | Paused |
|---|---|---|
| `AUDIOFOCUS_LOSS_TRANSIENT` / `_CAN_DUCK` | duck at `0.2` (default) or pause per setting | ignored |
| `AUDIOFOCUS_LOSS` | pause; focus is marked unheld | ignored |
| `AUDIOFOCUS_GAIN` | unduck, then resume if focus caused the pause | unduck only |

Ducking is applied in the sink through `OmniPlayer.setDuckLinear` → `ProcessorConfig.duckLinear` → `ReplayGainAudioProcessor.setDuckFactor`, so it composes with ReplayGain instead of changing the system volume. Any later config push (mono, PEQ, preamp, RG) must carry the current duck factor; `OmniPlayerImpl.pushConfig()` does.

## Lifecycle

`AudioFocusHelper` exposes a `Callbacks` seam so the controller remains the only command hub: `isPlaying()`, `onDuck(factor)`, `onUnduck()`, `onPauseForFocus()`, `onResumeAfterFocus()`. The helper requests focus when playback starts, abandons it when playback goes idle (`abandonFocus()`), and `release()` cancels its settings collection, abandons a held request, and ignores late focus callbacks. The controller wiring lands with the playback controller task.

## Proof status

- [VERIFIED] `AudioFocusHelperTest` (11 Robolectric cases) covers the request attributes, denied request, transient duck + gain unduck, can-duck, pause setting + resume, permanent loss + resume, ignored loss while paused, abandon, live setting switch, stale-pause clearing, and released-state callbacks.
- [VERIFIED] `OmniPlayerImplTest` asserts the duck factor reaches the processor and survives a settings-driven config push.
- [UNKNOWN] Audible duck level and interrupt/resume on a real device or car head unit.
