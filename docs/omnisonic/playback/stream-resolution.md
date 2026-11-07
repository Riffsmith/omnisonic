# Stream resolution and playback data seams — `stream-resolution.md`

Purpose: Pin the production `StreamResolver` (queue entry → playable `ResolvedStream`),
the song-row-backed `ReplayGainSource`, the no-op `QueueSource` seams, and the
history-less scrobble-point emission in the controller.

How derived: playback-api contracts (`StreamResolver`, `ReplayGainSource`,
`QueueSource`, `PlaybackEvents.ScrobblePoint`), the implementations in
`playback/stream/`, `playback/source/`, `playback/controller/` and the core-data
`SongRepository` read they bind, plus the tests in this repository
(`ProviderStreamResolverTest`, `SongReplayGainSourceTest`,
`ScrobblePointTrackerTest`, `QueueSourceSeamsTest`, `QueueSourcesTest`, and the
scrobble cases in `PlaybackControllerImplTest`). Evidence categories: own-source reading, in-repository test runs.

Confidence:
- [VERIFIED] `ProviderStreamResolver` maps a queue entry through
  `MediaProvider.streamUrl(externalId)`; an unknown provider, a null URL or a
  provider failure resolves to null and the queue machine advances to the next
  entry (resolver tests).
- [VERIFIED] `content://`/`file://` URLs mark `requiresNetwork = false`,
  `routedDirect = true` and transport `DIRECT_URI`; every other URL is
  `PROVIDER_HTTP` and network-bound (resolver tests).
- [VERIFIED] The song-level resume point (`songs.resumeMs`) seeds
  `ResolvedStream.startPositionMs` when the queue snapshot carries none: values
  ≤ 0 are dropped, values at or past the entry duration restart the track, a
  missing duration keeps the point, and radio stations always start at 0
  (resolver tests). A queue-snapshot position still overrides it
  (`QueuePlayerImpl.startCurrent` copies `startPositionMs` only when a pending
  position is armed for the entry).
- [VERIFIED] `SongReplayGainSource` maps `replayGainTrack`/`replayGainAlbum`
  into `ReplayGainValues`; a missing row yields null (no normalisation)
  (source tests).
- [VERIFIED] `songs.replayGainType` reads as `RgTagType` by ordinal; null and
  out-of-range ints read as UNKNOWN (source tests). The Phase 5 tag scanner
  must write the same convention.
- [VERIFIED] `QueueSources` composition returns the first non-empty source
  result in priority order and empty when every source is empty
  (`QueueSourcesTest`); the wired seams (`RadioQueueSource`,
  `AutoplayQueueSource`, `SearchPlayQueueSource`) are no-ops until their
  features land (seam test).
- [VERIFIED] The controller emits exactly one `ScrobblePoint` per played
  playback of an entry: threshold = 95% of the entry duration (default,
  crossing counts only while the engine reports playing), re-armed at every
  Started and non-terminal TrackChanged, and a natural finish counts as a full
  play even below the metadata threshold (tracker + controller tests). No
  history is written — play events, counters and outbox scrobbles stay with
  the Phase 6 play counter.
- [INFERRED] Radio resolution rides the provider's stream endpoint by id (the
  Subsonic family serves internet radio through `stream`); a provider whose
  radio stations only expose a remote direct URL needs a resolver extension
  when radio queueing lands.
- [UNKNOWN] On-device: resume-point behavior against real bookmark-synced
  values, and radio-stream transport behavior (live-server acceptance).

Date: 2026-11-07

Links: `stream-cache-identity.md`, `controller-and-events.md`,
`sink-processors.md`, `../storage/library-sync-ownership.md` (resume-point
column ownership), `../protocol/scrobble-timestamps.md` (D-8 submission policy)

---

## Resolver

[VERIFIED] `ProviderStreamResolver(MediaProviderLookup, SongRowLookup)` is the
production `StreamResolver`. `MediaProviderLookup` adapts
`ProviderClientRegistry.providerFor` (cached per provider row, rebuilt on
edit); `SongRowLookup` adapts the core-data `SongRepository` song-row read.

[VERIFIED] `ResolvedStream.cacheKey` stays the contract default
(`StreamKey(providerId, externalId)` — see `stream-cache-identity.md`);
transcode variants do not exist until Phase 8.

## Replay-gain source

[VERIFIED] `SongReplayGainSource` is the first cut of the RG source order: tag
values scanned into the local library. The tag scanner that populates the
columns lands with the Phase 5 local library work; server-provided RG values
are unused. `songs.replayGainType` stores `RgTagType.ordinal` (RG1=0, RG2=1,
EBU=2, UNKNOWN=3) — the read convention the writer must match.

## Queue sources

[VERIFIED] `RadioQueueSource`, `AutoplayQueueSource` and
`SearchPlayQueueSource` implement `QueueSource` with the interface's default
no-ops, wired into the controller in priority order. "Play from search" stays
a no-op because search plays are built by the UI and voice/assistant
search-play is not planned for 1.0.

## Scrobble points

[VERIFIED] `ScrobblePointTracker(bus, thresholdFraction = 0.95)` is fed by the
controller's event collector: Started / non-terminal TrackChanged re-arm,
Position crossing emits (only while `isPlaying`), Finished emits once when no
point was emitted during that playback. Seeks while paused count only once
playback resumes at the crossed position.

[UNKNOWN] Whether the 95% default matches the eventual Phase 6 setting — the
tracker takes the fraction as a constructor parameter for that change.
