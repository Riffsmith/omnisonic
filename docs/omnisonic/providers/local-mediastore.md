# Local MediaStore provider — `local-mediastore.md`

Purpose: Pin how the device-library provider pages and filters real `MediaStore` queries on Android 11+, keeps albums with identical titles separate, and derives song metadata without reading `DATA`.

How derived: The Android `ContentResolver` reference, AOSP `MediaProvider` and `SQLiteQueryBuilder` source at the Android 11 release tag, and the provider-local fake-`ContentProvider` tests. The studied app is not a source.

Confidence:
- [VERIFIED] Android 11 `MediaProvider` only lifts `LIMIT` out of `sortOrder` for callers targeting below API 30; targeting API 30+ leaves it in place, where strict grammar rejects the `LIMIT` token.
- [VERIFIED] Paged local queries pass `QUERY_ARG_LIMIT`, `QUERY_ARG_OFFSET`, `QUERY_ARG_SQL_SELECTION`, and `QUERY_ARG_SORT_COLUMNS` in a query `Bundle`.
- [VERIFIED] Album and artist directory children are selected by `ALBUM_ID` and `ARTIST_ID`, not by display names.
- [VERIFIED] Codec comes from the `DISPLAY_NAME` extension, falling back to the `MIME_TYPE` subtype; local `filePath` is no longer populated from `DATA`.
- [INFERRED] `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"` is required for Android 10-12 while Android 13+ uses `READ_MEDIA_AUDIO`.

Date: 2026-10-30

Links: `../00-template.md`

---

## Android 11 LIMIT behavior

The official `ContentResolver` reference defines the bundled paging keys:

```text
QUERY_ARG_LIMIT       Specifies the max number of rows to include in a Cursor.
QUERY_ARG_OFFSET      Specifies the offset row index within a Cursor.
QUERY_ARG_SORT_COLUMNS
                      Specifies the list of columns (stored as a String[])
                      against which to sort results.
```

AOSP tag `android-11.0.0_r48`, file `src/com/android/providers/media/MediaProvider.java`:

```java
if (targetSdkVersion < Build.VERSION_CODES.R) {
    // Some apps are abusing "ORDER BY" clauses to inject "LIMIT"
    // clauses; gracefully lift them out.
    DatabaseUtils.recoverAbusiveSortOrder(queryArgs);
    ...
}
...
qb.setStrictGrammar(true);
```

AOSP tag `android-11.0.0_r48`, file `src/com/android/providers/media/util/SQLiteQueryBuilder.java`:

```java
case "LIMIT":
    isAllowedKeyword = false;
    break;
...
throw new IllegalArgumentException("Invalid token " + token);
```

[VERIFIED] The recovery path is gated on `targetSdkVersion < Build.VERSION_CODES.R`. An app targeting API 30 or later therefore does not get ` LIMIT ` moved out of `sortOrder`; strict grammar then rejects `LIMIT` with `Invalid token LIMIT`.

[VERIFIED] Omnisonic targets API 36. The provider no longer puts `LIMIT` or `OFFSET` into any sort-order string.

Sources:
- `https://developer.android.com/reference/android/content/ContentResolver`
- `https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/tags/android-11.0.0_r48/src/com/android/providers/media/MediaProvider.java`
- `https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/tags/android-11.0.0_r48/src/com/android/providers/media/util/SQLiteQueryBuilder.java`

## Query shape

[VERIFIED] Every provider-local read goes through one `query` helper that builds a `Bundle` and calls the four-argument `ContentResolver.query`. On API 30+ paged calls put the row count and offset in `QUERY_ARG_LIMIT` and `QUERY_ARG_OFFSET`; sort columns and direction stay separate.

[VERIFIED] Android 10 `MediaProvider` reads only the selection and sort order from the query bundle and ignores `QUERY_ARG_LIMIT` and `QUERY_ARG_OFFSET`. The local provider therefore applies the page window in a cursor wrapper on API 29 so pagination still matches the requested page.

[VERIFIED] Multi-column album sorts carry per-column `COLLATE NOCASE` and `DESC` tokens inside `QUERY_ARG_SORT_COLUMNS`. `MediaProvider` appends the shared collation and direction to the final column only, so keeping those modifiers inline preserves the intended order for every column.

[VERIFIED] `listAlbums`, `searchAlbums`, `searchSongs`, and `searchArtists` all supply both limit and offset. The fake provider throws when it sees `LIMIT` in a sort-order string, so a regression fails the suite instead of being silently parsed.

## Identity and song metadata

[VERIFIED] `browseDirectory` checks whether the id exists as an artist, then selects albums by `MediaStore.Audio.Albums.ARTIST_ID`. Otherwise it checks the album id and selects songs by `MediaStore.Audio.Media.ALBUM_ID`. Two albums named "Greatest Hits" therefore return different song lists.

[VERIFIED] Album rows now expose `artistExternalId` from `ARTIST_ID`; song rows expose `albumExternalId` and `artistExternalId` from `ALBUM_ID` and `ARTIST_ID`.

[VERIFIED] `DATA` is absent from the media projection. `filePath` is null for local songs. Codec prefers the `DISPLAY_NAME` suffix, then the `MIME_TYPE` subtype, mapping `audio/mpeg` to `mp3` and treating `application/octet-stream` as unknown.

## Permission

[VERIFIED] `app/src/main/AndroidManifest.xml` declares `READ_EXTERNAL_STORAGE` with `android:maxSdkVersion="32"` alongside `READ_MEDIA_AUDIO`.

[INFERRED] Android 10 through 12 grant library access through `READ_EXTERNAL_STORAGE`; Android 13 and later grant it through `READ_MEDIA_AUDIO`. The max-SDK cap keeps the legacy permission from being requested on newer releases.
