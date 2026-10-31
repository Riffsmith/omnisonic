# SESSION 2 — DECISION BRIEF (T8, T10, T11/D-8)

Purpose: Decision preparation only. No code changed, no commits made. Presents verified
evidence, options with consequences, and one recommendation per decision. Waits for answers.

How derived: Direct inspection of source, tests, manifest, build files, and git history in
this repository on 2026-10-30. No device, emulator, or network was used.

Confidence: Code/file claims below are [VERIFIED] by direct file inspection at the cited
locations. Platform-behavior claims are tagged separately. No Session 1 claims were trusted
without verification.

Date: 2026-10-30

Links: Prompt.md task definitions (T8, T10, T11); docs/omnisonic/00-template.md.

---

## 0. Repository state observed

- `main` is at `93e5849` ("fix(T7): page local MediaStore via query bundles"). Git confirms
  Session 1 = commits `881b63b`..`93e5849` (T1–T7). T8–T12 are not started. [VERIFIED]
- No `docs/omnisonic/session-reports/SESSION-1.md` exists. T1–T7 topic docs exist under
  `docs/omnisonic/{protocol,network,playback,providers,storage}/`. [VERIFIED]
- Uncommitted work-in-progress NOT part of T1–T7, left untouched:
  - modified `playback-api/.../queue/QueuePlayer.kt` (setQueue gains `sourceType`/`sourceData`)
  - untracked `playback/src/main/.../queue/QueuePlayerImpl.kt` + its test
  - `.gitignore` now ignores `Prompt.md`
  This looks like early queue/events work started out of session order. It is not mine to
  finish or discard this session. [VERIFIED]
- There is no `ROADMAP.md` at repo root (only a gitignored one under `reference/`). Relevant
  later for T24. [VERIFIED]

---

## 1. T8 — Manifest and network policy (cleartext HTTP)

### 1.1 Verified evidence

- `app/src/main/AndroidManifest.xml:5-6` declares `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and
  `FOREGROUND_SERVICE_DATA_SYNC`; the base `android.permission.FOREGROUND_SERVICE` permission
  is NOT declared. [VERIFIED — platform requires the base permission to start any foreground
  service; type-specific permissions are additional, not substitutes]
- `<application>` (lines 14–18) has no `android:networkSecurityConfig` and no
  `usesCleartextTraffic`; `app/src/main/res/xml/` does not exist. [VERIFIED]
- `app/build.gradle.kts:16` sets `targetSdk = 36`. [VERIFIED] For targetSdk >= 28 the platform
  default network security config rejects cleartext HTTP, so `http://192.168.x.x:4533` fails
  with "Cleartext HTTP traffic not permitted". [VERIFIED platform rule; the on-device failure
  itself is [INFERRED] — cannot be reproduced on desktop JVM; will be proven with a
  Robolectric `ShadowNetworkSecurityPolicy` test + a documented manual device check at
  implementation time]
- `ProviderConfigMapper.kt:58` accepts scheme `http` as valid provider input; secondary
  blocks even default to `http` (`ProviderConfigMapper.kt:51`). [VERIFIED] The data layer
  already treats HTTP servers as first-class.

### 1.2 Options

| | (a) Allow cleartext globally | (b) domain-config for private hosts | (c) Global allow + in-app warning |
|---|---|---|---|
| Mechanism | `network_security_config.xml` with `base-config cleartextTrafficPermitted=true` | NSC `<domain-config>` entries | (a) + host-classifier + warning at provider save |
| LAN IP servers (`http://192.168.1.10:4533`) | WORKS | **FAILS** — NSC matches domain names only, never IP literals/CIDR ranges; the config is also static XML and cannot react to user-added providers | WORKS |
| `.local` / hostname servers | WORKS | WORKS (for statically enumerated names only) | WORKS |
| Public http servers | WORKS, silently | blocked (good) | WORKS, but user sees an explicit "traffic will not be encrypted" warning when saving the provider |
| Implementation cost | smallest (1 XML file + manifest attr) | moderate and **does not fix the main bug** | moderate: NSC + manifest + pure classifier function (private IPv4 ranges, IPv6 ULA/link-local, `localhost`, `.local`) + warning hook |
| What it enables | Every self-hosted setup, incl. the primary LAN Navidrome case, works immediately | Only hostname servers; the reported scenario stays broken | Everything (a) enables, plus informed consent for public hosts |
| What it does not do | No signal to users who accidentally point the app at a public http host | — | Warning is advisory only; user may still accept |

Practical notes:
- Option (b) is rejected on evidence: the decision scenario in Prompt.md (LAN IP) is exactly
  what NSC domain-config cannot match.
