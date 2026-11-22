# Offline-first stream resolution — `offline-resolution.md`

Purpose: Pin how a queue entry resolves to an already downloaded file before the
provider is asked, which stored variant is chosen, what the resulting
`ResolvedStream` looks like, and what the player does with it. Phase 7 task 5c;
the download pipeline that produces these rows is `../storage/download-completion.md`.

How derived: code reading of `core-data/downloads/OfflinePlaybackSource.kt`,
`core-data/downloads/OfflineStorage.kt`, the `offline_media` read
(`OfflineFileDao.selectByProviderAndExternal`),
`playback/stream/OfflineFirstStreamResolver.kt`, `ProviderStreamResolver.kt`,
`OmniPlayerImpl.dataSourceFactoryFor`, `PlaybackCacheManager` and
`QueuePlayerImpl.schedulePrepareFollowing`, plus the tests
`OfflinePlaybackSourceTest` (7), `OfflineFirstStreamResolverTest` (12), the
offline cases in `OmniPlayerImplTest` (2) and `QueuePlayerImplTest` (3).
Evidence categories: own-source reading, in-repository test runs.

Confidence:
- [VERIFIED] Resolution order for a non-radio entry is: the exact desired
  variant's stored file, else the newest readable stored variant, else the
  provider URL, else null (resolver tests).
- [VERIFIED] A stored row is offered only while its `offlinePath` resolves to a
  non-empty regular file under the active root. An unavailable root, a missing
  file, a zero-length file and a directory all yield no candidate, so they fall
  through to the provider instead of becoming a playable source (source tests).
- [VERIFIED] Lookup is by `providerId` + `externalId`. A deleted library song, a
  renamed song and a moved song file all keep the offline entry playable. The
  source exposes the offline row's own `title`, not a join against the current
  `songs` row (source tests), while the resolver keeps the queue entry's display
  metadata unchanged because the queue snapshot owns presentation (resolver
  test).
- [VERIFIED] An offline stream is a direct `file://` URI
  (`URI("file", "", absolutePath, null)`, so spaces and non-ASCII names are
  percent-encoded) with `requiresNetwork = false`, `isRadio = false`,
  `transport = DIRECT_URI`, `startPositionMs = 0` and `expiresAtMs = null`
  (resolver tests; `OmniPlayerImplTest` reaches `STATE_READY` and a natural
  `Finished` from such a URI).
- [VERIFIED] Radio entries never consult the offline source — they always
  resolve through the provider, so a station cannot be replaced by a stale
  file (resolver test).
- [VERIFIED] Player-side, `DIRECT_URI` streams bypass the streaming cache:
  `dataSourceFactoryFor` returns a direct factory even with cache-first on,
  the source is not a `CacheDataSource`, and `cachedBytesFor(sourceKey)` stays
  `0` after playback (`OmniPlayerImplTest`).
- [VERIFIED] An offline lookup failure (database or storage error) falls back
  to the provider; `CancellationException` is rethrown (resolver test).
- [VERIFIED] A prepared entry that resolves to nothing prepares no next item;
  auto-advance then skips to the following playable entry and emits no
  queue-end `TrackChanged`. A next entry whose offline file is missing but
  whose provider URL works is prepared as the network fallback and adopted on
  auto-advance (`QueuePlayerImplTest`).
- [INFERRED] Airplane-mode playback, external-storage unmount and real-device
  transport behaviour are untested here; the source-level contract is that an
  unavailable root or file can only produce a fallback, never an error.

Date: 2026-11-22

Links: `stream-resolution.md`, `stream-cache-identity.md`,
`../storage/download-completion.md`, `../storage/offline-storage-layout.md`,
`../decisions.md` (D-45, D-56)

## Desired variant selection (Phase 8 task 3b, D-56)

- `OfflineFirstStreamResolver` takes a `DesiredStreamVariants` seam returning the desired `mediaKey` source key for an entry, or null for no exact preference. The exact match still wins; null keeps the deterministic newest-readable fallback. A selector failure degrades to null (never an error), mirroring the lookup-failure path. [VERIFIED JVM]
- Production `PlannedStreamVariants` reads the local renderer's `TranscodeSettings` and plans with `TranscodePlanner` for both stream slots over unknown source/renderer metadata. Only when Wi-Fi and cellular plan the same outcome is a variant desired; differing slots yield null instead of applying one network's settings to the other. Network-aware slot selection replaces this rule in TODO 5. Provider traits are the permissive default because a stored variant stays selectable even when the provider could not transcode it today. [VERIFIED JVM]
- All-off settings desire `auto:auto`, preserving the Phase 7 behavior. D-57 corrects the original mandatory-only mapping: `ServerTranscode(null, null)` has no explicit variant identity and returns null instead of aliasing the raw `auto:auto` key. No existing row is reidentified. The download slot never influences stream variant selection. [VERIFIED JVM]
- A desired key is an offline preference, not a bypass of offline-first fallback. Even mandatory settings keep the deterministic newest-readable fallback when the exact variant is absent; mandatory server-transcode refusal remains an online planner decision, not permission to discard or ignore a readable offline file. [VERIFIED JVM]
- The selector is bound through `PlaybackWiringModule.desiredStreamVariants`; the player and offline-first ordering are otherwise unchanged, and radio entries still never consult the offline source.

