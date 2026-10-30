# Per-provider media clients — `per-provider-media-clients.md`

Purpose: Pin down how streamed audio reaches the provider's MEDIA-profile OkHttp client so custom headers, mTLS identity and media timeouts apply to Media3 data-source reads.

How derived: Code reading of `MediaDataSourceFactoryProvider.kt`, `PlaybackCacheManager.kt`, `OmniPlayerImpl.kt`, `HttpClientFactory.kt`, plus the T4 MockWebServer and mTLS playback tests. The studied app is not a source.

Confidence:
- [VERIFIED] `MediaDataSourceFactoryProvider.factoryFor(providerId)` returns a cached `OkHttpDataSource.Factory` backed by the resolver's client for that provider.
- [VERIFIED] `PlaybackCacheManager` resolves its upstream factory per `ResolvedStream`, so each provider id gets its own OkHttp data source inside the cache wrapper.
- [VERIFIED] A stream request with `X-Test: 1` from a provider MEDIA client reaches MockWebServer through `OmniPlayerImpl`; the same request without that header is rejected by the server and surfaces as a playback error.
- [VERIFIED] A PKCS#12 client identity and an extra trusted server certificate complete a mutual-TLS handshake through `OkHttpDataSource`.
- [INFERRED] Callers must pass the provider's MEDIA-profile client to `MediaDataSourceFactoryProvider`; an API-profile client would keep API compression and timeout behavior on audio reads.
- [UNKNOWN] Production dependency-injection wiring for provider ids and client factories is not present yet; it belongs to the provider/client lifecycle work.

Date: 2026-10-30

Links: `../network/client-profiles.md`, `stream-cache-identity.md`

---

## Data path

```text
ResolvedStream(providerId)
  -> PlaybackCacheManager.upstreamFactory(stream)
  -> MediaDataSourceFactoryProvider.factoryFor(providerId)
  -> OkHttpDataSource.Factory(providerMediaClient)
  -> DefaultDataSource / CacheDataSource
  -> Media3
```

[VERIFIED] The cache manager no longer holds one global upstream factory. Its public operations (`cacheFor`, `directFor`, `factoryFor`) accept the `ResolvedStream` they are building for.

[VERIFIED] `OkHttpDataSource` is supplied by `androidx.media3:media3-datasource-okhttp` at the same version as the rest of Media3.

## Caching and lifecycle

[VERIFIED] Factory instances are cached per provider id, so repeated tracks from one provider share the same OkHttp pool and data-source configuration.

[INFERRED] A provider connection change (host, credentials, certificate, headers) must call `invalidate(providerId)` before the next play; otherwise the old client remains in the cached factory.
