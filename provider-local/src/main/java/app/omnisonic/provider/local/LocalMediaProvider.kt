package app.omnisonic.provider.local

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
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

/**
 * Device-library provider backed by MediaStore audio rows. Local files carry no
 * server state, so user-data operations are no-ops and plays report as fully
 * accepted so the outbox drains them without retries.
 */
class LocalMediaProvider(private val contentResolver: ContentResolver) : MediaProvider {

    override suspend fun probe(): ProviderCapabilities = ProviderCapabilities()

    override suspend fun listArtists(): List<ProviderArtist> =
        contentResolver.query(
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Artists._ID,
                MediaStore.Audio.Artists.ARTIST,
                MediaStore.Audio.Artists.NUMBER_OF_ALBUMS,
                MediaStore.Audio.Artists.NUMBER_OF_TRACKS,
            ),
            null,
            null,
            "${MediaStore.Audio.Artists.ARTIST} COLLATE NOCASE ASC",
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
        val artistName = artistName(externalId)
        if (artistName != null) {
            return ProviderDirectory(
                externalId = externalId,
                childAlbums = albumsBy(MediaStore.Audio.Albums.ARTIST, artistName),
            )
        }
        val albumName = albumName(externalId)
        return ProviderDirectory(
            externalId = externalId,
            childSongs = albumName?.let { songsBy(MediaStore.Audio.Media.ALBUM, it) }.orEmpty(),
        )
    }

    override suspend fun albumDetails(albumExternalId: String): ProviderAlbum? =
        contentResolver.query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            ALBUM_PROJECTION,
            "${MediaStore.Audio.Albums._ID} = ?",
            arrayOf(albumExternalId),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.toAlbum() else null }

    override suspend fun songDetails(externalId: String): ProviderSong? =
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MEDIA_PROJECTION,
            "${MediaStore.Audio.Media._ID} = ?",
            arrayOf(externalId),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.toSong() else null }

    override suspend fun listAlbums(request: ProviderAlbumRequest): List<ProviderAlbum> =
        contentResolver.query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            ALBUM_PROJECTION,
            null,
            null,
            "${albumSort(request.type)} LIMIT ${request.size} OFFSET ${request.offset}",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toAlbum())
                }
            }
        }.orEmpty()

    override suspend fun listGenres(): List<ProviderGenre> =
        contentResolver.query(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
            null,
            null,
            "${MediaStore.Audio.Genres.NAME} COLLATE NOCASE ASC",
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
        contentResolver.query(
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
            ARTIST_PROJECTION,
            "${MediaStore.Audio.Artists.ARTIST} LIKE ?",
            arrayOf("%${query.trim()}%"),
            "${MediaStore.Audio.Artists.ARTIST} COLLATE NOCASE ASC LIMIT $count OFFSET $offset",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toArtist())
                }
            }
        }.orEmpty()

    override suspend fun searchAlbums(query: String, offset: Int, count: Int): List<ProviderAlbum> =
        contentResolver.query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            ALBUM_PROJECTION,
            "${MediaStore.Audio.Albums.ALBUM} LIKE ?",
            arrayOf("%${query.trim()}%"),
            "${MediaStore.Audio.Albums.ALBUM} COLLATE NOCASE ASC LIMIT $count OFFSET $offset",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toAlbum())
                }
            }
        }.orEmpty()

    override suspend fun searchSongs(query: String, offset: Int, count: Int): List<ProviderSong> =
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MEDIA_PROJECTION,
            MUSIC_SELECTION + " AND (${MediaStore.Audio.Media.TITLE} LIKE ?)",
            arrayOf("%${query.trim()}%"),
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC LIMIT $count OFFSET $offset",
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

    private suspend fun artistName(externalId: String): String? =
        contentResolver.query(
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Artists._ID, MediaStore.Audio.Artists.ARTIST),
            "${MediaStore.Audio.Artists._ID} = ?",
            arrayOf(externalId),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.string(MediaStore.Audio.Artists.ARTIST) else null }

    private suspend fun albumName(externalId: String): String? =
        contentResolver.query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Albums._ID, MediaStore.Audio.Albums.ALBUM),
            "${MediaStore.Audio.Albums._ID} = ?",
            arrayOf(externalId),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.string(MediaStore.Audio.Albums.ALBUM) else null }

    private suspend fun albumsBy(column: String, value: String): List<ProviderAlbum> =
        contentResolver.query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            ALBUM_PROJECTION,
            "$column = ?",
            arrayOf(value),
            "${MediaStore.Audio.Albums.FIRST_YEAR} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toAlbum())
                }
            }
        }.orEmpty()

    private suspend fun songsBy(column: String, value: String): List<ProviderSong> =
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MEDIA_PROJECTION,
            "$MUSIC_SELECTION AND ($column = ?)",
            arrayOf(value),
            "${MediaStore.Audio.Media.TRACK} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toSong())
                }
            }
        }.orEmpty()

    private fun albumSort(type: AlbumListType): String = when (type) {
        AlbumListType.NEWEST -> "${MediaStore.Audio.Albums.LAST_YEAR} DESC, ${MediaStore.Audio.Albums._ID} DESC"
        AlbumListType.ALPHABETICAL_BY_NAME -> "${MediaStore.Audio.Albums.ALBUM} COLLATE NOCASE ASC, ${MediaStore.Audio.Albums._ID} ASC"
        AlbumListType.ALPHABETICAL_BY_ARTIST ->
            "${MediaStore.Audio.Albums.ARTIST} COLLATE NOCASE ASC, ${MediaStore.Audio.Albums.ALBUM} COLLATE NOCASE ASC"
        AlbumListType.BY_GENRE, AlbumListType.RANDOM -> "${MediaStore.Audio.Albums._ID} ASC"
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
        artistName = string(MediaStore.Audio.Albums.ARTIST),
        year = int(MediaStore.Audio.Albums.FIRST_YEAR),
        songCount = int(MediaStore.Audio.Albums.NUMBER_OF_SONGS),
    )

    private fun Cursor.toSong(): ProviderSong {
        val filePath = string(MediaStore.Audio.Media.DATA)
        val track = int(MediaStore.Audio.Media.TRACK) ?: 0
        return ProviderSong(
            externalId = string(MediaStore.Audio.Media._ID).orEmpty(),
            title = string(MediaStore.Audio.Media.TITLE).orEmpty(),
            artistName = string(MediaStore.Audio.Media.ARTIST),
            albumExternalId = null,
            trackNo = if (track > 0) track % TRACK_GRANULARITY else null,
            discNo = if (track >= TRACK_GRANULARITY) track / TRACK_GRANULARITY else null,
            year = int(MediaStore.Audio.Media.YEAR),
            imagePath = null,
            durationMs = int(MediaStore.Audio.Media.DURATION)?.toLong(),
            filePath = filePath,
            codec = filePath?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() },
        )
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
            MediaStore.Audio.Albums.NUMBER_OF_SONGS,
            MediaStore.Audio.Albums.FIRST_YEAR,
        )

        private val MEDIA_PROJECTION = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
        )
    }
}

class LocalMediaProviderFactory(private val contentResolver: ContentResolver) : MediaProviderFactory {

    override fun apiType(): String = API_TYPE

    override fun create(connection: ProviderConnection): MediaProvider = LocalMediaProvider(contentResolver)

    companion object {
        const val API_TYPE = "local"
    }
}
