# Scrobble timestamps and D-8 policy — `scrobble-timestamps.md`

Purpose: Pin how play events become backdated scrobble submissions: the D-8 capability policy (hint-gated with a per-provider override), per-play timestamps through the outbox, and the drainer's cold-start capability probe.

How derived: Prompt.md T11; decision (a) from the D-8 decision brief (hint-based default + `auto|on|off` per-provider override; unknown/absent hint stays OFF in `auto`, confirmed 2026-10-30); protocol reading of the base Subsonic API scrobble `time` parameter; code inspection and test runs in this repository.

Confidence:
- [VERIFIED] `ProviderCapabilities.supportsBackdatedScrobbles` no longer derives from unrelated OpenSubsonic extension names; it follows `backdateScrobbles` (`AUTO`/`ON`/`OFF`) with `AUTO` enabled only when `serverSoftwareHint` is a known honoring server (initial set: `navidrome`). The previous extension-based rule and its KDoc are removed.
- [VERIFIED] The override reaches the client via `providers.optionsJson.backdateScrobbles` (`"auto" | "on" | "off"`, default auto) → `ProviderConnection.backdateScrobbles` → `probe()`. Nothing writes these options yet (no profiles UI), so `auto` is OFF in practice until the UI sets hints; recorded as the interim gap from the decision brief.
- [VERIFIED] Each queued play carries its own timestamp: `OutboxQueue.enqueuePlayCountDelta(providerId, mediaType, externalId, playedAtMs, now)` appends to `playTimestamps` (both payload mirrors), merged rows keep every timestamp, capped at 100 with the newest kept. Payloads without the list decode gracefully (null list) and legacy `playCountDelta`+`lastPlayedAt` rows synthesize one-millisecond-apart times from `lastPlayedAt`.
- [VERIFIED] `MediaProvider.submitPlays(externalId, timestamps)` sends one scrobble submission per timestamp; the client forces strictly increasing `time` values (+1 ms on ties or regressions) and omits `time` entirely when the capability is off.
- [VERIFIED] `OutboxDrainer` probes a provider once per drainer instance before its first play-count task (network evidence: the drain's first requests are ping + extensions), so a cold app no longer drains offline plays without timestamps when the hint/override enables backdating. Probe failure is logged and does not fail the task.
- [VERIFIED] Partial acceptance keeps the remaining timestamps for the next drain (`store.progress`), preserving one row per item.
- [UNKNOWN] Real-server behavior of `time` on other server software — needs a live server; not run.

Date: 2026-10-31

Links: `subsonic-auth.md`, `../storage/library-sync-ownership.md`, Prompt.md T11, D-8 decision brief section 3

---

## Policy

[VERIFIED] `auto` (default): ON only for known-honoring servers (`navidrome`), OFF otherwise. `on`: always send `time`. `off`: never send `time`.

## Timestamp flow

[VERIFIED] One play = one enqueue = one timestamp appended. The drainer submits the accumulated list; the server-visible times are strictly increasing, 1 ms apart on ties, matching the per-play history requirements.

## Interim gap (from the decision brief, not blocking)

[VERIFIED] No UI writes `serverSoftwareHint` or `backdateScrobbles` today. The profiles screen should set `serverSoftwareHint` (defaulting new providers to `navidrome`) when it is built.