- The warning surface for (c) is `feature-profiles`, which today contains only a
  `ModuleMarker.kt` (no add-provider UI yet). [VERIFIED] So (c) lands in T8 as: NSC + manifest
  + a unit-tested classifier (e.g. in `provider-api` or `core-data`) + documented integration
  point; the UI call-site arrives with the profiles screen.

### 1.3 Recommendation

**(c)** — same as the Prompt's recommendation. It fixes the primary LAN case, and the
classifier is cheap, pure, and testable. If you want the smallest possible T8, (a) is
acceptable and the warning can be added later without any migration cost.

### 1.4 Your answer needed

> **T8: Which policy? (a) global cleartext allow, (b) NSC domain-config (rejected on
> evidence, listed for completeness), or (c) global allow + in-app warning for http on
> non-private hosts? My recommendation: (c).**

---

## 2. T10 — Pruning server-deleted songs that have playCount > 0 or outbox rows

### 2.1 Verified evidence (data ownership model first — it drives everything)

- No pruning exists today: `SubsonicLibrarySyncer.kt` deletes nothing except playlist entries
  during playlist rebuild (line 302). Vanished songs/albums/artists/playlists stay forever.
  [VERIFIED]
- The entire network walk runs inside ONE `database.withTransaction` (lines 50–59). [VERIFIED]
- **playCount is locally-owned.** Sync preserves `existing?.playCount` and IGNORES the server
  value (`SubsonicLibrarySyncer.kt:201`); local playback increments it via
  `SongDao.addPlayCount` (`SongDao.kt:33`). Deleting the song row destroys local play history.
  (The playback→playCount wiring has no production caller yet — only tests — so today this
  data comes from sync merges/tests, but the Phase 4 pipeline is designed to produce it.)
  [VERIFIED]
- **Outbox rows do not depend on the song row.** `outbox_items` keys on
  `(providerId, mediaType, externalId, action)` strings (`OutboxItemEntity.kt:10`). Deleting
  a song row neither deletes nor blocks its outbox rows, and draining is unaffected in every
  option. But if the row is deleted, the local record of the not-yet-submitted plays
  (`songs.playCount`) is destroyed before the drain can represent it. [VERIFIED]
- **No FK or trigger cascade on `songs`.** Cascade triggers fire only on `providers` delete
  (`ProviderCascadeTriggers.kt`). Deleting a song orphans `song_artists`, `chapters`, and
  `playlists_entries.songId` rows (no FKs exist). [VERIFIED]
- `offline_files.sourceKey` / `download_items.mediaKey` are opaque strings with no established
  song linkage yet (Phase 7); the syncer cannot currently tell whether a song is downloaded.
  [VERIFIED] A tombstone flag is the only robust way to keep downloaded-but-delisted songs
  playable later.
- `SongEntity` has no `missingOnServer` column. [VERIFIED] T16's v2 migration is already
  planned, so the column has a free ride if we decide now.

### 2.2 Options

**A. Tombstone (keep the row, add `missingOnServer=true`; hidden from library browse/search)**

- Enables: local playCount/lastPlayed history survives server deletions; pending outbox
  scrobbles keep their local record until drained; when Phase 7 downloads exist, a downloaded
  song that the server later delists stays visible/playable in the downloads section; if the
  same externalId reappears on the server (restored backup, moved folder), sync flips the
  flag back and nothing was lost; album/artist aggregates don't silently drop history.
- Does not: the song stays invisible in normal library views (requires every library query
  to filter the flag — mechanical but pervasive); tombstones slowly accumulate for
  externalIds that are never reused (bounded by what the server actually deleted).
- Cost: `missingOnServer` column (folded into the already-planned T16 v2 migration), filter
  updates in library queries, mark-and-sweep logic, tests.

**B. Delete outright (plain mark-and-sweep)**