---

## Selection policy

```text
non-radio entry
  ├─ desired key supplied by DesiredStreamVariants, or null
  ├─ exact sourceKey match when the desired key is non-null
  ├─ else newest readable row by downloadedAt, tie-break highest id
  ├─ else provider resolution (online)
  └─ else null → the queue skips
```

[VERIFIED] All-off production settings supply `stream:<providerId>:<externalId>:auto:auto`, the Phase 7 `StreamKey(providerId, externalId).cacheKey`. Explicit bitrate/format plans supply their variant-qualified key. Differing network slots, selector failure, refusal, or a server-default `ServerTranscode(null, null)` supply no exact key. The fallback exists so a downloaded file is never ignored merely because its variant differs, and it is ordered by `downloadedAt` (nulls first) then row id, so two rows with equal timestamps still choose the same one on every resolve. The reserved `auto` format token is rejected by settings, so an explicit preference cannot alias the no-format sentinel (D-57).

## Offered files

[VERIFIED] `OfflinePlaybackSource.filesFor(providerId, externalId)` reads with
`ProfileScope.Unrestricted` (playback is not a surface, so a provider hidden in
the UI is still playable) and validates every row through
`OfflineStorage.resolveStoredPath`, which returns null when the published root
is unavailable or the path leaves the root. The additional checks are
`File.isFile` and `length() > 0`.

[VERIFIED] `OfflinePlaybackFile` carries only what playback needs: row id,
`sourceKey`, direct `uri`, `sizeBytes`, and the row's own `title`,
`bitrateKbps`, `downloadedAtMs`. It deliberately carries no provider path and
no song-row reference, so a library re-sync cannot orphan it.

## Stream shape and player behavior

[VERIFIED] The offline `ResolvedStream`:

| Field | Value |
|---|---|
| `uri` | `file:///<absolute path>` (percent-encoded) |
| `requiresNetwork` | `false` |
| `isRadio` | `false` |
| `transport` | `DIRECT_URI` |
| `startPositionMs` | `0` |
| `cacheKey` | the row's `sourceKey` |
| `expiresAtMs` | `null` |

[VERIFIED] The start position stays `0` — the same D-18 rule as provider
streams: only a queue-snapshot restore moves the playhead. Seeking, pausing,
resuming, repeat-one and an engine swap all work unchanged because the stream is
an ordinary direct source; after an engine swap the captured position is
re-applied to the same file (`OmniPlayerImplTest`).

[VERIFIED] The streaming cache is neither read nor written for these streams.
`cacheKey` is still the row's provider-stable key so cache introspection and
future features see one identity per variant, but `dataSourceFactoryFor` routes
`DIRECT_URI` through the direct factory, and the test asserts the streaming
cache holds zero bytes for that key after playback. A file played offline can
therefore never be mistaken for, or evict, a streamed cache entry.

## Failure and queue behavior

[VERIFIED] `OfflineStreamLookup` failures are swallowed into an empty candidate
list (cancellation still propagates), and a null provider result remains the
existing "unplayable entry" signal. `QueuePlayerImpl` then bounces to the next
entry; the engine's trimmed tail means no false end-of-queue event is emitted.

[VERIFIED] Prepared-next resolves through the same resolver, so a downloaded
next track is prepared as a direct file and a next track with no local file
falls back to its provider URL. When neither exists the queue prepares nothing
and still advances at the natural boundary, without ending the queue.

[UNKNOWN] Behavior with a physically removed SD card mid-playback, real
airplane-mode playback, and playback from an adopted external-storage root on a
device remain device-gated.

---

## Evidence (2026-11-20)

[VERIFIED] `:core-data` 643 tests / 0 failures / 0 errors / 0 skipped
(`OfflinePlaybackSourceTest` 7). `:playback` 314 / 0 / 0 / 0
(`OfflineFirstStreamResolverTest` 9, offline cases in `OmniPlayerImplTest` 2 and
`QueuePlayerImplTest` 3). `:app` debug 166 / 0 / 0 / 0. `:app:assembleDebug`,
`:core-data:lintDebug` and `:playback:lintDebug` exit 0 (three pre-existing
warnings, none in the new code). No schema change. Banned-name scan clean; the
new public names do not appear in `reference/extracted/theme/theme_keys.json`
or `reference/docs/THEMING.md` §4.

## Phase 8 audit evidence (2026-11-22, D-57)

[VERIFIED JVM] `/tmp/opencode/phase-8-audit-fix-gate.log` exited 0. `/tmp/opencode/phase-8-audit-fix-xml-totals.txt` records `:playback` **375/0/0/0** per variant, including `PlannedStreamVariantsTest` 7 and `OfflineFirstStreamResolverTest` 12. `mandatory alone does not alias the raw auto variant` proves the corrected server-default/no-exact-preference behavior; all-off exact matching and newest-readable fallback cases remain green. No row or schema migration was introduced.
