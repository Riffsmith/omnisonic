# Client lifecycle and block failover — `client-lifecycle.md`

Purpose: Pin the per-provider client lifecycle (one cached MediaProvider plus one media-profile OkHttpClient per provider id) and the connection-block failover behavior: two-failure hysteresis, a five-minute primary re-probe interval, and preference persistence.

How derived: code inspection and test runs in this repository; the API/MEDIA profile split is documented in `../network/client-profiles.md`. The studied app is not a source.

Confidence:
- [VERIFIED] `ProviderClientRegistry` (:core-data) caches (MediaProvider, OkHttpClient) per providerId. Rebuild triggers: explicit `invalidate`/`invalidateAll`, a provider row edit (updatedAt change), or row deletion (registry returns null and clears the entry). Unknown api types resolve to null without caching.
- [VERIFIED] The cached media client uses the MEDIA profile (no encoding injection, media read-timeout floor) with the connection's custom headers and timeouts; the MediaProvider itself carries its own API-profile client, built once per provider because the factory runs only on cache misses.
- [VERIFIED] `SubsonicProviderFactory` wires the client's block-preference callback to `ProviderDao.setLastWorkingBlock(providerId, block)`, so the learned preference survives process restarts (previously the callback was never installed and `lastWorkingBlock` was never written).
- [VERIFIED] `BlockFailover` requires `FLIP_FAILURE_THRESHOLD = 2` consecutive primary failures (transport errors or >=500 status) before flipping to the secondary; a single failure surfaces without flipping; 4xx and fatal errors are terminal without flip; a primary success resets the failure count.
- [VERIFIED] While the secondary is preferred, the primary is re-probed only after `DEFAULT_PREFER_PRIMARY_RETRY_INTERVAL_MS` (5 minutes, fake-clocked in tests): a successful probe reclaims the primary and reports `PRIMARY_BLOCK`; a failed probe re-arms the timer and keeps the secondary preferred. Before the interval elapses, a remembered secondary is used directly without touching the primary.
- [VERIFIED] The preference callback is `suspend`, so persistence can run inside the caller's coroutine without blocking.
- [VERIFIED] Stream/cover/download URLs are built from the preferred block only (no failover on the URL builder); if the preferred block is unreachable, stream URLs must be re-resolved after the preference changes. Documented, deliberate limitation.
- [UNKNOWN] Behavior against a real dual-block (LAN/WAN) server over long periods — needs a live server; not run.

Date: 2026-10-31

Links: `../network/client-profiles.md`, `../playback/per-provider-media-clients.md`, `provider-connections.md`, `../decisions.md`

---

## Lifecycle

[VERIFIED] One `create()` per provider per registry lifetime: engine swaps, sync runs, and drains reuse the same MediaProvider instance and dispatcher/pool. Playback sources resolve clients through `httpClientFor(providerId)`.

[VERIFIED] The registry also exposes `apiClientFor(providerId)` (API-profile client, same mTLS/header treatment) and `apiClientForUrl(url)`, which matches scheme/host/port on both connection blocks. The app-wide Coil `ImageLoader` routes cover-image requests through `apiClientForUrl` (`ProviderRoutedCallFactory` in `:app`), so images ride the provider's headers, mTLS identity, and trust anchors; a URL matching no provider block falls back to the plain default client. A provider connection change (host, credentials, certificate, headers) must call `invalidate(providerId)` before the next image load or playback, otherwise the old client remains in the cached factory.

## Failover

[VERIFIED] Flip: primary fails twice in a row -> secondary preferred (callback fires once). Reclaim: after the 5-minute interval, a healthy primary probe resets the preference (callback fires). Hysteresis and probe timing are covered by `BlockFailoverTest` with an injected clock; preference persistence by `ProviderClientRegistryTest` through the real Room DAO.
