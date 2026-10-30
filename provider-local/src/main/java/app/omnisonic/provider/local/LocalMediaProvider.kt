package app.omnisonic.provider.local

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import app.omnisonic.provider.api.AlbumListType
import app.omnisonic.provider.api.MediaProvider
import app.omnisonic.provider.api.MediaProviderFactory
import app.omnisonic.provider.api.ProviderAlbum
import app.omnisonic.provider.api.ProviderAlbumRequest
import app.omnisonic.provider.api.ProviderArtist
import app.omnisonic.provider.api.ProviderBookmark
import app.omnisonic.provider.api.ProviderCapabilities
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderDirectory
import app.omnisonic.provider.api.ProviderGenre
import app.omnisonic.provider.api.ProviderMediaType
import app.omnisonic.provider.api.ProviderPlaylist
import app.omnisonic.provider.api.ProviderPlaylistSummary
import app.omnisonic.provider.api.ProviderRadio
import app.omnisonic.provider.api.ProviderSong
import java.text.Collator

/**
 * Device-library provider backed by MediaStore audio rows. Local files carry no
 * server state, so user-data operations are no-ops and plays report as fully
 * accepted so the outbox drains them without retries.
 */
class LocalMediaProvider(private val contentResolver: ContentResolver) : MediaProvider {

    override suspend fun probe(): ProviderCapabilities = ProviderCapabilities()

