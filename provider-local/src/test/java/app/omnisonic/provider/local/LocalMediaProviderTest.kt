package app.omnisonic.provider.local

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.provider.api.AlbumListType
import app.omnisonic.provider.api.ProviderAlbumRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

private data class RecordedQuery(
    val uri: Uri,
    val selection: String?,
    val selectionArgs: Array<out String>?,
    val sortOrder: String?,
    val sortColumns: List<String>?,
    val sortDirection: Int?,
    val limit: Int?,
    val offset: Int?,
)

private class FakeMediaStoreProvider : ContentProvider() {

    val queries = mutableListOf<RecordedQuery>()
    private val columnsByUri = mutableMapOf<Uri, Array<String>>()
    private val rowsByUri = mutableMapOf<Uri, List<Array<Any?>>>()

    fun given(uri: Uri, columns: Array<String>, rows: List<Array<Any?>>) {
        columnsByUri[uri] = columns
        rowsByUri[uri] = rows
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = recordQuery(uri, selection, selectionArgs, sortOrder, null, null, null, null)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        queryArgs: Bundle?,
        cancellationSignal: CancellationSignal?,
    ): Cursor {
        val args = queryArgs ?: Bundle.EMPTY
        return recordQuery(
            uri = uri,
            selection = args.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
            selectionArgs = args.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
            sortOrder = args.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
            sortColumns = args.getStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS)?.toList(),
            sortDirection = args.getInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, Int.MIN_VALUE)
                .takeUnless { it == Int.MIN_VALUE },
            limit = args.getInt(ContentResolver.QUERY_ARG_LIMIT, Int.MIN_VALUE)
                .takeUnless { it == Int.MIN_VALUE },
            offset = args.getInt(ContentResolver.QUERY_ARG_OFFSET, Int.MIN_VALUE)
                .takeUnless { it == Int.MIN_VALUE },
        )
    }

    private fun recordQuery(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        sortColumns: List<String>?,
        sortDirection: Int?,
        limit: Int?,
        offset: Int?,
    ): Cursor {
        if (sortOrder?.contains("LIMIT", ignoreCase = true) == true) {
            throw IllegalArgumentException("Invalid token LIMIT")
        }
        queries += RecordedQuery(uri, selection, selectionArgs, sortOrder, sortColumns, sortDirection, limit, offset)

        val columns = columnsByUri.getValue(uri)
        var rows = rowsByUri[uri].orEmpty().toList()
        if (selection != null && selectionArgs != null && selectionArgs.isNotEmpty()) {
            Regex("([A-Za-z0-9_]+) = \\?").find(selection)?.groupValues?.get(1)?.let { column ->
                val index = columns.indexOf(column)
                if (index >= 0) rows = rows.filter { row -> row[index]?.toString() == selectionArgs[0] }
            }
            Regex("([A-Za-z0-9_]+) LIKE \\?", RegexOption.IGNORE_CASE).find(selection)
                ?.groupValues?.get(1)?.let { column ->
                    val index = columns.indexOf(column)
                    if (index >= 0) {
                        val needle = selectionArgs[0].trim('%')
                        rows = rows.filter { row -> row[index]?.toString()?.contains(needle, ignoreCase = true) == true }
                    }
                }
        }
        if (limit != null) {
            rows = rows.drop(offset ?: 0).take(limit)
        }

        val cursor = MatrixCursor(columns)
        rows.forEach(cursor::addRow)
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalMediaProviderTest {

    private val fake = FakeMediaStoreProvider()
    private lateinit var provider: LocalMediaProvider

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fake.given(
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
            ARTIST_COLUMNS,
            listOf(
                arrayOf("7", "Autechre", 12, 130),
                arrayOf("8", "Sade", 2, 19),
            ),
        )
        fake.given(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            ALBUM_COLUMNS,
            listOf(
                arrayOf("100", "Lovers Rock", "Sade", "8", 10, 2000),
                arrayOf("101", "Promise", "Sade", "8", 9, 1985),
                arrayOf("102", "Greatest Hits", "Sade", "8", 2, 1990),
                arrayOf("103", "Greatest Hits", "Autechre", "7", 1, 1995),
            ),
        )
        fake.given(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MEDIA_COLUMNS,
            listOf(
                arrayOf("501", "Smooth Operator", "Promise", "101", "Sade", "8", 3, 1985, 260_000, "audio/mpeg", "smooth.mp3"),
                arrayOf("500", "Kiss of Life", "Promise", "101", "Sade", "8", 1002, 1985, 210_000, "audio/flac", "kiss.flac"),
                arrayOf("502", "Sade Greatest", "Greatest Hits", "102", "Sade", "8", 1, 1990, 200_000, "audio/ogg", "track"),
                arrayOf("503", "Autechre Greatest", "Greatest Hits", "103", "Autechre", "7", 1, 1995, 180_000, "audio/mpeg", "autechre.mp3"),
            ),
        )
        fake.given(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            GENRE_COLUMNS,
            listOf(arrayOf("30", "soul"), arrayOf("31", "electronic")),
        )
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, fake)
        provider = LocalMediaProvider(context.contentResolver)
    }

    @Test
    fun `listArtists maps names and library counts`(): Unit = runBlocking {
        val artists = provider.listArtists()

        assertThat(artists).hasSize(2)
        assertThat(artists[0].externalId).isEqualTo("7")
        assertThat(artists[0].title).isEqualTo("Autechre")
        assertThat(artists[0].albumCount).isEqualTo(12)
        assertThat(artists[0].songCount).isEqualTo(130)
        assertThat(artists[1].title).isEqualTo("Sade")
    }

    @Test
    fun `listAlbums pages through MediaStore with bundle limit and offset`(): Unit = runBlocking {
        val albums = provider.listAlbums(ProviderAlbumRequest(type = AlbumListType.ALPHABETICAL_BY_NAME, size = 1, offset = 1))

        assertThat(albums).hasSize(1)
        assertThat(albums.single().title).isEqualTo("Promise")
        assertThat(albums.single().artistName).isEqualTo("Sade")
        assertThat(albums.single().artistExternalId).isEqualTo("8")
        assertThat(albums.single().songCount).isEqualTo(9)
        assertThat(albums.single().year).isEqualTo(1985)

        val query = fake.queries.single { it.uri == MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI }
        assertThat(query.sortOrder).isNull()
        assertThat(query.sortColumns).containsExactly(
            "${MediaStore.Audio.Albums.ALBUM} COLLATE NOCASE",
            MediaStore.Audio.Albums._ID,
        ).inOrder()
        assertThat(query.limit).isEqualTo(1)
        assertThat(query.offset).isEqualTo(1)
    }

    @Test
    fun `paged reads pass limits as query args and never as SQL sort order`(): Unit = runBlocking {
        provider.listAlbums(ProviderAlbumRequest(type = AlbumListType.NEWEST, size = 2, offset = 1))
        provider.searchArtists(query = "a", offset = 3, count = 4)
        provider.searchAlbums(query = "g", offset = 5, count = 6)
        provider.searchSongs(query = "h", offset = 7, count = 8)

        val paged = fake.queries.filter { it.limit != null }
        assertThat(paged).hasSize(4)
        assertThat(paged.map { it.limit }).containsExactly(2, 4, 6, 8).inOrder()
        assertThat(paged.map { it.offset }).containsExactly(1, 3, 5, 7).inOrder()
        assertThat(paged.map { it.sortOrder }).containsExactly(null, null, null, null)
        assertThat(paged.map { it.sortColumns }).doesNotContain(null)
    }

    @Test
    @Config(sdk = [29])
    fun `api 29 pages locally when the provider ignores bundled limits`(): Unit = runBlocking {
        val albums = provider.listAlbums(ProviderAlbumRequest(type = AlbumListType.ALPHABETICAL_BY_NAME, size = 1, offset = 1))

        assertThat(albums.map { it.title }).containsExactly("Promise")
        val query = fake.queries.single { it.uri == MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI }
        assertThat(query.limit).isNull()
        assertThat(query.offset).isNull()
    }

    @Test
    fun `album and song details map metadata with codec and disc split`(): Unit = runBlocking {
        val album = provider.albumDetails("100")!!
        assertThat(album.title).isEqualTo("Lovers Rock")
        assertThat(album.artistName).isEqualTo("Sade")
        assertThat(album.artistExternalId).isEqualTo("8")
        assertThat(album.songCount).isEqualTo(10)

        val song = provider.songDetails("500")!!
        assertThat(song.title).isEqualTo("Kiss of Life")
        assertThat(song.albumExternalId).isEqualTo("101")
        assertThat(song.artistExternalId).isEqualTo("8")
        assertThat(song.filePath).isNull()
        assertThat(song.codec).isEqualTo("flac")
        assertThat(song.trackNo).isEqualTo(2)
        assertThat(song.discNo).isEqualTo(1)
        assertThat(song.durationMs).isEqualTo(210_000L)

        val plain = provider.songDetails("501")!!
        assertThat(plain.filePath).isNull()
        assertThat(plain.codec).isEqualTo("mp3")
        assertThat(plain.discNo).isNull()
        assertThat(plain.trackNo).isEqualTo(3)

        val mimeOnly = provider.songDetails("502")!!
        assertThat(mimeOnly.codec).isEqualTo("ogg")
    }

    @Test
    fun `browseDirectory resolves artists to albums and albums to songs`(): Unit = runBlocking {
        val artistDirectory = provider.browseDirectory("8")
        assertThat(artistDirectory.childAlbums.map { it.title }).containsExactly("Lovers Rock", "Promise", "Greatest Hits").inOrder()

        val albumDirectory = provider.browseDirectory("101")
        assertThat(albumDirectory.childSongs.map { it.title }).containsExactly("Smooth Operator", "Kiss of Life").inOrder()
    }

    @Test
    fun `albums sharing a title keep their song lists separate`(): Unit = runBlocking {
        val sade = provider.browseDirectory("102")
        val autechre = provider.browseDirectory("103")

        assertThat(sade.childAlbums).isEmpty()
        assertThat(autechre.childAlbums).isEmpty()
        assertThat(sade.childSongs.map { it.title }).containsExactly("Sade Greatest")
        assertThat(autechre.childSongs.map { it.title }).containsExactly("Autechre Greatest")
    }

    @Test
    fun `search filters songs by title`(): Unit = runBlocking {
        val songs = provider.searchSongs(query = "smooth", count = 5, offset = 0)

        assertThat(songs.map { it.title }).containsExactly("Smooth Operator")
    }

    @Test
    fun `user data operations are local no-ops that report success`(): Unit = runBlocking {
        assertThat(provider.probe().extensionNames).isEmpty()
        assertThat(provider.submitPlays("500", 2, lastPlayedAtMs = 1_000)).isEqualTo(2)
        assertThat(provider.setFavorite(app.omnisonic.provider.api.ProviderMediaType.SONG, "500", favorite = true)).isTrue()
        assertThat(provider.setUserRating(app.omnisonic.provider.api.ProviderMediaType.SONG, "500", rating = 4)).isTrue()
        assertThat(provider.saveBookmark("500", positionMs = 1_000)).isTrue()
        assertThat(provider.deleteBookmark("500")).isTrue()
        assertThat(provider.listRadios()).isEmpty()
        assertThat(provider.listPlaylists()).isEmpty()
        assertThat(provider.listBookmarks()).isEmpty()
        assertThat(provider.coverArtUrl("ca-1")).isNull()
        assertThat(provider.streamUrl("500")).isEqualTo(
            android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 500).toString(),
        )
    }

    @Test
    fun `factory registers under the local api type`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = LocalMediaProviderFactory(context.contentResolver)

        assertThat(factory.apiType()).isEqualTo("local")
        assertThat(factory.create(app.omnisonic.provider.api.ProviderConnection(providerId = 1, primary = app.omnisonic.provider.api.ConnectionBlock("local", "localhost", 0)))).isInstanceOf(LocalMediaProvider::class.java)
    }

    @Test
    fun `listGenres maps names`() = runBlocking {
        assertThat(provider.listGenres().map { it.externalId }).containsExactly("30", "31").inOrder()
    }

    private companion object {
        val ARTIST_COLUMNS = arrayOf(
            MediaStore.Audio.Artists._ID,
            MediaStore.Audio.Artists.ARTIST,
            MediaStore.Audio.Artists.NUMBER_OF_ALBUMS,
            MediaStore.Audio.Artists.NUMBER_OF_TRACKS,
        )

        val ALBUM_COLUMNS = arrayOf(
            MediaStore.Audio.Albums._ID,
            MediaStore.Audio.Albums.ALBUM,
            MediaStore.Audio.Albums.ARTIST,
            MediaStore.Audio.Albums.ARTIST_ID,
            MediaStore.Audio.Albums.NUMBER_OF_SONGS,
            MediaStore.Audio.Albums.FIRST_YEAR,
        )

        val MEDIA_COLUMNS = arrayOf(
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

        val GENRE_COLUMNS = arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME)
    }
}
