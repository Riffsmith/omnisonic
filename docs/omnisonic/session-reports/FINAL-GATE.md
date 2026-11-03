# FINAL GATE — T23, T24, and the full task table

Date: 2026-11-03
Head commit: `3899a67` (`fix(final-gate): exercise failover hysteresis in the live Navidrome acceptance test`)
Working tree: clean (`git status` — nothing to commit). Branch `main`, not pushed.
Phase 4 work: **none started.**

---

## 1. Final gate result

`./gradlew clean test assembleDebug assembleRelease lint` → **BUILD SUCCESSFUL**
(twice: once before, once after the last test-only commit; second run
`BUILD SUCCESSFUL in 16s`, 1448 tasks, 0 failures).

| Module (debug+release) | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| app | 12 | 0 | 0 | 0 |
| core-data | 328 | 0 | 0 | 0 |
| core-model | 44 | 0 | 0 | 0 |
| core-network | 20 | 0 | 0 | 0 |
| playback | 274 | 0 | 0 | 0 |
| playback-api | 20 | 0 | 0 | 0 |
| provider-api | 116 | 0 | 0 | 0 |
| provider-local | 24 | 0 | 0 | 0 |
| provider-navidrome | 56 | 0 | 0 | 8 |
| **Total** | **894** | **0** | **0** | **8** |

The 8 skips are the env-gated `NavidromeLiveAcceptanceTest` assumptions in the
default run; they were additionally executed live (§5). APKs produced:
`app-debug.apk` (42.8 MB), `app-release-unsigned.apk` (5.5 MB, minified +
resource-shrunk, `assets/dexopt/baseline.prof` present). Lint: green (0 errors).

---

## 2. Task table (Prompt.md requirement)

| Task | Problem confirmed | Test added | Commit |
|---|---|---|---|
| T1 auth double-encoding / apiKey mixing | Yes | username `a+b@c.d e` round-trip; apiKey mode has no u/t/s/p; legacy `enc:` once-encoded | `881b63b` |
| T2 unstable cache keys | Yes | same URL twice (fresh salts) → zero upstream requests on second read; `isCached` across builds | `20e0699` |
| T3 zstd on Android + MEDIA client profile | Yes | MEDIA sends no injected Accept-Encoding, raw body; native-load → IOException; **androidTest on emulator NOT run** (§6) | `936c3b7` |
| T4 media reads bypass provider client | Yes | header-required stream succeeds through `OmniPlayerImpl` and fails without it; mTLS handshake through the data-source path | `7e25927` |
| T5 SQLite UPSERT on API 29 | Yes (3.22 < 3.24, AOSP-verified) | existing DAO tests unchanged; `DaoSqlCompatibilityTest` scanner (no ON CONFLICT/RETURNING/window functions) | `5c978eb` |
| T6 NULL aggregate crash | Yes | AVERAGE + MINIMUM on empty album (by id and by provider) → 0/null | `251d9f9` |
| T7 Local provider vs real MediaStore | Yes | same-title albums stay separate; fake provider rejects LIMIT-in-sortOrder; Bundle-args honoured | `93e5849` |
| T8 manifest + cleartext policy | Yes | manifest lint; private-host classifier tests (decision c) | `454a6fa` |
| T9 wake mode | Yes | NETWORK default; locks held while playing, released on pause (reworked deterministic in T23) | `1b94084` |
| T10 sync transactions/merge/pruning | Yes | outbox enqueue completes during sync; local columns survive; resume merge; tombstone prune vs diff; N+constant requests | `acc254d` |
| T11 scrobble correctness | Yes | 3 plays → 3 exact timestamps (1 ms ties); cold drain probes once; `off` omits time | `0b7eff9` |
| T12 failover + client lifecycle | Yes | flip after 2 failures; primary re-probe after interval; preference persisted; one client per provider id | `9b10202` |
| T13 stable exposed player | Yes | session identity survives engine swap; SEEK_TO_NEXT/PREVIOUS follow the queue; gapless smoke | `4ffcce6` (+ `3d1a157` T13b) |
| T14 sink processors | Yes (measured: mid-stream change produced 0 output bytes) | real-pipeline mid-stream fade/mono/RG output measurements; per-engine chain independence | `d9cee18` |
| T15 offload/extension honesty | Yes (bytecode-verified) | `offloadEffective`/`extensionAvailable` tests; DecoderPreference removal test | `a62ec69` + `8e1c2df` |
| T16 schema v2 (one migration) | Yes | real 1→2 migration test (v1 seed, data intact, triggers, defaults, history copy, queue restore, cascades) | `d4b517c` |
| T17 REPLACE upserts | Yes | recursive-triggers-ON provider-edit keeps songs; re-upsert keeps id + join rows | `6f855c0` |
| T18 enum ordinals | Yes | name-based keys; legacy ordinal fallback; unknown → default | `a437204` |
| T19 swallowed CancellationException | Yes (3 of 4 sites red; syncer partial) | drain-cancel records no failure; client/probe cancellation tests; syncer guard | `6d936e0` |
| T20 outbox contract | Yes | 4 red drainer tests + 2 core-data reds; golden JSON round trip | `6f6af04` |
| T21 logging/credentials | Yes | scrubbed throwable to sinks; `CredentialsUnreadable` surfaces; logcat sink; BackupExcludes | `9e67f8b` |
| T22 history shape | DECISION only (folded into T16) | per-play table + rollup view tests (inside T16/T16-4) | `d4b517c` |
| T23 build hygiene | Yes (all 8 sub-items verified in code first) | WakeModeTest rework (mapping + deterministic lock lifecycle), `ProviderClientRegistryTest` +2 URL-routing tests, release/minification + `testReleaseUnitTest` (445) + lint | `0ea84f7` |
| T24 clean-room + roadmap | Yes | MigrationHarnessTest rename battery (old tables/indices/triggers absent, data survived, new indices + triggers present); `DaoSqlCompatibilityTest` green; banned scan over `git ls-files` clean (278 files) | `dcb59a5` |