    override suspend fun listArtists(): List<ProviderArtist> =
        query(
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
            ARTIST_PROJECTION,
            sortColumns = arrayOf(MediaStore.Audio.Artists.ARTIST),
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ProviderArtist(
                            externalId = cursor.string(MediaStore.Audio.Artists._ID) ?: continue,
                            title = cursor.string(MediaStore.Audio.Artists.ARTIST).orEmpty(),
                            albumCount = cursor.int(MediaStore.Audio.Artists.NUMBER_OF_ALBUMS),
                            songCount = cursor.int(MediaStore.Audio.Artists.NUMBER_OF_TRACKS),
                        ),
                    )
                }
            }
        }.orEmpty()

    override suspend fun browseDirectory(externalId: String): ProviderDirectory {
        if (artistExists(externalId)) {
            return ProviderDirectory(
                externalId = externalId,
                childAlbums = albumsBy(MediaStore.Audio.Albums.ARTIST_ID, externalId),
            )
        }
        return ProviderDirectory(
            externalId = externalId,
            childSongs = if (albumExists(externalId)) {
                songsBy(MediaStore.Audio.Media.ALBUM_ID, externalId)
            } else {
                emptyList()
            },
        )
    }

    override suspend fun albumDetails(albumExternalId: String): ProviderAlbum? =
        query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            ALBUM_PROJECTION,
            selection = "${MediaStore.Audio.Albums._ID} = ?",
            selectionArgs = arrayOf(albumExternalId),
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.toAlbum() else null }

    override suspend fun songDetails(externalId: String): ProviderSong? =
        query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MEDIA_PROJECTION,
            selection = "${MediaStore.Audio.Media._ID} = ?",
            selectionArgs = arrayOf(externalId),
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.toSong() else null }

    override suspend fun listAlbums(request: ProviderAlbumRequest): List<ProviderAlbum> {
        val (sortColumns, sortDirection) = albumSort(request.type)
        return query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            ALBUM_PROJECTION,
            sortColumns = sortColumns,
            sortDirection = sortDirection,
            limit = request.size,
            offset = request.offset,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toAlbum())
                }
            }
        }.orEmpty()
    }

    override suspend fun listGenres(): List<ProviderGenre> =
        query(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
            sortColumns = arrayOf(MediaStore.Audio.Genres.NAME),
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.string(MediaStore.Audio.Genres._ID) ?: continue
                    add(ProviderGenre(externalId = id, songCount = null, albumCount = null))
                }
            }
        }.orEmpty()

    override suspend fun listRadios(): List<ProviderRadio> = emptyList()

    override suspend fun listPlaylists(): List<ProviderPlaylistSummary> = emptyList()

    override suspend fun getPlaylist(playlistExternalId: String): ProviderPlaylist? = null

    override suspend fun listBookmarks(): List<ProviderBookmark> = emptyList()

    override suspend fun searchArtists(query: String, offset: Int, count: Int): List<ProviderArtist> =
        query(
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
            ARTIST_PROJECTION,
            selection = "${MediaStore.Audio.Artists.ARTIST} LIKE ?",
            selectionArgs = arrayOf("%${query.trim()}%"),
            sortColumns = arrayOf(MediaStore.Audio.Artists.ARTIST),
            limit = count,
            offset = offset,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toArtist())
                }
            }
        }.orEmpty()

    override suspend fun searchAlbums(query: String, offset: Int, count: Int): List<ProviderAlbum> =
        query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            ALBUM_PROJECTION,
            selection = "${MediaStore.Audio.Albums.ALBUM} LIKE ?",
            selectionArgs = arrayOf("%${query.trim()}%"),
            sortColumns = arrayOf(MediaStore.Audio.Albums.ALBUM),
            limit = count,
            offset = offset,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toAlbum())
                }
            }
        }.orEmpty()

    override suspend fun searchSongs(query: String, offset: Int, count: Int): List<ProviderSong> =
        query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MEDIA_PROJECTION,
            selection = MUSIC_SELECTION + " AND (${MediaStore.Audio.Media.TITLE} LIKE ?)",
            selectionArgs = arrayOf("%${query.trim()}%"),
            sortColumns = arrayOf(MediaStore.Audio.Media.TITLE),
            limit = count,
            offset = offset,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toSong())
                }
            }
        }.orEmpty()

    override fun streamUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? =
        mediaUri(externalId)?.toString()

    override fun coverArtUrl(imageId: String?, sizePx: Int?): String? = null

    override fun downloadUrl(externalId: String, maxBitRateKbps: Int?, format: String?): String? = null

    override suspend fun submitPlays(externalId: String, playCount: Int, lastPlayedAtMs: Long?): Int = playCount

    override suspend fun sendNowPlaying(externalId: String, playedMs: Long): Boolean = true

    override suspend fun setFavorite(mediaType: ProviderMediaType, externalId: String, favorite: Boolean): Boolean = true

    override suspend fun setUserRating(mediaType: ProviderMediaType, externalId: String, rating: Int): Boolean = true

    override suspend fun saveBookmark(externalId: String, positionMs: Long): Boolean = true

    override suspend fun deleteBookmark(externalId: String): Boolean = true

    override suspend fun similarSongs(externalId: String, count: Int): List<ProviderSong> = emptyList()

    private fun mediaUri(externalId: String): Uri? =
        externalId.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }

    private suspend fun artistExists(externalId: String): Boolean =
        query(
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Artists._ID),
            selection = "${MediaStore.Audio.Artists._ID} = ?",
            selectionArgs = arrayOf(externalId),
        )?.use { it.moveToFirst() } == true

    private suspend fun albumExists(externalId: String): Boolean =
        query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Albums._ID),
            selection = "${MediaStore.Audio.Albums._ID} = ?",
            selectionArgs = arrayOf(externalId),
        )?.use { it.moveToFirst() } == true

    private suspend fun albumsBy(column: String, value: String): List<ProviderAlbum> =
        query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            ALBUM_PROJECTION,
            selection = "$column = ?",
            selectionArgs = arrayOf(value),
            sortColumns = arrayOf(MediaStore.Audio.Albums.FIRST_YEAR),
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toAlbum())
                }
            }
        }.orEmpty()

    private suspend fun songsBy(column: String, value: String): List<ProviderSong> =
        query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MEDIA_PROJECTION,
            selection = "$MUSIC_SELECTION AND ($column = ?)",
            selectionArgs = arrayOf(value),
            sortColumns = arrayOf(MediaStore.Audio.Media.TRACK),
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toSong())
                }
            }
        }.orEmpty()

    private fun albumSort(type: AlbumListType): Pair<Array<String>, Int> = when (type) {
        AlbumListType.NEWEST -> arrayOf(
            "${MediaStore.Audio.Albums.LAST_YEAR} DESC",
            MediaStore.Audio.Albums._ID,
        ) to ContentResolver.QUERY_SORT_DIRECTION_DESCENDING

        AlbumListType.ALPHABETICAL_BY_NAME -> arrayOf(
            "${MediaStore.Audio.Albums.ALBUM} COLLATE NOCASE",
            MediaStore.Audio.Albums._ID,
        ) to ContentResolver.QUERY_SORT_DIRECTION_ASCENDING

        AlbumListType.ALPHABETICAL_BY_ARTIST -> arrayOf(
            "${MediaStore.Audio.Albums.ARTIST} COLLATE NOCASE",
            MediaStore.Audio.Albums.ALBUM,
        ) to ContentResolver.QUERY_SORT_DIRECTION_ASCENDING

        AlbumListType.BY_GENRE, AlbumListType.RANDOM -> arrayOf(
            MediaStore.Audio.Albums._ID,
        ) to ContentResolver.QUERY_SORT_DIRECTION_ASCENDING
    }

    private fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortColumns: Array<String>? = null,
        sortDirection: Int = ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
        limit: Int? = null,
        offset: Int? = null,
    ): Cursor? {
        val args = Bundle()
        if (selection != null) {
            args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (selectionArgs != null) {
                args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            }
        }
        if (!sortColumns.isNullOrEmpty()) {
            args.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, sortColumns)
            args.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, sortDirection)
            args.putInt(ContentResolver.QUERY_ARG_SORT_COLLATION, Collator.PRIMARY)
        }
        if (limit != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            args.putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            if (offset != null) {
                args.putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
        }
        val cursor = contentResolver.query(uri, projection, args, null)
        return if (limit != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cursor?.let { PagedCursor(it, offset ?: 0, limit) }
        } else {
            cursor
        }
    }

    private fun Cursor.toArtist(): ProviderArtist = ProviderArtist(
        externalId = string(MediaStore.Audio.Artists._ID).orEmpty(),
        title = string(MediaStore.Audio.Artists.ARTIST).orEmpty(),
        albumCount = int(MediaStore.Audio.Artists.NUMBER_OF_ALBUMS),
        songCount = int(MediaStore.Audio.Artists.NUMBER_OF_TRACKS),
    )

    private fun Cursor.toAlbum(): ProviderAlbum = ProviderAlbum(
        externalId = string(MediaStore.Audio.Albums._ID).orEmpty(),
        title = string(MediaStore.Audio.Albums.ALBUM).orEmpty(),
        artistExternalId = string(MediaStore.Audio.Albums.ARTIST_ID),
        artistName = string(MediaStore.Audio.Albums.ARTIST),
        year = int(MediaStore.Audio.Albums.FIRST_YEAR),
        songCount = int(MediaStore.Audio.Albums.NUMBER_OF_SONGS),
    )

    private fun Cursor.toSong(): ProviderSong {
        val track = int(MediaStore.Audio.Media.TRACK) ?: 0
        return ProviderSong(
            externalId = string(MediaStore.Audio.Media._ID).orEmpty(),
            title = string(MediaStore.Audio.Media.TITLE).orEmpty(),
            artistExternalId = string(MediaStore.Audio.Media.ARTIST_ID),
            artistName = string(MediaStore.Audio.Media.ARTIST),
            albumExternalId = string(MediaStore.Audio.Media.ALBUM_ID),
            trackNo = if (track > 0) track % TRACK_GRANULARITY else null,
            discNo = if (track >= TRACK_GRANULARITY) track / TRACK_GRANULARITY else null,
            year = int(MediaStore.Audio.Media.YEAR),
            imagePath = null,
            durationMs = int(MediaStore.Audio.Media.DURATION)?.toLong(),
            filePath = null,
            codec = codec(),
        )
    }

    private fun Cursor.codec(): String? {
        val extension = string(MediaStore.Audio.Media.DISPLAY_NAME)
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        if (extension != null) return extension

        val subtype = string(MediaStore.Audio.Media.MIME_TYPE)
            ?.substringAfter('/', "")
            ?.substringBefore(';')
            ?.lowercase()
        return when (subtype) {
            null, "", "octet-stream" -> null
            "mpeg" -> "mp3"
            else -> subtype
        }
    }

    private fun Cursor.string(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.int(columnName: String): Int? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getInt(index) else null
    }

    companion object {
        private const val MUSIC_SELECTION = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        private const val TRACK_GRANULARITY = 1000

        private val ARTIST_PROJECTION = arrayOf(
            MediaStore.Audio.Artists._ID,
            MediaStore.Audio.Artists.ARTIST,
            MediaStore.Audio.Artists.NUMBER_OF_ALBUMS,
            MediaStore.Audio.Artists.NUMBER_OF_TRACKS,
        )

        private val ALBUM_PROJECTION = arrayOf(
            MediaStore.Audio.Albums._ID,
            MediaStore.Audio.Albums.ALBUM,
            MediaStore.Audio.Albums.ARTIST,
            MediaStore.Audio.Albums.ARTIST_ID,
            MediaStore.Audio.Albums.NUMBER_OF_SONGS,
            MediaStore.Audio.Albums.FIRST_YEAR,
        )

        private val MEDIA_PROJECTION = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
    }
}

private class PagedCursor(
    cursor: Cursor,
    private val offset: Int,
    private val limit: Int,
) : CursorWrapper(cursor) {
    private var remaining = limit
    private var searched = false

    override fun moveToNext(): Boolean {
        if (!searched) {
            searched = true
            if (offset > 0 && !super.moveToPosition(offset - 1)) return false
        }
        if (remaining <= 0) return false
        val moved = super.moveToNext()
        if (moved) remaining--
        return moved
    }

    override fun getCount(): Int = minOf(limit, maxOf(0, super.getCount() - offset))
}

class LocalMediaProviderFactory(private val contentResolver: ContentResolver) : MediaProviderFactory {

    override fun apiType(): String = API_TYPE

    override fun create(connection: ProviderConnection): MediaProvider = LocalMediaProvider(contentResolver)

    companion object {
        const val API_TYPE = "local"
    }
}
