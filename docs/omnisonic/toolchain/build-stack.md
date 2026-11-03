# Build stack — `toolchain/build-stack.md`

Purpose: pins the verified build toolchain combination (Gradle, AGP, Kotlin/KSP,
Hilt, media3, Coil) and the release-build hygiene contract (minification, Moshi
codegen, packaging) established in the build-hygiene pass. Version choices and
their rationale are recorded in `../decisions.md`.

How derived: official AGP compatibility table (developer.android.com/build/releases/about-agp,
2026-10-28 revision), Maven metadata for media3/Hilt/KSP, and green build runs of
the full gate on this host.

Confidence:
- compileSdk 36 requires minimum AGP 8.9.1 [VERIFIED — official minimum-AGP table].
- AGP 8.9.1 requires Gradle 8.11.1 [VERIFIED — official required-Gradle table].
- The combination AGP 8.9.1 / Gradle 8.11.1 / Kotlin 2.2.10 / KSP 2.2.10-2.0.2
  (`ksp.useKSP2=false`) / Hilt 2.57.2 / media3 1.11.1 / Coil 3.0.0 builds, tests
  (890 tests), and lints green [VERIFIED — `./gradlew test assembleDebug assembleRelease lint`].
- KSP1 fallback (`ksp.useKSP2=false`) is required: Room 2.6.1's processor fails
  under KSP2 with "unexpected jvm signature V" [VERIFIED — reproduction then flag].
- Hilt 2.57.2 is the newest line that accepts AGP 8.9.1 while reading Kotlin 2.2
  metadata; Hilt 2.60+ hard-requires AGP 9 [VERIFIED — plugin application error].
- media3 1.11.1 transitively pulls kotlin-stdlib 2.2.10, which forces the Kotlin
  2.2 bump [VERIFIED — metadata-version compiler error with Kotlin 2.0.0].
- R8 in AGP 8.9.1 logs "error occurred when parsing kotlin metadata" warnings for
  Kotlin 2.2 metadata; they are non-fatal [VERIFIED — build succeeds; mapping/seeds
  produced]. A future AGP 8.13+ (R8 8.13.19, Kotlin 2.3 metadata support) is the
  upgrade path that silences them [INFERRED — AGP release notes].
- Release: minify + resource shrinking enabled; Moshi decoded via generated
  adapters (moshi-kotlin-codegen); the reflection factory is gone. Codegen
  adapters survive R8 via Moshi's consumer rules [VERIFIED — mapping.txt keeps
  `*JsonAdapter` classes; release-variant unit tests green].
- On-device smoke of the minified APK [UNKNOWN — no device/emulator in this
  environment].

Date: 2026-11-03

Links: `../playback/offload-and-extensions.md`, `../decisions.md` (toolchain table).

---

## Toolchain

| Component | Version | Notes |
|---|---|---|
| Gradle | 8.11.1 | wrapper |
| AGP | 8.9.1 | minimum for compileSdk 36 |
| Kotlin / KSP | 2.2.10 / 2.2.10-2.0.2 | `ksp.useKSP2=false` |
| Hilt | 2.57.2 | 2.60+ needs AGP 9 |
| media3 | 1.11.1 | upgraded from 1.3.1 |
| Coil | 3.0.0 (+ `coil-network-okhttp`) | image fetcher routed per provider |
| Moshi | 1.15.1 + codegen | no runtime reflection |
| compileSdk / minSdk / targetSdk | 36 / 29 / 36 | unchanged |

## media3 1.3.1 → 1.11.1 API/behavior changes handled

- `Player.hasNext/hasNextWindow/hasPrevious/hasPreviousWindow` removed; the
  MediaItem-named overrides in `OmniForwardingPlayer` carry the command policy.
- `BaseAudioProcessor.isActive()` is `@CallSuper` and already implements the
  always-active-after-configure contract; the four sink processors dropped their
  redundant overrides.
- `WifiLockManager`/`WakeLockManager` moved to `media3-common`, gate acquisition
  on `checkSelfPermission(WAKE_LOCK)` and hold locks only while
  `playWhenReady` (READY/BUFFERING). `:playback` declares `WAKE_LOCK` in its own
  manifest; Robolectric tests grant it explicitly.
- Wake/wifi lock scheduling now runs through the injected `Clock` (FakeClock);
  lock lifecycle assertions moved to deterministic `WifiLockManager`/
  `WakeLockManager`-level tests plus the `WakeMode → C.WAKE_MODE_*` mapping test.
- R8: media3 ships consumer rules; no extra keep rules were required.

## Packaging

- The broad `jniLibs { pickFirsts += "*/*/*.so" }` is removed; the build resolves
  the real set (zstd-jni AAR, 12 ABIs total) without any duplicate conflict.
- `:baseline-profile` is a `com.android.test` producer (`targetProjectPath = ":app"`);
  the hand-authored profile still ships (`assets/dexopt/baseline.prof` in the
  release APK).
