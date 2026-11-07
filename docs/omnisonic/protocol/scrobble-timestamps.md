# Scrobble timestamps and D-8 policy — `scrobble-timestamps.md`

Purpose: Pin how play events become backdated scrobble submissions: the D-8 capability policy (hint-gated with a per-provider override), per-play timestamps through the outbox, and the drainer's cold-start capability probe.

How derived: decision (hint-gated backdating with a per-provider override, confirmed 2026-10-30 — see `../decisions.md`); protocol reading of the base Subsonic API scrobble `time` parameter; OpenSubsonic `subsonic-response` spec reading (opensubsonic.netlify.app, `type`/`serverVersion`/`openSubsonic` fields); code inspection and test runs in this repository.

Confidence:
- [VERIFIED] `ProviderCapabilities.supportsBackdatedScrobbles` no longer derives from unrelated OpenSubsonic extension names; it follows `backdateScrobbles` (`AUTO`/`ON`/`OFF`) with `AUTO` enabled only when `serverSoftwareHint` is a known honoring server (initial set: `navidrome`). The previous extension-based rule and its KDoc are removed.
- [VERIFIED] `SubsonicClient` initializes its capabilities from the connection configuration in the constructor, so a configured `serverSoftwareHint` and the explicit ON/OFF override apply before any probe round-trip (no first-drain probe dependency). The override reaches the client via `providers.optionsJson.backdateScrobbles` (`"auto" | "on" | "off"`, default auto) → `ProviderConnection.backdateScrobbles`. Nothing writes these options yet (no profiles UI).
- [VERIFIED] `probe()` reads the OpenSubsonic response fields `type`, `serverVersion` and `openSubsonic` (added to `SubsonicEnvelope`; absent on plain Subsonic servers). When no hint is configured, the ping `type` becomes the hint, so `auto` turns on for a server that reports `navidrome` without any UI configuration. A configured hint always wins over the ping type.
- [UNKNOWN] Live-server verification of the ping field names/values: no live Navidrome was reachable this session (`OMNISONIC_NAVIDROME_*` unset). The field names are pinned by the OpenSubsonic `subsonic-response` spec (spec examples show `"type": "AwesomeServerName"`; Navidrome is documented to report its type); a 2026-10-29 live probe against a real Navidrome recorded `serverSoftwareHint=null (unmapped ping field)` pre-fix, consistent with the field being present but then-unhandled. Re-verify the exact `type` value (expected `navidrome`) when a live server is available.
- [VERIFIED] Each queued play carries its own timestamp: `OutboxQueue.enqueuePlayCountDelta(providerId, mediaType, externalId, playedAtMs, now)` appends to `playTimestamps`, and merged rows keep every timestamp (no cap). The queue writes `playCountDelta` equal to `playTimestamps.size`; a legacy `playCountDelta`+`lastPlayedAt` row without the list synthesizes one-millisecond-apart times from `lastPlayedAt` before the new play is appended. Payloads without the list still decode gracefully (null list).
- [VERIFIED] `MediaProvider.submitPlays(externalId, timestamps)` sends one scrobble submission per timestamp; the client forces strictly increasing `time` values (+1 ms on ties or regressions) and omits `time` entirely when the capability is off. Submission stops at the first rejected timestamp and the result is the length of the accepted prefix, so later timestamps are never sent ahead of a failed one.
- [VERIFIED] `OutboxDrainer` probes a provider before its first play-count task (network evidence: the drain's first requests are ping + extensions), so a cold app no longer drains offline plays without timestamps when the hint/override enables backdating. Probe failure is logged and does not fail the task; only a successful probe marks the provider done, so a failed probe is retried on the next drain instead of disabling capability discovery for the drainer's lifetime.
- [VERIFIED] The drainer submits the timestamp list in chronological chunks of 100, recording progress after each fully accepted chunk; 250 queued plays therefore reach the server in three batches (100/100/50) without losing or duplicating a timestamp.
- [VERIFIED] Partial acceptance keeps the remaining timestamps for the next drain (`store.progress`), preserving one row per item. A rejected timestamp ends its chunk; the remaining list starts at that timestamp, so a mid-chunk rejection retries it and every later one rather than dropping or duplicating them.
- [UNKNOWN] Real-server behavior of `time` on other server software — needs a live server; not run.
- [UNKNOWN] Timestamps that share a millisecond across a chunk boundary reset the client's per-call `+1 ms` coercion, so effective times can repeat across chunks; same-millisecond retries store raw timestamps. Not exercised against a live server.

Date: 2026-11-06

Links: `subsonic-auth.md`, `../storage/library-sync-ownership.md`, `../decisions.md` (backdated-scrobble policy).

---

## Policy

[VERIFIED] `auto` (default): ON only for known-honoring servers (`navidrome`), OFF otherwise. `on`: always send `time`. `off`: never send `time`.

[VERIFIED] The hint comes from the connection configuration when set; otherwise a successful probe adopts the OpenSubsonic ping `type` as the hint (Navidrome reports `navidrome`). Explicit ON/OFF ignore the hint entirely.

## Timestamp flow

[VERIFIED] One play = one enqueue = one timestamp appended. The drainer submits the accumulated list; the server-visible times are strictly increasing, 1 ms apart on ties, matching the per-play history requirements.

## Interim gap (known, not blocking)

[VERIFIED] No UI writes `serverSoftwareHint` or `backdateScrobbles` today. The ping-`type` fallback makes `auto` work out of the box for servers that report their type (Navidrome), so the profiles screen only needs to set `serverSoftwareHint` (defaulting new providers to `navidrome`) for servers that do not report it, and to expose the ON/OFF override.
