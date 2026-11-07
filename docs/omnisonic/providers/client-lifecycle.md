# Client lifecycle and block failover — `client-lifecycle.md`

Purpose: Pin the per-provider client lifecycle (one registry-owned pair of OkHttp clients — API and MEDIA profile — plus one MediaProvider per provider id, with the API client injected into the provider) and the connection-block failover behavior: two-failure hysteresis, a five-minute primary re-probe interval, and preference persistence.

How derived: code inspection and test runs in this repository; the API/MEDIA profile split is documented in `../network/client-profiles.md`. Evidence categories: own-source reading, in-repository test runs.

Confidence:
- [VERIFIED] `ProviderClientRegistry` (:core-data) owns (MediaProvider, API client, MEDIA client) per providerId. Rebuild triggers: explicit `invalidate`/`invalidateAll`, a provider row edit (updatedAt change), or row deletion (registry returns null and clears the entry). Unknown api types resolve to null without caching. Persisting a learned block preference is explicitly NOT a rebuild trigger: `ProviderDao.setLastWorkingBlock` updates only `lastWorkingBlock`, never `updatedAt`, so the cache entry (and its clients) survives a failover (`ProviderWiringTest` in `:app`).
- [VERIFIED] The cached media client uses the MEDIA profile (no encoding injection, media read-timeout floor) with the connection's custom headers and timeouts. The API-profile client is built by the registry BEFORE the provider is created and injected into the factory (`MediaProviderFactory.create(connection, apiClient)`), so `SubsonicClient` sends every request through the exact instance `apiClientFor(providerId)` returns — one client and connection pool per provider across library, outbox, engine and image traffic (`ProviderWiringClientIdentityTest` pins the identity against the real `SubsonicProviderFactory`; the mTLS handshake test pins it for the mtls path). The provider is created only after both specs build, so a corrupt keystore no longer constructs a provider that is immediately discarded.
- [VERIFIED] `MediaProviderFactory.create` has no client-less overload: provider-api depends on the OkHttp type (not on core-network), and every factory must accept the registry-built client. Networkless providers (local) ignore it. There is no factory-side client construction left (`SubsonicProviderFactory.defaultClient` was removed with FIX-09 part 1).
- [VERIFIED] `SubsonicProviderFactory` accepts a `suspend` block-preference callback (`onPreferredBlockChange`). Production wiring installs it (FIX-09 part 2, 2026-11-06): `ProviderWiringModule` builds the factory with a callback onto `ProviderBlockPreferenceStore`, which writes through `ProviderDao.setLastWorkingBlock`, so a learned preference survives process restarts and is reloaded by `ProviderConfigMapper` as `ProviderConnection.lastWorkingBlock`. The failure to persist is logged and swallowed (the in-memory failover still completes the caller's request). A Hilt-level test (`ProviderWiringTest` in `:app`) drives the real graph — registry, production factory, real Room DAO — through a two-failure flip and asserts the persisted block, the unchanged `updatedAt`, the preserved cache entry and the shared client instance.
- [VERIFIED] `BlockFailover` requires `FLIP_FAILURE_THRESHOLD = 2` consecutive primary failures (transport errors or >=500 status) before flipping to the secondary; a single failure surfaces without flipping; 4xx and fatal errors are terminal without flip; a primary success resets the failure count.
- [VERIFIED] While the secondary is preferred, the primary is re-probed only after `DEFAULT_PREFER_PRIMARY_RETRY_INTERVAL_MS` (5 minutes, fake-clocked in tests): a successful probe reclaims the primary and reports `PRIMARY_BLOCK`; a failed probe re-arms the timer and keeps the secondary preferred. Before the interval elapses, a remembered secondary is used directly without touching the primary.
- [VERIFIED] The preference callback is `suspend`, so persistence can run inside the caller's coroutine without blocking.
- [VERIFIED] Production logging is one Hilt-provided `OmnisonicLogger` over `AndroidLogSink` (`LoggingWiringModule`); the registry and its default client factories reuse that injected logger instead of constructing `ConsoleLogSink` instances, so registry and HTTP diagnostics reach logcat in release builds (`ProviderWiringTest` pins the shared instance and a registry warning arriving in the log).
- [VERIFIED] Stream/cover/download URLs are built from the preferred block only (no failover on the URL builder); if the preferred block is unreachable, stream URLs must be re-resolved after the preference changes. Documented, deliberate limitation.
- [UNKNOWN] Behavior against a real dual-block (LAN/WAN) server over long periods — needs a live server; not run.

Date: 2026-11-07

Links: `../network/client-profiles.md`, `../playback/per-provider-media-clients.md`, `provider-connections.md`, `../decisions.md`

---

## Lifecycle

[VERIFIED] One `create()` per provider per registry lifetime: engine swaps, sync runs, and drains reuse the same MediaProvider instance and dispatcher/pool. Playback sources resolve clients through `httpClientFor(providerId)`.

[VERIFIED] The registry also exposes `apiClientFor(providerId)` — the API-profile client injected into the provider (same mTLS/header/timeout treatment, same instance) — and `apiClientForUrl(url)`, which matches scheme/host/port on both connection blocks. The app-wide Coil `ImageLoader` routes cover-image requests through `apiClientForUrl` (`ProviderRoutedCallFactory` in `:app`), so images ride the provider's headers, mTLS identity, and trust anchors; a URL matching no provider block falls back to the plain default client. A provider connection change (host, credentials, certificate, headers) must call `invalidate(providerId)` before the next image load, API call or playback, otherwise the old client and provider remain in the cached entry.

## Failover

[VERIFIED] Flip: primary fails twice in a row -> secondary preferred (callback fires once). Reclaim: after the 5-minute interval, a healthy primary probe resets the preference (callback fires). Hysteresis and probe timing are covered by `BlockFailoverTest` with an injected clock; preference persistence by `ProviderClientRegistryTest` (mechanism, test factory) and `ProviderWiringTest` (production Hilt graph, real DAO, unchanged `updatedAt`, preserved cache entry).