- Enables: simplest possible pruning; no schema change; no query changes.
- Does not: destroys local playCount/lastPlayed for the deleted song; pending play-count
  outbox rows lose their local record (the drain itself may still succeed server-side, but
  the app's own history is gone); playlist entries and artist joins dangle; future downloads
  have no row to anchor to.
- Cost: none up front, permanent local-data loss.

**C. Delete the row, keep history in playback_events / a T22 per-play table only**

- Enables: same browse-UX as B with history preserved at the event level.
- Does not: cannot anchor downloads or favorites of delisted songs; couples T10 to the T22
  listen-history redesign; largest migration surface of the three.

### 2.3 Recommendation

**A (tombstone)**, with these exact rules:
1. After a FULL sync only: delete rows for this provider whose externalId was not seen,
   EXCEPT when `playCount > 0` OR a pending outbox row exists for that externalId — those
   become `missingOnServer=true` instead. (Never prune on diff sync — diff sync does not see
   the whole server.) [matches Prompt.md's proposal; confirming per rule 5]
2. Albums are pruned when no non-tombstoned child songs remain (tombstoned songs keep their
   album alive); artists likewise; vanished playlists are pruned outright.
3. Edge case left to you: `isFavorite=true` does NOT block deletion in my proposal (a star on
   a server-deleted song is meaningless), only playCount>0 / outbox rows do, per the Prompt.

### 2.4 Your answer needed

> **T10: (A) tombstone with `missingOnServer`, (B) delete outright, or (C) delete + event-level
> history only? My recommendation: (A), with the rules in 2.3. Please also confirm the
> favorite edge case (2.3.3) and the albums/artists/playlists rule (2.3.2).**

---

## 3. T11 / D-8 — Backdated scrobble capability detection

### 3.1 Verified evidence

- `ProviderCapabilities.supportsBackdatedScrobbles = extensionNames.contains("formPost") ||
  contains("transcodeOffset")` (`provider-api/.../MediaModels.kt:113-114`). Neither extension
  has anything to do with the scrobble `time` parameter; the KDoc claims a spec connection
  that does not exist. [VERIFIED — misleading KDoc will be removed per T11]
- A cold `SubsonicClient` starts with `ProviderCapabilities()` (all false,
  `SubsonicClient.kt:54`); only `probe()` writes capabilities (`:56-68`). [VERIFIED]
- `probe()` has NO production call site (only tests), and `OutboxDrainer.drain` never probes
  (`Outbox.kt:45-72`). [VERIFIED] Therefore a cold app drains offline plays WITHOUT
  timestamps — confirmed by the existing test "scrobble submissions omit the timestamp on
  unprobed servers" (`SubsonicClientTest.kt:344`). [VERIFIED]
- `submitPlays` sends the SAME `lastPlayedAtMs` for all N submissions (`SubsonicClient.kt:303-314`);
  the existing test asserts the equal times (`SubsonicClientTest.kt:338-339`). [VERIFIED]
  Per the sync/listen-history requirements, N plays must become N submissions backdated
  one millisecond apart.
- `OutboxQueue.enqueuePlayCountDelta` has no timestamp parameter (`OutboxQueue.kt:59-67`) and
  `merge()` keeps the EXISTING `lastPlayedAt` (`:79`), so plays 2..N inherit play 1's
  timestamp once timestamps exist at all. [VERIFIED]
- `serverSoftwareHint` is already plumbed end-to-end: `ProviderEntity.optionsJson` →
  `ProviderConnection` (`ProviderConfigMapper.kt:41`) → `probe()` (`SubsonicClient.kt:65`) →
  capabilities — but nothing sets it today (no profiles UI yet), and the capability rule
  ignores it. [VERIFIED]
- Category-level reference note (studied-app material, clean-room-safe phrasing): the
  scrobble `time` parameter is part of the base Subsonic API, and the common self-hosted
  servers (notably the hint-gated set navidrome/lms/funkwhale) honor it; the studied
  implementation switched backdating on from a **server-software hint**, not from extension
  probing. [INFERRED — re-derived here from the reference audit notes, not from any spec
  quote in this repo]

### 3.2 Options

**(a) Hint-based default + per-provider override** (Prompt's recommendation)

- Rule: `ProviderCapabilities.supportsBackdatedScrobbles` becomes a function of
  `serverSoftwareHint`; `OptionsJson` gains `backdateScrobbles: auto|on|off` (per provider).
  - `on` → always send `time` (user says the server honors it).
  - `off` → never send `time`.
  - `auto` → ON when the hint names a known-honoring server (initial set: `navidrome`;
    extendable), OFF when the hint is unknown/absent (conservative, deterministic).
- Enables: correct backdated scrobbles out of the box for the primary server type; an
  explicit per-provider kill switch if some server mishandles `time`; no extra network
  round-trip; the override rides the existing `optionsJson` plumbing (already parsed in
  `ProviderConfigMapper`), so it needs no new tables.
- Does not: backdate anything TODAY in practice — no UI writes `serverSoftwareHint` or
  `optionsJson` yet, so every provider is effectively "unknown/absent" → OFF until the
  profiles screen exists. The cold-app bug itself is fixed regardless by the mechanical
  T11 fixes (below), but with (a) those submissions still carry no `time` until you set a
  hint or override.
- Risk: minimal — a server that disliked the param only affects users who opted `on`.

**(b) Probe-only**

- Meaning: send `time` only after `probe()` ran. If that means "probe as gate, then always
  send time", backdating activates immediately for every server and the cold-client hole
  closes — but it sends `time` to servers we know nothing about (some strict/older servers
  error on unknown params; that would fail ALL scrobbles, worse than losing the timestamp),
  and it adds a mandatory round-trip before the first scrobble. If it instead means "keep
  using the extension list as the signal", it is the current bug with a probe in front:
  `formPost`/`transcodeOffset` still say nothing about `time`, so servers like the primary
  target that don't advertise them would stay OFF forever.
- Enables: nothing that (a) doesn't, in either interpretation. REJECT.

**(c) Always-on (send `time` whenever a timestamp is available)**

- Enables: backdating works immediately for all servers, no hints, no UI, no probe.
- Does not: provides no escape hatch if a server mishandles `time`; contradicts the
  D-8 requirement of per-provider override.
- Risk: a rejecting server breaks scrobbles entirely (no fallback path).

### 3.3 Recommendation

**(a)**, with the mechanical T11 fixes implemented regardless of choice (they are not part
of the decision): `OutboxActionPayload.playTimestamps` list (cap 100, keep newest) with
graceful decode of old payloads; `enqueuePlayCountDelta(..., playedAtMs, ...)`; per-timestamp
submissions with strictly-increasing times (+1 ms on ties); and a lazy one-per-process
`probe()` in `OutboxDrainer` before the first play-count task so probe order is no longer a
test-only accident. To close the interim gap noted in 3.2(a), the profiles UI should write
`serverSoftwareHint` (defaulting new providers to `navidrome`) when it is built — I will
note that in the docs but it is out of T8–T12 scope.

### 3.4 Your answer needed

> **T11/D-8: (a) hint-based default + `auto|on|off` override (recommended, note it is inert
> until the profiles UI writes hints), (b) probe-only, or (c) always-on? And for (a): confirm
> unknown/absent hint → OFF (conservative) as the `auto` default.**

---

## 4. What each answer unblocks

| Decision | Unblocks |
|---|---|
| T8 | T8 implementation (manifest + NSC + optional classifier) → Gate A T9–T12 |
| T10 | T10 pruning tests; `missingOnServer` column folded into the T16 v2 migration (one migration, as Gate D requires) |
| T11/D-8 | Capability rule rewrite, outbox timestamp payload schema, drainer lazy probe |

## 5. Not verified this session / needs environment

- The on-device cleartext failure message: desktop JVM cannot reproduce it. At T8
  implementation: Robolectric `ShadowNetworkSecurityPolicy` test + a documented manual device
  test will cover it (per Prompt.md T8 "Proof").
- No device, emulator, API, or network was used; nothing here is instrumented-test evidence.

---

## 6. DECISIONS RECEIVED (2026-10-30) — all three answered, affirmed by the user

| # | Question (sections above) | Answer |
|---|---|---|
| T8 (§1.4) | Cleartext policy a/b/c | **(c)** — global cleartext allow + in-app warning for http on non-private hosts |
| T10 (§2.4) | Pruning A/B/C + sub-rules | **(A) Tombstone**, "with the exact 3 rules recommended in the 2nd session report" (§2.3 rules 1–3 all confirmed) |
| T11/D-8 (§3.4) | Detection a/b/c + `auto` default | **(a)** — hint-based default + `auto|on|off` per-provider override; the presented `auto` default (unknown/absent hint → OFF) was not contested and stands as confirmed |

Affirmation notes:
- T10: user explicitly confirmed §2.3 **including** rule 3 (isFavorite does NOT block
  deletion) and rule 2 (albums/artists pruned when no live children remain; playlists pruned
  outright).
- T11: "unknown/absent hint → OFF in `auto`" is part of the recorded decision (it is what
  "hint-based default" means); the user may still veto at implementation review.
- User offered an adb-connected Android device for any testing that needs one (see §7.6).

---

## 7. Handoff to the implementation session (Session 3)

Scope: **T8–T12** (Gate A completion + Gate B), per Prompt.md's checkpoint "report after
T8–T12". T9 and T12 need no decisions and can proceed directly. One task at a time, one
commit per task (`fix(T<n>): <summary>`), reproduce first, `./gradlew test` + `:app:assembleDebug`
per touched module, banned-name scan on changed files.

### 7.1 T8 — locked design
1. `app/src/main/res/xml/network_security_config.xml` with `base-config
   cleartextTrafficPermitted="true"`; `android:networkSecurityConfig` on `<application>`.
2. Add `android.permission.FOREGROUND_SERVICE` to the manifest (base permission; the two
   type-specific permissions already present at lines 5–6 are not substitutes).
3. Host classifier (pure function; suggested home: `provider-api` or `core-data`): treat as
   private → private IPv4 ranges (RFC 1918 + CGNAT + link-local), IPv6 ULA/link-local,
   `localhost`/loopback, `.local` suffix. Everything else over http = non-private.
4. Warning surface: `feature-profiles` has no UI yet (ModuleMarker only) — land the
   classifier + unit tests now, and document the call-site contract in a new
   `docs/omnisonic/network/` doc (cleartext policy + classifier) per Prompt.md rule 7.
5. Proof per Prompt.md: manifest lint passes; Robolectric test that an http URL is permitted
   under the policy (ShadowNetworkSecurityPolicy); a manual on-device check is listed as
   pending in §5 — offer of a device applies here if the user wants the manual proof done.

### 7.2 T9 — no decision needed
`C.WAKE_MODE_NETWORK` via `EngineConfig` parameter; WAKE_LOCK already declared.

### 7.3 T10 — locked design (decision A)
- Mark-and-sweep pruning, **FULL sync only** (never on diff sync).
- Song rows not seen on the server: DELETE if `playCount == 0` AND no pending outbox row
  exists for that externalId AND (no other local-only state per §2.3 — only playCount/outbox
  gate; isFavorite does NOT block deletion); otherwise keep and set `missingOnServer=true`.
- `missingOnServer: Boolean = false` must fold into the **T16 v2 migration** (Gate D = ONE
  migration). Do not ship a standalone migration in T10; T10 adds the column via a new
  entity field + tests, and the migration lands with T16. Re-check with the user if that
  ordering is uncomfortable.
- Albums pruned when no non-tombstoned child songs remain; artists likewise; vanished
  playlists pruned outright.
- All other T10 items from Prompt.md (per-album transactions, column-scoped server-field
  updates with SERVER/LOCAL column classification test, resume merge rule, TextNormalizer,
  `ProviderAlbumDetails` single-request album, DAO lookups replacing O(n²) scans) proceed as
  specified there.

### 7.4 T11 / D-8 — locked design (decision a)
- Capability rule: `supportsBackdatedScrobbles` derives from `serverSoftwareHint` +
  override; remove the formPost/transcodeOffset rule and its KDoc.
- `OptionsJson` gains `backdateScrobbles: auto|on|off` (default `auto`). `auto` → ON only
  when hint is in the known set (initially `navidrome`), else OFF. `on`/`off` force.
- Mechanical fixes regardless (Prompt.md T11): `OutboxActionPayload.playTimestamps` (cap
  100, keep newest; decode old payloads gracefully); `enqueuePlayCountDelta(..., playedAtMs,
  ...)`; merge keeps the newest timestamps; `submitPlays(externalId, timestamps)` strictly
  increasing (+1 ms on ties); OutboxDrainer lazily probes once per process before the first
  play-count task.
- Interim gap (recorded, not blocking): nothing writes hints/options yet, so `auto` = OFF
  everywhere until the profiles UI lands; the profiles screen should write
  `serverSoftwareHint` (defaulting new providers to `navidrome`).

### 7.5 T12 — no decision needed
ProviderClientRegistry + failover completion per Prompt.md.

### 7.6 Environment / leftovers for Session 3
- **Uncommitted working-tree changes exist and are NOT part of T8–T12**: modified
  `playback-api/.../queue/QueuePlayer.kt` (setQueue gains sourceType/sourceData), untracked
  `playback/.../queue/QueuePlayerImpl.kt` + `QueuePlayerImplTest.kt`, `.gitignore` adds
  `Prompt.md`. The implementer must not silently commit these with fix tasks; ask the user
  or commit them separately first.
- No `SESSION-1.md` exists; Session 1 = commits 881b63b..93e5849 (T1–T7). No ROADMAP.md at
  repo root (T24 concern, not Session 3's).
- ADB device offer: a connected device would serve the T8 manual cleartext proof (and the
  still-pending T3 zstd androidTest + T5 API-29 instrumented check from Session 1). Ask the
  user to connect it when needed; never fabricate device results.
- `.agents/memory/open-questions.md` and `.agents/memory/decisions.md` were updated today to
  supersede the stale D-8 resolution (the old text codified the buggy formPost/
  transcodeOffset rule); Session 3 should treat THIS document as the authoritative record.
