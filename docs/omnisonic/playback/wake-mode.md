# Wake mode — `wake-mode.md`

Purpose: Pin how engine construction chooses the Media3 wake mode: NETWORK by default (wake lock plus Wi-Fi lock during playback, for streaming with the screen off), with a LOCAL opt-out for local-only engines.

How derived: Prompt.md T9; direct reading of the Media3 1.3.1 `ExoPlayerImpl`/`WakeLockManager`/`WifiLockManager` bytecode in the local Gradle cache; empirical Robolectric probes in this repository (WakeModeTest). The studied app is not a source.

Confidence:
- [VERIFIED] `EngineConfig` gained `wakeMode: WakeMode = NETWORK` (`LOCAL | NETWORK`), mapped in `ExoEngineFactory` to `C.WAKE_MODE_NETWORK` / `C.WAKE_MODE_LOCAL`.
- [VERIFIED] Media3 1.3.1 bytecode: `WAKE_MODE_LOCAL` enables the PowerManager wake lock but leaves the wifi lock disabled (`wifiLockManager.setEnabled(wakeMode == WAKE_MODE_NETWORK)`), so long Wi-Fi streaming under LOCAL can stall when the screen is off.
- [VERIFIED] Robolectric 4.14.1 behavior behind `WakeModeTest`: with NETWORK, `ShadowWifiManager.activeLockCount` is -1 after build (ExoPlayer's constructor creates the wifi lock and releases it once; the shadow counts every release) and rises on the STATE_READY transition (the acquire); with LOCAL the count stays 0 (no lock is ever created). The absolute -1 is a shadow counting quirk; the test asserts the build-to-playing delta, not the absolute value.
- [VERIFIED] The PowerManager wake lock (via `ShadowPowerManager.getLatestWakeLock()`) is held while playing and released on pause, in both modes.
- [UNKNOWN] Real-device behavior of the wifi lock across doze/screen-off (needs a device; not run).

Date: 2026-10-31

Links: `per-provider-media-clients.md`, Prompt.md T9

---

## Configuration

[VERIFIED] `EngineConfig.wakeMode` defaults to `NETWORK`. `OmniPlayerImpl.applyEngineConfig` rebuilds engines with the stored config, so the wake mode follows engine swaps. WAKE_LOCK was already declared in the app manifest; the Wi-Fi lock needs no manifest permission.

## Proof status

- [VERIFIED] `WakeModeTest` in `:playback` covers: default selects NETWORK; NETWORK engine acquires the wifi lock on playback and releases the wake lock on pause; LOCAL engine never creates a wifi lock.
- [UNKNOWN] On-device streaming with the screen off. Needs a device; never fabricated.
