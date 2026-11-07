# Stream cache identity — `stream-cache-identity.md`

Purpose: Pin down the stable cache identity used for streamed audio and cover images so rotating per-request authentication parameters cannot create duplicate cache entries.

How derived: Code reading of `StreamResolver.kt`, `PlaybackCacheManager.kt`, `OmniPlayerImpl.kt`, `CoverImageFetcher.kt`, and the Media3 `CacheDataSource` cache-key path, plus the stream-cache and cover-loader tests in this repository. Evidence categories: own-source reading, published Media3 source reading, in-repository test runs.

Confidence:
- [VERIFIED] Media3 `CacheDataSource` resolves its cache key through `CacheKeyFactory`; the default factory uses `DataSpec.key` when present and otherwise the URI string.
- [VERIFIED] `PlaybackCacheManager.cacheFor` overrides that default with `DataSpec.key ?: caller cache key`.
- [VERIFIED] `ResolvedStream` carries a provider-stable `cacheKey`; `OmniPlayerImpl` sets both `MediaItem.customCacheKey` and the cache factory key from it.
- [INFERRED] Providers should derive the key from `StreamKey(providerId, externalId, maxBitRateKbps, format)` so transcode bitrate or format variants remain distinct while auth salts do not.
- [VERIFIED] The Coil `CoverKeyer` maps `CoverImageKey` to `cover:$providerId:$imageId:$size`.
- [VERIFIED] `CoverImageFetcher` resolves the provider URL at fetch time and pins the delegate network fetcher's `diskCacheKey` to `CoverImageKey.cacheKey`, so two salted URLs for one cover produce one memory/disk entry (`CoverImageLoaderTest.salted urls for one cover share a single cache entry`).
- [VERIFIED] `ResolvedStream.expiresAtMs == null` never expires; a non-null value becomes stale 60 seconds before the deadline.
- [VERIFIED] `StreamTransport` selects the upstream path: provider HTTP, direct URI, or a registered custom `DataSource.Factory`; the cache key is unchanged.

Date: 2026-11-07

Links: `../protocol/subsonic-auth.md`

---

## Stream key

`StreamKey` formats as:

```text
stream:<providerId>:<externalId>:<maxBitRateKbps-or-auto>:<format-or-auto>
```

[VERIFIED] Two `ResolvedStream` values built from the same queue entry but different salted URLs have the same `cacheKey`.

[INFERRED] A resolver that offers transcoded variants must pass the selected bitrate and format through `StreamKey`; the default `ResolvedStream.cacheKey` only guarantees identity for one variant per entry.

## Cache operations

[VERIFIED] `cachedBytesFor`, `isCached`, `setPerUriCacheFirst`, and `clearPerUriOverride` accept the stable cache key rather than a URL string.

[VERIFIED] A write through the first salted URL is read through a second salted URL with no additional MockWebServer request when both use the same stream key.

## Stream freshness

[VERIFIED] `OmniPlayerImpl` re-resolves a stale stream before starting it, when an engine swap restarts playback, when resuming after a pause of at least 30 seconds, and when a stale prepared next item is promoted. The replacement stream keeps the queue entry and cache key, so a refreshed signed URL reads the same cache entry.

[VERIFIED] One `401`, `403`, or `410` response per stream triggers a single resolver call and a restart at the captured position; a second failure surfaces one error to the listener and is not retried.

[VERIFIED] A stream without `expiresAtMs` is never re-resolved by the player.

## Transport selection

[VERIFIED] `ResolvedStream.transport` defaults to `PROVIDER_HTTP`, which keeps the existing per-provider OkHttp path. `DIRECT_URI` (and the legacy `content://`/`file://` route) uses `DefaultDataSource` without a provider base factory. `CUSTOM` looks up `customDataSourceFactory` in the `DataSourceRegistry` owned by `PlaybackCacheManager`; an unregistered id surfaces a playback error instead of silently falling back.

[VERIFIED] Cache wrapping still happens before transport selection, so a custom or direct source can opt into caching through the same `cacheKey` and per-URI override.

## Cover images

[VERIFIED] `CoverKeyer` keys cover images as `cover:<providerId>:<imageId>:<sizePx-or-original>`, independent of the signed request URL.

[VERIFIED] The app-wide `ImageLoader` registers `CoverKeyer` and `CoverImageFetcher.Factory`. `CoverImageFetcher` resolves a `CoverImageKey` through the provider registry (`MediaProvider.coverArtUrl(imageId, sizePx)`) at fetch time, so a provider's rotating auth salt changes the URL without changing the cache identity. The delegate network fetcher runs with `Options.diskCacheKey = key.cacheKey`; Coil's `NetworkFetcher` uses that value for its disk snapshot/write key instead of the URL.

[VERIFIED] `CoverImageLoaderTest` serves one PNG and executes two requests for the same `CoverImageKey` whose resolver returns different salted URLs with the memory cache disabled: both results are successes, the resolver is called twice, the server sees one request, and both results report the same `diskCacheKey` (`cover:<providerId>:<imageId>:<sizePx>`).
