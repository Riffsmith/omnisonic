# Client profiles — `client-profiles.md`

Purpose: Separate API traffic, which may negotiate compressed response bodies, from media traffic, which must preserve raw bytes, Range semantics, and real Content-Length values.

How derived: Code reading of `ConnectionSpec.kt`, `HttpClientFactory.kt`, and `HttpClientFactoryTest.kt`; inspection of the published `zstd-jni` Android AAR metadata and packaged native libraries; and the T3 unit-test runs. The studied app is not a source.

Confidence:
- [VERIFIED] `ClientProfile.API` advertises `zstd, gzip`, decodes `zstd`/`gzip`, and preserves the previous API timeout defaults.
- [VERIFIED] `ClientProfile.MEDIA` injects no encoding advertisement, suppresses OkHttp's transparent gzip negotiation, skips body unwrapping, and has a read timeout of at least 60 seconds.
- [VERIFIED] Both profiles keep custom headers, logging redaction, and the mTLS configuration path.
- [VERIFIED] `zstd-jni:1.5.7-10` resolves as an AAR containing `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` native libraries, and the debug APK packages all four.
- [VERIFIED] JVM unit tests use the plain `zstd-jni` jar because the Android AAR natives cannot load in a host JVM.
- [UNKNOWN] Actual native loading and decoding on an Android device or emulator: `core-network` has an instrumented test for it, but no device, AVD, or system image was available to run it.
- [VERIFIED] Decoder construction and read failures, including `UnsatisfiedLinkError`, are converted to `IOException`.

Date: 2026-10-30

Links: `../protocol/subsonic-auth.md`, `../playback/stream-cache-identity.md`

---

## API profile

[VERIFIED] `ClientProfile.API` is the default. It adds `Accept-Encoding: zstd, gzip`, then unwraps response `Content-Encoding` values for API calls. Existing API timeout defaults are unchanged.

## Media profile

[VERIFIED] `ClientProfile.MEDIA` does not advertise encodings and does not unwrap response bodies. It prevents OkHttp's implicit gzip request header so the wire request carries no `Accept-Encoding` field and the caller receives the stored bytes.

[VERIFIED] The media read timeout is `max(requested, 60_000 ms)`, so callers may raise it but not fall below the media floor.

## Native dependency

[VERIFIED] The Android artifact is selected with `@aar`. The plain jar remains a unit-test-only dependency so JVM tests can exercise decoder behavior.

[UNKNOWN] Device execution coverage for the AAR native library. The instrumented assertion is `ZstdAndroidTest.androidArtifactLoadsNativeZstdAndDecodesPayload`; its debug test APK assembles, but it has not been installed or run.