---

## 3. Decisions and your answers

| Decision | Your answer |
|---|---|
| T8 cleartext HTTP policy | **(c)** global cleartext allow via network security config + in-app warning for http on non-private hosts (Session 2) |
| T10 server-deleted data | **(A)** tombstone: prune on full sync only; keep + `missingOnServer=true` when local-only state exists (Session 2) |
| D-8 / T11 scrobble backdating | **(a)** hint-based default (`auto` = ON for `navidrome`) + `backdateScrobbles: auto|on|off` override (Session 2) |
| T13 player architecture | **Option A** — stable `ForwardingPlayer` exterior, full effective-order timeline, `prepareNext` + auto-advance; ROADMAP §0.3 updated to match the code (Session 4/5) |
| T15 FFmpeg decoder | **(3)** defer the decoder extension; remove the dead preference (Session 5) |
| T22 listen history | **(b)** per-play table + per-day rollup view, folded into the v2 migration (recorded `SESSION-T16-1-AUDIT.md` §4.3) |
| T24 table renames | **Option C** — fold into the existing v2 migration: `playlists_entries→playlist_items`, `internet_radios→radio_stations`, `download_items→download_queue`, `offline_files→offline_media`, `sync_status→sync_state` (this session). Precondition verified first: `adb devices` empty, no emulator images, no install command anywhere in project history — no v2 database exists outside this repo's test harnesses |

This session additionally selected (engineering, not DECISION-gated): AGP 8.9.1 /
Gradle 8.11.1 (official minimum for compileSdk 36), Kotlin 2.2.10 + KSP
2.2.10-2.0.2 with `ksp.useKSP2=false` (media3 1.11.1 forces stdlib 2.2;
Room 2.6.1 fails under KSP2), Hilt 2.57.2 (2.60+ requires AGP 9), media3 1.11.1
(current stable per Maven metadata). All recorded in
`docs/omnisonic/toolchain/build-stack.md`.

