# Library sync ownership and pruning — `library-sync-ownership.md`

Purpose: Pin the server/local column-ownership model, per-album transaction boundaries, resume-point merge rules, and full-sync pruning (tombstones) for the server-to-device library walk.

How derived: Prompt.md T10; decision (A) from the T10 decision brief (tombstone with the three rules, confirmed 2026-10-30); direct code inspection and test runs in this repository. The studied app is not a source.

Confidence:
- [VERIFIED] Each album (and each entity batch: artists, genres, radios, playlists, bookmarks, pruning, status) commits in its own short transaction; the network fetch (`getAlbum`) runs outside the transaction. A concurrent outbox write completes while a sync is parked waiting on the provider (test: `outbox enqueue completes while the sync waits on the network`).
- [VERIFIED] Column ownership is pinned by `SyncColumnOwnershipTest`: every column of SongEntity/AlbumEntity/ArtistEntity is classified KEY/SERVER/LOCAL; a new column fails the test until classified. Server-owned columns are written via DAO `updateServerFields` (INSERT OR IGNORE + UPDATE; no `ON CONFLICT DO UPDATE` per T5); locally-owned columns are never in the SET list.
- [VERIFIED] Locally-owned song state (playCount, lastPlayed, skipped, userRating, isFavorite, resumePointMs, resumeUpdatedAt, missingOnServer, replay gain, clips, isAudiobook) and album state (isCompilation, description, resumeFilePath, isAudiobook, discTitlesJson, playback state) survive a full sync unchanged.
- [VERIFIED] Resume merge rule: a server bookmark applies only when the song has no pending `resume_point` outbox row AND `bookmark.updatedAtMs` is non-null and greater than `songs.resumeUpdatedAt` (null resumeUpdatedAt counts as 0). Applying stores the server timestamp in `resumeUpdatedAt`; local resume writes (`setResumePoint`) stamp the local now. A bookmark without a timestamp never overwrites local state.
- [VERIFIED] Full-sync-only mark-and-sweep: songs unseen on the server are deleted only if playCount == 0 AND no outbox row exists for the externalId; otherwise they are tombstoned with `missingOnServer = true` (favorite status alone does NOT block deletion, per the confirmed rule 3). Albums are deleted when unseen and no song rows remain (tombstoned songs keep their album alive); artists are deleted when unseen and no song_artists/album_artists links remain; playlists are deleted outright when unseen. Diff sync never prunes.
- [VERIFIED] `MediaProvider.getAlbum(externalId): ProviderAlbumDetails` returns album + songs in one fetch; the syncer makes N album requests for N albums plus one album-list page request (asserted in tests). `browseDirectory` remains for directory browsing and the local provider.
- [VERIFIED] titleRoman/sortTitle are computed at sync for songs, albums, and artists via `core-model` TextNormalizer (ICU Any-Latin transliteration when available on Android, otherwise NFD + strip marks on the JVM), with `sortKey` lowercased.
- [VERIFIED] O(n^2) genre/radio/playlist lookups replaced with `(providerId, externalId)` DAO queries.
- [VERIFIED] `songs.resumeUpdatedAt` and `songs.missingOnServer` exist from schema v1 (pre-release). The single T16 v1->v2 migration preserves them and adds provider kinds/endpoints, Wi-Fi/mTLS columns, download ownership, queue snapshots, and per-play history; see `schema-v2.md`. No released database exists, and the migration is explicit (no destructive fallback).
- [VERIFIED] Provider rows now carry `kind` (`REMOTE_SERVER`/`DEVICE`/`VIRTUAL`); `DEVICE` and `VIRTUAL` load through `ProviderEndpoint.None`, while `REMOTE_SERVER` keeps the primary-block validation. `SongRepository.ensureSong` lets playback/favorites/playlists materialise a minimal song row without overwriting SERVER- or LOCAL-owned columns.
- [UNKNOWN] Behavior against a live server (real Navidrome) — needs a server; not run.

Date: 2026-11-02

Links: `schema-v2.md`, `../protocol/subsonic-auth.md`, `../../.agents` decision brief section 2 (tombstone rules), Prompt.md T10

---

## Ownership model

[VERIFIED] See `SyncColumnOwnershipTest` for the authoritative classification. In short: everything describing the server's copy (titles, paths, codec facts, counts, timestamps) is SERVER-owned; everything the app or user produces (play history, favorites, ratings, resume points, clips, audiobook flags, editorial text) is LOCAL-owned.

## Pruning rules (FULL sync only)

1. Songs not seen: DELETE when playCount == 0 and no outbox row exists for the externalId; otherwise keep and set `missingOnServer = true`.
2. Albums: deleted when not seen and no song rows remain. Tombstoned songs keep their album.
3. Artists: deleted when not seen and no link rows remain.
4. Playlists: deleted when not seen; orphaned entries cleaned.
5. Diff sync never prunes.

## Resume merge

[VERIFIED] Server bookmark wins only if newer than the local resume state and no pending local resume upload exists. This prevents a pending outbox row (newer local data) from being clobbered by an older server bookmark.
