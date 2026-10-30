package app.omnisonic.provider.local

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import app.omnisonic.provider.api.AlbumListType
import app.omnisonic.provider.api.ProviderAlbumRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
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
    ): Cursor {
        queries += RecordedQuery(uri, selection, selectionArgs, sortOrder)
        val columns = columnsByUri.getValue(uri)
        val cursor = MatrixCursor(columns)
        var rows = rowsByUri[uri].orEmpty()
        if (selection?.contains("_id = ?", ignoreCase = true) == true && selectionArgs != null) {
            rows = rows.filter { row -> row[0]?.toString() == selectionArgs[0] }
        }
        if (selection?.contains("like ?", ignoreCase = true) == true && selectionArgs != null) {
            val needle = selectionArgs[0].trim('%')
            rows = rows.filter { row -> row.drop(1).any { it?.toString()?.contains(needle, ignoreCase = true) == true } }
        }
        if (sortOrder != null) {
            val limit = Regex("LIMIT (\\d+)").find(sortOrder)?.groupValues?.get(1)?.toIntOrNull()
            val offset = Regex("OFFSET (\\d+)").find(sortOrder)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            rows = rows.drop(offset).let { if (limit == null) it else it.take(limit) }
        }
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
        fake.given(MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI, ARTIST_COLUMNS, listOf(arrayOf("7", "Autechre", 12, 130), arrayOf("8", "Sade", 2, 19)))
        fake.given(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, ALBUM_COLUMNS, listOf(arrayOf("100", "Lovers Rock", "Sade", 10, 2000), arrayOf("101", "Promise", "Sade", 9, 1985)))
        fake.given(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MEDIA_COLUMNS, listOf(arrayOf("501", "Smooth Operator", "Promise", "Sade", 3, 1985, 260_000, "/music/sade/smooth.mp3"), arrayOf("500", "Kiss of Life", "Promise", "Sade", 1002, 1985, 210_000, "/music/sade/kiss.flac")))
        fake.given(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI, GENRE_COLUMNS, listOf(arrayOf("30", "soul"), arrayOf("31", "electronic")))
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
    fun `listAlbums pages through MediaStore with limit and offset`(): Unit = runBlocking {
        val albums = provider.listAlbums(ProviderAlbumRequest(type = AlbumListType.ALPHABETICAL_BY_NAME, size = 1, offset = 1))

        assertThat(albums).hasSize(1)
        assertThat(albums.single().title).isEqualTo("Promise")
        assertThat(albums.single().artistName).isEqualTo("Sade")
        assertThat(albums.single().songCount).isEqualTo(9)
        assertThat(albums.single().year).isEqualTo(1985)

        val sort = fake.queries.single { it.uri == MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI }.sortOrder
        assertTrue(sort.orEmpty().contains("LIMIT 1 OFFSET 1"))
    }

    @Test
    fun `album and song details map metadata with codec and disc split`(): Unit = runBlocking {
        val album = provider.albumDetails("100")!!
        assertThat(album.title).isEqualTo("Lovers Rock")
        assertThat(album.artistName).isEqualTo("Sade")
        assertThat(album.songCount).isEqualTo(10)

        val song = provider.songDetails("500")!!
        assertThat(song.title).isEqualTo("Kiss of Life")
        assertThat(song.filePath).isEqualTo("/music/sade/kiss.flac")
        assertThat(song.codec).isEqualTo("flac")
        assertThat(song.trackNo).isEqualTo(2)
        assertThat(song.discNo).isEqualTo(1)
        assertThat(song.durationMs).isEqualTo(210_000L)

        val plain = provider.songDetails("501")!!
        assertThat(plain.codec).isEqualTo("mp3")
        assertThat(plain.discNo).isNull()
        assertThat(plain.trackNo).isEqualTo(3)
    }

    @Test
    fun `browseDirectory resolves artists to albums and albums to songs`(): Unit = runBlocking {
        val artistDirectory = provider.browseDirectory("8")
        assertThat(artistDirectory.childAlbums.map { it.title }).containsExactly("Lovers Rock", "Promise").inOrder()

        val albumDirectory = provider.browseDirectory("101")
        assertThat(albumDirectory.childSongs.map { it.title }).containsExactly("Smooth Operator", "Kiss of Life").inOrder()
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
            MediaStore.Audio.Albums.NUMBER_OF_SONGS,
            MediaStore.Audio.Albums.FIRST_YEAR,
        )

        val MEDIA_COLUMNS = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
        )

        val GENRE_COLUMNS = arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME)
    }
}