---

## 4. T23 — what was done

1. **Release minification**: `isMinifyEnabled=true` + `isShrinkResources=true`;
   Moshi switched from `KotlinJsonAdapterFactory` (reflection) to
   `moshi-kotlin-codegen` via KSP across `core-data`, `provider-api`,
   `provider-navidrome` (33 DTO/payload classes annotated). Proof:
   `assembleRelease` green; `mapping.txt` keeps the generated `*JsonAdapter`
   classes (Moshi consumer rules); `testReleaseUnitTest` 445/445 green.
2. **Toolchain**: AGP 8.5.2 → 8.9.1 (official minimum for compileSdk 36, verified
   against the published table, not guessed), Gradle 8.10.2 → 8.11.1,
   Kotlin 2.0.0 → 2.2.10, KSP → 2.2.10-2.0.2 (`ksp.useKSP2=false`), Hilt → 2.57.2.
   `android.suppressUnsupportedCompileSdk` NOT needed.
3. **Root build file**: all plugin aliases declared `apply false` at the root;
   `rootProject.buildDir` replaced with `layout.buildDirectory`.
4. **`:baseline-profile`** converted from `androidLibrary` to `com.android.test`
   with `targetProjectPath = ":app"`; the hand-authored profile still ships
   (verified inside the release APK).
5. **Packaging**: broad `pickFirsts += "*/*/*.so"` removed; the real native set
   (zstd-jni AAR, 12 ABIs) packages with zero duplicate conflicts.
6. **Coil**: `coil-network-okhttp` added; an `ImageLoader` (Hilt singleton, set as
   Coil's app-wide loader) routes every request through
   `ProviderRoutedCallFactory` → `ProviderClientRegistry.apiClientForUrl` (new
   resolver matching scheme/host/port on both connection blocks) so images ride
   the provider's headers/mTLS/trust anchors; unknown URLs fall back to a plain
   client. Registry routing covered by 2 new tests. The registry binding also
   completes the planned `onCredentialsUnreadable` → `ProviderStatusTracker`
   wiring (first production consumer).
7. **media3 1.3.1 → 1.11.1**: all playback tests re-run (274, green). Handled:
   removed `hasNext/hasNextWindow/hasPrevious/hasPreviousWindow`; `@CallSuper`
   `isActive()` (redundant overrides dropped — super now implements the T14
   always-active contract exactly); wake/wifi locks now permission-gated and
   clock-scheduled (`:playback` declares `WAKE_LOCK`; lock assertions moved to
   deterministic `WifiLockManager`/`WakeLockManager`-level tests + the
   `WakeMode → C.WAKE_MODE_*` mapping test); lint issues fixed.
8. **Module markers**: four files moved so directory matches package
   (`core-model`, `core-data`, `core-network`, `provider-api`).

## 5. T24 — what was done

- **Table renames** (your approved mapping, option C) folded into the existing
  `MIGRATION_1_2`: trigger teardown preamble (18 v1-named triggers dropped),
  `download_queue` rebuild, four `ALTER TABLE RENAME` steps with index
  recreation under the v2 names, trigger reinstall. `SCHEMA_VERSION` stays 2;
  `2.json` regenerated; `1.json` untouched (verified via git status);
  `Migrations.ALL` still exactly one step; no destructive fallback.
- **Tests**: `MigrationHarnessTest` seeds a v1 DB with rows in ALL five renamed
  tables; after migration asserts old tables absent, data survived field-by-field,
  the 11 new index names exist and the 10 old ones don't, the six renamed trigger
  names exist and the old ones don't, and provider delete cascades every renamed
  table. `DaoSqlCompatibilityTest` green. Full suite green.
- **Prohibited comments** rewritten: `ConnectionSpec` ("see app docs (PROVIDERS
  §multi-URL)"), `ProviderConnection` ("see .agents/memory/decisions.md" +
  wrong column names), `ReplayGain.kt` snake_case column names → real camelCase
  columns.
