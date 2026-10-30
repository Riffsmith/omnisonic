# Stream cache identity — `stream-cache-identity.md`

Purpose: Pin down the stable cache identity used for streamed audio and cover images so rotating per-request authentication parameters cannot create duplicate cache entries.

How derived: Code reading of `StreamResolver.kt`, `PlaybackCacheManager.kt`, `OmniPlayerImpl.kt`, and the Media3 `CacheDataSource` cache-key path, plus the T2 cache tests. The studied app is not a source.

Confidence:
- [VERIFIED] Media3 `CacheDataSource` resolves its cache key through `CacheKeyFactory`; the default factory uses `DataSpec.key` when present and otherwise the URI string.
- [VERIFIED] `PlaybackCacheManager.cacheFor` overrides that default with `DataSpec.key ?: caller cache key`.
- [VERIFIED] `ResolvedStream` carries a provider-stable `cacheKey`; `OmniPlayerImpl` sets both `MediaItem.customCacheKey` and the cache factory key from it.
- [INFERRED] Providers should derive the key from `StreamKey(providerId, externalId, maxBitRateKbps, format)` so transcode bitrate or format variants remain distinct while auth salts do not.
- [VERIFIED] The Coil `CoverKeyer` maps `CoverImageKey` to `cover:$providerId:$imageId:$size`.

Date: 2026-10-30

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

## Cover images

[VERIFIED] `CoverKeyer` keys cover images as `cover:<providerId>:<imageId>:<sizePx-or-original>`, independent of the signed request URL.
