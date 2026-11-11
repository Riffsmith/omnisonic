package app.omnisonic.feature.library.browse

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.feature.library.FakePlaybackController
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryBrowseViewModelTest {

    private fun viewModel(
        source: LibraryBrowseSource,
        controller: FakePlaybackController = FakePlaybackController(),
    ) = LibraryBrowseViewModel(source, controller)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load lands on content with count and hasMore`() = runTest {
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 130))
        val viewModel = viewModel(source)

        viewModel.load(LibrarySection.ALBUMS)

        val state = viewModel.albums.value
        assertThat(state).isInstanceOf(BrowseUiState.Content::class.java)
        val content = state as BrowseUiState.Content
        assertThat(content.items).hasSize(60)
        assertThat(content.totalCount).isEqualTo(130)
        assertThat(content.hasMore).isTrue()
        assertThat(content.appending).isFalse()
    }

    @Test
    fun `initial load is idempotent`() = runTest {
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 130))
        val viewModel = viewModel(source)

        viewModel.load(LibrarySection.ALBUMS)
        viewModel.load(LibrarySection.ALBUMS)
        viewModel.load(LibrarySection.ALBUMS)

        assertThat(source.pageOffsets.getValue(FakeSection.ALBUMS)).containsExactly(0)
    }

    @Test
    fun `loadMore appends the next window without duplicate keys`() = runTest {
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 130))
        val viewModel = viewModel(source)
        viewModel.load(LibrarySection.ALBUMS)

        viewModel.loadMore(LibrarySection.ALBUMS)

        val content = viewModel.albums.value as BrowseUiState.Content
        assertThat(content.items).hasSize(120)
        assertThat(source.pageOffsets.getValue(FakeSection.ALBUMS)).containsExactly(0, 60).inOrder()
        assertThat(content.items.map { it.id }).containsNoDuplicates()
        assertThat(content.hasMore).isTrue()

        viewModel.loadMore(LibrarySection.ALBUMS)
        val final = viewModel.albums.value as BrowseUiState.Content
        assertThat(final.items).hasSize(130)
        assertThat(final.hasMore).isFalse()
    }

    @Test
    fun `loadMore stops requesting once hasMore is false`() = runTest {
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 10))
        val viewModel = viewModel(source)
        viewModel.load(LibrarySection.ALBUMS)

        viewModel.loadMore(LibrarySection.ALBUMS)
        viewModel.loadMore(LibrarySection.ALBUMS)

        assertThat(source.pageOffsets.getValue(FakeSection.ALBUMS)).containsExactly(0)
    }

    @Test
    fun `initial load parks in loading until the page returns`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 130), pageGate = gate)
        val viewModel = viewModel(source)

        viewModel.load(LibrarySection.ALBUMS)
        assertThat(viewModel.albums.value).isEqualTo(BrowseUiState.Loading)
        assertThat(source.pageOffsets.getValue(FakeSection.ALBUMS)).containsExactly(0)

        gate.complete(Unit)
        val state = viewModel.albums.value
        assertThat(state).isInstanceOf(BrowseUiState.Content::class.java)
        assertThat((state as BrowseUiState.Content).items).hasSize(60)
    }

    @Test
    fun `concurrent loadMore requests collapse into one page fetch`() = runTest {
        val firstPage = CompletableDeferred<Unit>()
        val secondPage = CompletableDeferred<Unit>()
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 130))
        val viewModel = viewModel(source)

        source.pageGate = firstPage
        viewModel.load(LibrarySection.ALBUMS)
        firstPage.complete(Unit)
        assertThat(source.pageOffsets.getValue(FakeSection.ALBUMS)).containsExactly(0)

        source.pageGate = secondPage
        viewModel.loadMore(LibrarySection.ALBUMS)
        assertThat((viewModel.albums.value as BrowseUiState.Content).appending).isTrue()

        viewModel.loadMore(LibrarySection.ALBUMS)
        secondPage.complete(Unit)

        assertThat(source.pageOffsets.getValue(FakeSection.ALBUMS)).containsExactly(0, 60).inOrder()
        val content = viewModel.albums.value as BrowseUiState.Content
        assertThat(content.items).hasSize(120)
    }

    @Test
    fun `empty library lands on the empty state`() = runTest {
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 0))
        val viewModel = viewModel(source)

        viewModel.load(LibrarySection.ALBUMS)

        assertThat(viewModel.albums.value).isEqualTo(BrowseUiState.Empty)
    }

    @Test
    fun `initial failure lands on error and load retries`() = runTest {
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 5), failPagesAt = setOf(0))
        val viewModel = viewModel(source)

        viewModel.load(LibrarySection.ALBUMS)
        assertThat(viewModel.albums.value).isEqualTo(BrowseUiState.Error)

        source.failPagesAt = emptySet()
        viewModel.load(LibrarySection.ALBUMS)
        val state = viewModel.albums.value
        assertThat(state).isInstanceOf(BrowseUiState.Content::class.java)
        assertThat((state as BrowseUiState.Content).items).hasSize(5)
    }

    @Test
    fun `append failure keeps items and flags appendFailed until retried`() = runTest {
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 130), failPagesAt = setOf(60))
        val viewModel = viewModel(source)
        viewModel.load(LibrarySection.ALBUMS)

        viewModel.loadMore(LibrarySection.ALBUMS)

        val failed = viewModel.albums.value as BrowseUiState.Content
        assertThat(failed.items).hasSize(60)
        assertThat(failed.appendFailed).isTrue()
        assertThat(failed.appending).isFalse()

        source.failPagesAt = emptySet()
        viewModel.loadMore(LibrarySection.ALBUMS)
        val recovered = viewModel.albums.value as BrowseUiState.Content
        assertThat(recovered.items).hasSize(120)
        assertThat(recovered.appendFailed).isFalse()
    }

    @Test
    fun `sections route to their own source methods`() = runTest {
        val source = FakeBrowseSource(
            totals = mapOf(
                FakeSection.ALBUMS to 3,
                FakeSection.ARTISTS to 4,
                FakeSection.SONGS to 5,
                FakeSection.GENRES to 6,
                FakeSection.PLAYLISTS to 7,
            ),
        )
        val viewModel = viewModel(source)

        viewModel.load(LibrarySection.ARTISTS)
        viewModel.load(LibrarySection.SONGS)
        viewModel.load(LibrarySection.GENRES)
        viewModel.load(LibrarySection.PLAYLISTS)

        assertThat((viewModel.artists.value as BrowseUiState.Content).items).hasSize(4)
        assertThat((viewModel.songs.value as BrowseUiState.Content).items).hasSize(5)
        assertThat((viewModel.genres.value as BrowseUiState.Content).items).hasSize(6)
        assertThat((viewModel.playlists.value as BrowseUiState.Content).items).hasSize(7)
    }

    @Test
    fun `playFrom queues the loaded songs and starts at the tapped row`() = runTest {
        val controller = FakePlaybackController()
        val source = FakeBrowseSource(totals = mapOf(FakeSection.SONGS to 4))
        val viewModel = viewModel(source, controller)
        viewModel.load(LibrarySection.SONGS)

        viewModel.playFrom(2)

        val (queued, start) = controller.plays.single()
        assertThat(start).isEqualTo(2)
        assertThat(queued.map { it.externalId }).containsExactly("e-1", "e-2", "e-3", "e-4").inOrder()
        assertThat(queued.first().songRowId).isEqualTo(1L)
        assertThat(queued.first().title).isEqualTo("Song 1")
    }

    @Test
    fun `playFrom skips unresolvable rows without shifting the anchor`() = runTest {
        val controller = FakePlaybackController()
        val source = FakeBrowseSource(
            totals = mapOf(FakeSection.SONGS to 3),
            nullExternalIdIndexes = setOf(0),
        )
        val viewModel = viewModel(source, controller)
        viewModel.load(LibrarySection.SONGS)

        viewModel.playFrom(2)

        val (queued, start) = controller.plays.single()
        assertThat(queued.map { it.externalId }).containsExactly("e-2", "e-3").inOrder()
        assertThat(start).isEqualTo(1)
    }

    @Test
    fun `playFrom before content or out of range is a no-op`() = runTest {
        val controller = FakePlaybackController()
        val source = FakeBrowseSource(totals = mapOf(FakeSection.SONGS to 3))
        val viewModel = viewModel(source, controller)

        viewModel.playFrom(0)
        viewModel.load(LibrarySection.SONGS)
        viewModel.playFrom(9)

        assertThat(controller.plays).isEmpty()
    }

    @Test
    fun `playFrom after loadMore includes the appended window`() = runTest {
        val controller = FakePlaybackController()
        val source = FakeBrowseSource(totals = mapOf(FakeSection.SONGS to 70))
        val viewModel = viewModel(source, controller)
        viewModel.load(LibrarySection.SONGS)
        viewModel.loadMore(LibrarySection.SONGS)

        viewModel.playFrom(65)

        val (queued, start) = controller.plays.single()
        assertThat(queued).hasSize(70)
        assertThat(start).isEqualTo(65)
        assertThat(queued[65].externalId).isEqualTo("e-66")
    }

    @Test
    fun `catalog invalidation reloads an open section with the new count`() = runTest {
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 3))
        val viewModel = viewModel(source)
        viewModel.load(LibrarySection.ALBUMS)
        assertThat((viewModel.albums.value as BrowseUiState.Content).totalCount).isEqualTo(3)

        source.totals = mapOf(FakeSection.ALBUMS to 90)
        source.changes.emit(Unit)
        runCurrent()

        val content = viewModel.albums.value as BrowseUiState.Content
        assertThat(content.totalCount).isEqualTo(90)
        assertThat(content.items).hasSize(60)
        assertThat(content.hasMore).isTrue()
    }

    @Test
    fun `invalidation keeps the accumulated window instead of collapsing to one page`() = runTest {
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 130))
        val viewModel = viewModel(source)
        viewModel.load(LibrarySection.ALBUMS)
        viewModel.loadMore(LibrarySection.ALBUMS)
        assertThat((viewModel.albums.value as BrowseUiState.Content).items).hasSize(120)

        source.totals = mapOf(FakeSection.ALBUMS to 200)
        source.changes.emit(Unit)
        runCurrent()

        val content = viewModel.albums.value as BrowseUiState.Content
        assertThat(content.items).hasSize(120)
        assertThat(content.totalCount).isEqualTo(200)
        assertThat(source.pageRequests.getValue(FakeSection.ALBUMS).last()).isEqualTo(0 to 120)
    }

    @Test
    fun `invalidation before a section opens does not fetch anything`() = runTest {
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 10))
        viewModel(source)

        source.changes.emit(Unit)
        runCurrent()

        assertThat(source.pageRequests).isEmpty()
    }

    @Test
    fun `invalidation during the initial load refreshes once it finishes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeBrowseSource(totals = mapOf(FakeSection.ALBUMS to 3), pageGate = gate)
        val viewModel = viewModel(source)
        viewModel.load(LibrarySection.ALBUMS)
        assertThat(viewModel.albums.value).isEqualTo(BrowseUiState.Loading)

        source.totals = mapOf(FakeSection.ALBUMS to 90)
        source.changes.emit(Unit)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        val content = viewModel.albums.value as BrowseUiState.Content
        assertThat(content.totalCount).isEqualTo(90)
        assertThat(source.pageOffsets.getValue(FakeSection.ALBUMS)).containsExactly(0, 0).inOrder()
    }

    private enum class FakeSection { ALBUMS, ARTISTS, SONGS, GENRES, PLAYLISTS }

    private class FakeBrowseSource(
        var totals: Map<FakeSection, Int> = emptyMap(),
        var failPagesAt: Set<Int> = emptySet(),
        var pageGate: CompletableDeferred<Unit>? = null,
        var nullExternalIdIndexes: Set<Int> = emptySet(),
    ) : LibraryBrowseSource {

        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        override val libraryChanges: Flow<Unit>
            get() = changes

        val pageOffsets = mutableMapOf<FakeSection, MutableList<Int>>()
        val pageRequests = mutableMapOf<FakeSection, MutableList<Pair<Int, Int>>>()

        private fun record(section: FakeSection, offset: Int, limit: Int) {
            pageOffsets.getOrPut(section) { mutableListOf() }.add(offset)
            pageRequests.getOrPut(section) { mutableListOf() }.add(offset to limit)
        }

        private fun shouldFail(offset: Int): Boolean = offset in failPagesAt

        private fun indexes(offset: Int, limit: Int, total: Int): IntRange =
            offset until minOf(offset + limit, total)

        override suspend fun albums(offset: Int, limit: Int): BrowsePage<AlbumEntity> {
            record(FakeSection.ALBUMS, offset, limit)
            pageGate?.await()
            if (shouldFail(offset)) throw IOException("album page failed")
            val total = totals.getOrDefault(FakeSection.ALBUMS, 0)
            return BrowsePage(
                items = indexes(offset, limit, total).map { n ->
                    AlbumEntity(id = n + 1L, providerId = 1, title = "Album ${n + 1}", updatedAt = 0)
                },
                offset = offset,
                limit = limit,
                hasMore = offset + limit < total,
            )
        }

        override suspend fun artists(offset: Int, limit: Int): BrowsePage<ArtistEntity> {
            record(FakeSection.ARTISTS, offset, limit)
            if (shouldFail(offset)) throw IOException("artist page failed")
            val total = totals.getOrDefault(FakeSection.ARTISTS, 0)
            return BrowsePage(
                items = indexes(offset, limit, total).map { n ->
                    ArtistEntity(id = n + 1L, providerId = 1, title = "Artist ${n + 1}", updatedAt = 0)
                },
                offset = offset,
                limit = limit,
                hasMore = offset + limit < total,
            )
        }

        override suspend fun songs(offset: Int, limit: Int): BrowsePage<SongEntity> {
            record(FakeSection.SONGS, offset, limit)
            if (shouldFail(offset)) throw IOException("song page failed")
            val total = totals.getOrDefault(FakeSection.SONGS, 0)
            return BrowsePage(
                items = indexes(offset, limit, total).map { n ->
                    SongEntity(
                        id = n + 1L,
                        providerId = 1,
                        externalId = if (n in nullExternalIdIndexes) null else "e-${n + 1}",
                        title = "Song ${n + 1}",
                        displayArtist = "Artist",
                        updatedAt = 0,
                    )
                },
                offset = offset,
                limit = limit,
                hasMore = offset + limit < total,
            )
        }

        override suspend fun genres(offset: Int, limit: Int): BrowsePage<GenreEntity> {
            record(FakeSection.GENRES, offset, limit)
            if (shouldFail(offset)) throw IOException("genre page failed")
            val total = totals.getOrDefault(FakeSection.GENRES, 0)
            return BrowsePage(
                items = indexes(offset, limit, total).map { n ->
                    GenreEntity(id = n + 1L, providerId = 1, title = "Genre ${n + 1}", updatedAt = 0)
                },
                offset = offset,
                limit = limit,
                hasMore = offset + limit < total,
            )
        }

        override suspend fun playlists(offset: Int, limit: Int): BrowsePage<PlaylistEntity> {
            record(FakeSection.PLAYLISTS, offset, limit)
            if (shouldFail(offset)) throw IOException("playlist page failed")
            val total = totals.getOrDefault(FakeSection.PLAYLISTS, 0)
            return BrowsePage(
                items = indexes(offset, limit, total).map { n ->
                    PlaylistEntity(id = n + 1L, providerId = 1, title = "Playlist ${n + 1}", type = 0, updatedAt = 0)
                },
                offset = offset,
                limit = limit,
                hasMore = offset + limit < total,
            )
        }

        override suspend fun albumCount(): Int = totals.getOrDefault(FakeSection.ALBUMS, 0)

        override suspend fun artistCount(): Int = totals.getOrDefault(FakeSection.ARTISTS, 0)

        override suspend fun songCount(): Int = totals.getOrDefault(FakeSection.SONGS, 0)

        override suspend fun genreCount(): Int = totals.getOrDefault(FakeSection.GENRES, 0)

        override suspend fun playlistCount(): Int = totals.getOrDefault(FakeSection.PLAYLISTS, 0)
    }
}