- **ROADMAP.md scrubbed** (the studied app's style-samples path replaced with
  `<style-samples dir>` in both occurrences — the literal old path is not
  reproduced here or anywhere tracked) and reconciled: (1) D-2 = triggers, no FK pragma;
  (2) per-play logging recorded per T22 (1.1 table, K8, Phase 6 risk resolved,
  Phase 2 line annotated); (3) Phase 3 "mTLS gate test" deliverable line removed
  (1.2 says no gate); (4) D-8 marked resolved with the hint-based rule (1.3 table,
  section 8, R-1); (5) §0.3 table matches the shipped T13 code; (6) Gate-C/T-fix
  acceptance lists added to Phases 3 and 4.
- **Banned-name scan**: `scripts/check-banned-names.sh $(git ls-files)` over all
  **278 tracked files → clean (exit 0, no findings)**.

---

## 6. Verification that could not be performed (and what it needs)

1. **On-device smoke of the minified release APK** (T23 proof item): the R8'd APK
   builds, its mapping/seeds are sane, and release-variant unit tests pass, but
   nothing on this host can execute it. Needs: one device or emulator (API 29+),
   `adb install app-release-unsigned.apk` signed with a debug key, launch, play.
2. **T3 androidTest** — zstd decode on an emulator: no emulator exists here
   (`/opt/android-sdk` has no system images). Needs: an API 29+ emulator.
3. **On-device session surfaces** (T13/T13b): Bluetooth/lock-screen control,
   audible gapless handoff, audible fade/RG on real output — needs hardware
   (carried from earlier sessions).
4. **Real-device `recursive_triggers` default** — informational only since T17
   removed every REPLACE upsert; needs any device.
5. **R8 "error occurred when parsing kotlin metadata" warnings** (56, non-fatal):
   AGP 8.9.1's R8 predates Kotlin 2.2 metadata. Cosmetic; the silencing path is
   AGP 8.13+/R8 8.13.19 (Gradle 8.13) — deferred to keep the minimal
   compileSdk-36-verified bump. [INFERRED from AGP release notes]

## 7. Phase 3 acceptance list — run against a real Navidrome

Credentials were provided at `~/.config/omnisonic/navidrome.env`
(`OMNISONIC_NAVIDROME_URL/USER/PASS` — values never printed). Run:
`:provider-navidrome:testDebugUnitTest --tests "*NavidromeLiveAcceptanceTest*"`
→ **4/4 PASS**:

- `live probe and walk counts` — probe + artist/genre/radio/playlist/bookmark/album walks.
- `live favorite toggle restores initial starred state` — setFavorite true/false round trip.
- `live bookmark create-delete is net zero` — save/list/delete leaves no residue.
- `live failover completes through secondary when primary is refused` — dead
  primary refused; the next call completed through the secondary with the
  preferred-block change observed.

One finding fixed during this run (`3899a67`): the failover live test expected a
flip on the FIRST call, contradicting the committed T12 hysteresis contract
(`a single primary failure surfaces without flipping`, `FLIP_FAILURE_THRESHOLD =
2`) and the ROADMAP wording ("finishes the **next** sync through the secondary").
The test now fails once, then completes through the secondary. The remaining
ROADMAP Phase 3 acceptance items are covered by the unit suites listed in §2
(zstd-on-device is the only env-blocked item, §6.2).

---

## 8. Commits and tree state

- `0ea84f7` fix(T23): build hygiene …
- `dcb59a5` fix(T24): rename legacy tables …
- `3899a67` fix(final-gate): exercise failover hysteresis in the live Navidrome acceptance test
- Working tree: **clean**. All 24 tasks have their own commits (§2). Nothing pushed.

Every task from Prompt.md is complete. **Stopping here — no Phase 4 work will
begin until you say "continue Phase 4".**
