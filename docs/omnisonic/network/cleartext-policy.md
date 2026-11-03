# Cleartext policy — `cleartext-policy.md`

Purpose: Pin the app-wide cleartext HTTP policy (global allow via Network Security Config) and the private-host classifier that drives the "traffic will not be encrypted" warning for http providers on non-private hosts.

How derived: Decision (global cleartext allow + in-app warning), confirmed by the user on 2026-10-30 — see `../decisions.md`. Platform behavior from the Android Network Security Config documentation and AOSP `ManifestConfigSource`/`NetworkSecurityConfigProvider` sources. Robolectric limitation established empirically with a probe test in this repository.

Confidence:
- [VERIFIED] `app/src/main/res/xml/network_security_config.xml` sets `<base-config cleartextTrafficPermitted="true" />` and `AndroidManifest.xml` references it via `android:networkSecurityConfig="@xml/network_security_config"`.
- [VERIFIED] `android.permission.FOREGROUND_SERVICE` (base permission) is declared alongside the existing `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and `FOREGROUND_SERVICE_DATA_SYNC` type permissions; the type permissions are additional, not substitutes.
- [VERIFIED] With targetSdk >= 28 and no config, the platform default denies cleartext; the explicit config overrides it (Android NSC documentation; AOSP `ManifestConfigSource.DefaultConfigSource`).
- [VERIFIED] Robolectric 4.14.1 (sdk 34, targetSdk 36, no NSC) returns `true` from `NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("example.com")` both before and after the config exists: the framework startup path that installs the config-driven policy instance does not run under Robolectric, so the low-level permissive default stays. A Robolectric behavior test cannot distinguish the policy; enforcement must be verified on device.
- [UNKNOWN] On-device enforcement of the config (http to a LAN server succeeds). Manual device check pending; a connected device has been offered for this.
- [VERIFIED] `CleartextHostPolicy.isPrivateHost` classifies private IPv4 (RFC 1918, loopback 127/8, link-local 169.254/16, CGNAT 100.64/10), IPv6 ULA fc00::/7 and link-local fe80::/10, ::1, IPv4-mapped IPv6 by the embedded IPv4, `localhost`/`*.localhost`, and `*.local`; everything else, including malformed literals and blank hosts, is non-private.

Date: 2026-10-31

Links: `client-profiles.md`, `../decisions.md` (cleartext policy)

---

## Policy

[VERIFIED] Cleartext HTTP is permitted globally via the base config. This is required for the primary self-hosted case: a LAN server addressed by IP literal (for example `http://192.168.x.x:4533`), which a `domain-config` cannot match because NSC domain rules match domain names only, never IP literals.

## Warning contract (call site pending)

[VERIFIED] `CleartextHostPolicy.isPrivateHost(host)` in `:provider-api` is the pure classifier for the warning. The contract, to be wired when the provider-editing UI exists:

- For each saved block with `scheme == "http"`, compute `isPrivateHost(block.host)`.
- If false (for example a public domain over http), the save flow must show a confirmation warning that traffic to this server will not be encrypted.
- If true, no warning. All https blocks never warn.

The UI call site does not exist yet (no provider-editing screen); the classifier and its tests are in place.

## Verification status

- [VERIFIED] JVM tests pin the declared policy: config file content, manifest attribute, and the FGS base permission (`NetworkPolicyManifestTest` in `:app`), plus the classifier (`CleartextHostPolicyTest` in `:provider-api`).
- [UNKNOWN] On-device proof that an http URL is actually permitted at runtime. Needs an adb-connected device or emulator; offered but not yet available. Never fabricate this result.
