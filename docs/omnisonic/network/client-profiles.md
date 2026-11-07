# Client profiles — `client-profiles.md`

Purpose: Separate API traffic, which may negotiate compressed response bodies, from media traffic, which must preserve raw bytes, Range semantics, and real Content-Length values.

How derived: Code reading of `ConnectionSpec.kt`, `HttpClientFactory.kt`, `ZstdSupport.kt`, and `HttpClientFactoryTest.kt`; inspection of the published `zstd-jni` Android AAR metadata and packaged native libraries; and the client-profile unit tests in this repository. Evidence categories: own-source reading, published library artifact inspection, in-repository test runs.

Confidence:
- [VERIFIED] `ClientProfile.API` advertises `zstd, gzip` when zstd support is detected, decodes `zstd`/`gzip`, and preserves the previous API timeout defaults.
- [VERIFIED] API read timeouts may be configured per provider: `OptionsJson.readTimeoutMs` maps to `ProviderConnection.readTimeoutMs`, is clamped to `[5 s, 120 s]`, and reaches both profile specs through `ProviderClientRegistry` (FIX-14; see `../providers/provider-connections.md`).
- [VERIFIED] `ClientProfile.API` advertises `gzip` only when the zstd probe reports unavailable or fails (FIX-12): the decision is made once per client, and a probe `Throwable` (e.g. `UnsatisfiedLinkError`) degrades to gzip instead of breaking API calls (`HttpClientFactoryTest.zstdUnavailableAdvertisesGzipOnlyAndStillDecodesGzip`, `.zstdProbeNativeLoadFailureAdvertisesGzipOnly`).
- [VERIFIED] `ClientProfile.MEDIA` injects no encoding advertisement, suppresses OkHttp's transparent gzip negotiation, skips body unwrapping, and has a read timeout of at least 60 seconds.
- [VERIFIED] Both profiles keep custom headers, logging redaction, and the mTLS/trust-anchor path.
- [VERIFIED] Trust anchors are honored without a client keystore (FIX-07): the custom SSLContext is installed whenever a keystore or explicit anchors exist; anchors replace platform trust and no-anchor clients keep platform defaults (`TlsHandshakeTest`).
- [VERIFIED] `zstd-jni:1.5.7-10` resolves as an AAR containing `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` native libraries; the debug APK and the release APK package all four.
- [VERIFIED] The AAR ships no consumer ProGuard/R8 rules (no `proguard.txt` in the artifact). AGP 8.9.1's default `proguard-android-optimize.txt` already contains `-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }`, so no keep rules for `com.github.luben.zstd.**` were added (`app/proguard-rules.pro` stays empty). Release-build evidence: the R8 mapping keeps `Zstd`, `ZstdInputStreamNoFinalizer`, `ZstdCompressCtx` and `ZstdDecompressCtx` (the native-method carriers) unrenamed while non-native classes are renamed, and the release dex contains the native method names (`createDStream`, `decompressStream`, `recommendedDInSize`).
- [VERIFIED] JVM unit tests use the plain `zstd-jni` jar because the Android AAR natives cannot load in a host JVM; in every module other than `:core-network` the test JVM has no zstd classes at all, so the probe correctly reports unavailable there (tests that pin the `zstd, gzip` wire header inject `ZstdProbe { true }` — see `SubsonicClientTest`).
- [VERIFIED] Native loading and decoding on an Android device (2026-11-07): `ZstdAndroidTest` ran green on a real arm64 device (RMX3085, Android 13) — the packaged AAR natives load and decode, and the `ZstdSupport.available` probe reports true on-device. The debug-signed release APK also installed and cold-launched on the same device with no native errors (`primaryCpuAbi=arm64-v8a`).
- [UNKNOWN] A runtime zstd decode through the R8-processed release classes against a live server: the Phase-5 shell cannot configure a provider yet, so the release artifact's decode path is proven statically (mapping/dex) and by the same-device instrumented run of the same packaged natives.
- [VERIFIED] Decoder construction and read failures, including `UnsatisfiedLinkError`, are converted to `IOException`.

Date: 2026-11-07

Links: `../protocol/subsonic-auth.md`, `../playback/stream-cache-identity.md`

---

## API profile

[VERIFIED] `ClientProfile.API` is the default. It advertises `zstd, gzip` when the zstd probe reports support (one lazy compress/decompress round trip per process), then unwraps response `Content-Encoding` values for API calls. Existing API timeout defaults are unchanged.

[VERIFIED] The API read timeout is configurable per provider (FIX-14). `ConnectionSpec.readTimeoutMs` already fed OkHttp; the missing link was reading it from persisted provider options. `OptionsJson.readTimeoutMs` now maps through `ProviderConnection.readTimeoutMs` (default 15 s, clamped to 5–120 s), and `ProviderClientRegistry` copies it into both the API and MEDIA specs. The MEDIA profile still applies its own 60 s floor, so a lower provider value cannot starve streaming reads.

[INFERRED] Trade-off: one timeout covers every API call with no extra requests or sync-shape changes, at the cost of waiting up to the bound on a genuinely hung server. A per-page album size would instead multiply round trips and require the syncer to carry UI configuration, so it was not chosen for this remediation.

[VERIFIED] When the probe reports unavailable — or throws, e.g. `UnsatisfiedLinkError` from a failed native load — the advertisement degrades to `gzip` only and a warning is logged through `OmnisonicLogger`. The probe catches `Throwable` during detection, so a broken native library can never crash client creation or the first request. If a server disregards the reduced advertisement and still returns a zstd body, decoding fails closed as a wrapped `IOException` (existing behavior).

## Media profile

[VERIFIED] `ClientProfile.MEDIA` does not advertise encodings and does not unwrap response bodies. It prevents OkHttp's implicit gzip request header so the wire request carries no `Accept-Encoding` field and the caller receives the stored bytes.

[VERIFIED] The media read timeout is `max(requested, 60_000 ms)`, so callers may raise it but not fall below the media floor.

## Native dependency

[VERIFIED] The Android artifact is selected with `@aar`. The plain jar remains a unit-test-only dependency so JVM tests can exercise decoder behavior.

[VERIFIED] `ZstdSupport` (`core-network`) decides availability lazily: one `Zstd.compress`/`Zstd.decompress` round trip inside a `catch (Throwable)`, memoized for the process. `HttpClientFactory` takes a `ZstdProbe` seam so tests can simulate both outcomes deterministically. No keep rules for `com.github.luben.zstd.**` were needed (see the confidence list).

[VERIFIED] Device execution coverage for the AAR native library: `ZstdAndroidTest.androidArtifactLoadsNativeZstdAndDecodesPayload` and `ZstdAndroidTest.deviceProbeDetectsNativeZstdSupport` ran green on a real arm64 device (RMX3085, Android 13) on 2026-11-07. The debug-signed release APK installed and launched on the same device with no native errors; only an end-to-end release-variant decode against a live server remains [UNKNOWN].
