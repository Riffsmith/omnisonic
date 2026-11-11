package app.omnisonic.feature.library.detail

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.feature.library.browse.BrowseUiState
import app.omnisonic.playback.api.controller.PlaybackController
import app.omnisonic.playback.api.controller.PlayerCoreState
import app.omnisonic.playback.api.events.PlaybackEvent
import app.omnisonic.playback.api.events.PlaybackEventBus
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.playback.api.queue.QueueState
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.playback.api.renderer.PlaybackRenderer
import app.omnisonic.playback.api.settings.NotificationAction
import app.omnisonic.playback.api.source.QueueSources
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `album load lands on content with header and first page`() = runTest {
        val source = FakeDetailSource(
            album = album(id = 10, title = "Night Ride", songCount = 5),
            albumTracks = songs(5),
        )
        val viewModel = DetailViewModel(source, FakePlaybackController())

        viewModel.load(DetailKind.ALBUM, 10)

        val state = viewModel.state.value
        assertThat(state).isInstanceOf(DetailUiState.Album::class.java)
        val albumState = state as DetailUiState.Album
        assertThat(albumState.header.title).isEqualTo("Night Ride")
        val tracks = albumState.tracks as BrowseUiState.Content
        assertThat(tracks.items).hasSize(5)
        assertThat(tracks.totalCount).isEqualTo(5)
        assertThat(tracks.hasMore).isFalse()
    }

    @Test
    fun `load is idempotent for the same detail and refetches nothing`() = runTest {
        val source = FakeDetailSource(album = album(id = 10), albumTracks = songs(2))
        val viewModel = DetailViewModel(source, FakePlaybackController())

        viewModel.load(DetailKind.ALBUM, 10)
        viewModel.load(DetailKind.ALBUM, 10)
        viewModel.load(DetailKind.ALBUM, 10)

        assertThat(source.headerLookups).containsExactly(10L)
    }

    @Test
    fun `missing header row lands on the missing state`() = runTest {
        val viewModel = DetailViewModel(FakeDetailSource(album = null), FakePlaybackController())

        viewModel.load(DetailKind.ALBUM, 4242)

        assertThat(viewModel.state.value).isEqualTo(DetailUiState.Missing)
    }

    @Test
    fun `failed header load lands on error and load retries`() = runTest {
        val source = FakeDetailSource(album = album(id = 10), failHeader = true)
        val viewModel = DetailViewModel(source, FakePlaybackController())

        viewModel.load(DetailKind.ALBUM, 10)
        assertThat(viewModel.state.value).isEqualTo(DetailUiState.Error)

        source.failHeader = false
        viewModel.load(DetailKind.ALBUM, 10)
        assertThat(viewModel.state.value).isInstanceOf(DetailUiState.Album::class.java)
    }

    @Test
    fun `playAll queues the loaded rows in order`() = runTest {
        val controller = FakePlaybackController()
        val source = FakeDetailSource(album = album(id = 10), albumTracks = songs(4))
        val viewModel = DetailViewModel(source, controller)
        viewModel.load(DetailKind.ALBUM, 10)

        viewModel.playAll()

        assertThat(controller.plays).hasSize(1)
        val (queued, start) = controller.plays.single()
        assertThat(start).isEqualTo(0)
        assertThat(queued.map { it.externalId }).containsExactly("e-1", "e-2", "e-3", "e-4").inOrder()
        assertThat(queued.first().title).isEqualTo("Song 1")
        assertThat(queued.first().songRowId).isEqualTo(1L)
        assertThat(controller.shuffleCalls).isEmpty()
    }

    @Test
    fun `playFrom starts at the tapped row`() = runTest {
        val controller = FakePlaybackController()
        val source = FakeDetailSource(album = album(id = 10), albumTracks = songs(4))
        val viewModel = DetailViewModel(source, controller)
        viewModel.load(DetailKind.ALBUM, 10)

        viewModel.playFrom(2)

        val (queued, start) = controller.plays.single()
        assertThat(start).isEqualTo(2)
        assertThat(queued).hasSize(4)
    }

    @Test
    fun `playFrom skips unresolvable rows without shifting the anchor`() = runTest {
        val controller = FakePlaybackController()
        val rows = songs(3).toMutableList()
        rows[0] = rows[0].copy(externalId = null)
        val source = FakeDetailSource(album = album(id = 10), albumTracks = rows)
        val viewModel = DetailViewModel(source, controller)
        viewModel.load(DetailKind.ALBUM, 10)

        viewModel.playFrom(1)

        val (queued, start) = controller.plays.single()
        assertThat(queued.map { it.externalId }).containsExactly("e-2", "e-3").inOrder()
        assertThat(start).isEqualTo(0)
    }

    @Test
    fun `shuffleAll plays then enables shuffle`() = runTest {
        val controller = FakePlaybackController()
        val source = FakeDetailSource(album = album(id = 10), albumTracks = songs(3))
        val viewModel = DetailViewModel(source, controller)
        viewModel.load(DetailKind.ALBUM, 10)

        viewModel.shuffleAll()

        assertThat(controller.plays).hasSize(1)
        assertThat(controller.plays.single().second).isEqualTo(0)
        assertThat(controller.shuffleCalls).containsExactly(true)
        assertThat(controller.callOrder).containsExactly("play", "shuffle").inOrder()
    }

    @Test
    fun `play actions before content are no-ops`() = runTest {
        val controller = FakePlaybackController()
        val viewModel = DetailViewModel(FakeDetailSource(), controller)

        viewModel.playAll()
        viewModel.shuffleAll()
        viewModel.playFrom(0)

        assertThat(controller.plays).isEmpty()
        assertThat(controller.shuffleCalls).isEmpty()
    }

    @Test
    fun `loadMore appends the next track window`() = runTest {
        val source = FakeDetailSource(album = album(id = 10, songCount = 130), albumTracks = songs(130))
        val viewModel = DetailViewModel(source, FakePlaybackController())
        viewModel.load(DetailKind.ALBUM, 10)
        val firstPage = (viewModel.state.value as DetailUiState.Album).tracks as BrowseUiState.Content
        assertThat(firstPage.items).hasSize(60)

        viewModel.loadMoreTracks()

        val tracks = (viewModel.state.value as DetailUiState.Album).tracks as BrowseUiState.Content
        assertThat(tracks.items).hasSize(120)
        assertThat(tracks.items.map { it.id }).containsNoDuplicates()
        assertThat(tracks.hasMore).isTrue()
    }

    @Test
    fun `playlist loads entries in position order and playFrom maps entries`() = runTest {
        val controller = FakePlaybackController()
        val source = FakeDetailSource(
            playlist = playlist(id = 30, entryCount = 3),
            playlistEntries = entries(3),
        )
        val viewModel = DetailViewModel(source, controller)
        viewModel.load(DetailKind.PLAYLIST, 30)

        val state = viewModel.state.value as DetailUiState.Playlist
        val content = state.entries as BrowseUiState.Content
        assertThat(content.items.map { it.title }).containsExactly("Entry 1", "Entry 2", "Entry 3").inOrder()

        viewModel.playFrom(1)

        val (queued, start) = controller.plays.single()
        assertThat(start).isEqualTo(1)
        assertThat(queued.map { it.externalId }).containsExactly("e-1", "e-2", "e-3").inOrder()
    }

    @Test
    fun `artist loads top tracks and albums side by side`() = runTest {
        val source = FakeDetailSource(
            artist = artist(id = 20, songCount = 3, albumCount = 2),
            artistTracks = songs(3),
            artistAlbums = listOf(album(id = 1, title = "First"), album(id = 2, title = "Second")),
        )
        val viewModel = DetailViewModel(source, FakePlaybackController())

        viewModel.load(DetailKind.ARTIST, 20)

        val state = viewModel.state.value as DetailUiState.Artist
        assertThat((state.tracks as BrowseUiState.Content).items).hasSize(3)
        assertThat((state.albums as BrowseUiState.Content).items.map { it.title })
            .containsExactly("First", "Second")
            .inOrder()
    }

    @Test
    fun `catalog change refreshes the header in place`() = runTest {
        val source = FakeDetailSource(album = album(id = 10, songCount = 5), albumTracks = songs(5))
        val viewModel = DetailViewModel(source, FakePlaybackController())
        viewModel.load(DetailKind.ALBUM, 10)

        source.album = album(id = 10, songCount = 9, title = "Night Ride (deluxe)")
        source.changes.emit(Unit)
        runCurrent()

        val header = (viewModel.state.value as DetailUiState.Album).header
        assertThat(header.songCount).isEqualTo(9)
        assertThat(header.title).isEqualTo("Night Ride (deluxe)")
    }

    @Test
    fun `catalog change to a deleted row moves the screen to missing`() = runTest {
        val source = FakeDetailSource(album = album(id = 10), albumTracks = songs(2))
        val viewModel = DetailViewModel(source, FakePlaybackController())
        viewModel.load(DetailKind.ALBUM, 10)

        source.album = null
        source.changes.emit(Unit)
        runCurrent()

        assertThat(viewModel.state.value).isEqualTo(DetailUiState.Missing)
    }

    @Test
    fun `catalog invalidation reloads the open window with the new count`() = runTest {
        val source = FakeDetailSource(album = album(id = 10, songCount = 60), albumTracks = songs(60))
        val viewModel = DetailViewModel(source, FakePlaybackController())
        viewModel.load(DetailKind.ALBUM, 10)

        source.albumTracks = songs(90)
        source.album = album(id = 10, songCount = 90)
        source.changes.emit(Unit)
        runCurrent()

        val tracks = (viewModel.state.value as DetailUiState.Album).tracks as BrowseUiState.Content
        assertThat(tracks.items).hasSize(60)
        assertThat(tracks.totalCount).isEqualTo(90)
        assertThat(tracks.hasMore).isTrue()
    }

    private fun songs(count: Int): List<SongEntity> = (1..count).map { index ->
        SongEntity(
            id = index.toLong(),
            providerId = 1,
            externalId = "e-$index",
            title = "Song $index",
            displayArtist = "Artist",
            durationMs = 200_000L,
            updatedAt = 0,
        )
    }

    private fun entries(count: Int): List<PlaylistEntryEntity> = (1..count).map { index ->
        PlaylistEntryEntity(
            id = index.toLong(),
            playlistId = 30,
            providerId = 1,
            position = index - 1,
            externalId = "e-$index",
            title = "Entry $index",
            addedAt = 0,
        )
    }

    private fun album(id: Long, title: String = "Album", songCount: Int = 0) = AlbumEntity(
        id = id,
        providerId = 1,
        externalId = "alb-$id",
        title = title,
        songCount = songCount,
        updatedAt = 0,
    )

    private fun artist(id: Long, songCount: Int = 0, albumCount: Int = 0) = ArtistEntity(
        id = id,
        providerId = 1,
        externalId = "art-$id",
        title = "Artist",
        songCount = songCount,
        albumCount = albumCount,
        updatedAt = 0,
    )

    private fun playlist(id: Long, entryCount: Int = 0) = PlaylistEntity(
        id = id,
        providerId = 1,
        externalId = "pl-$id",
        title = "Playlist",
        type = 0,
        entryCount = entryCount,
        updatedAt = 0,
    )

    private class FakeDetailSource(
        var album: AlbumEntity? = null,
        var artist: ArtistEntity? = null,
        var genre: GenreEntity? = null,
        var playlist: PlaylistEntity? = null,
        var albumTracks: List<SongEntity> = emptyList(),
        var artistTracks: List<SongEntity> = emptyList(),
        var artistAlbums: List<AlbumEntity> = emptyList(),
        var genreTracks: List<SongEntity> = emptyList(),
        var playlistEntries: List<PlaylistEntryEntity> = emptyList(),
        var failHeader: Boolean = false,
    ) : DetailSource {

        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val headerLookups = mutableListOf<Long>()

        override val libraryChanges: Flow<Unit>
            get() = changes

        private fun <T> page(rows: List<T>, offset: Int, limit: Int): BrowsePage<T> {
            val window = rows.drop(offset).take(limit)
            return BrowsePage(
                items = window,
                offset = offset,
                limit = limit,
                hasMore = offset + limit < rows.size,
            )
        }

        override suspend fun album(id: Long): AlbumEntity? {
            headerLookups.add(id)
            if (failHeader) throw IOException("header failed")
            return album
        }

        override suspend fun artist(id: Long): ArtistEntity? {
            headerLookups.add(id)
            if (failHeader) throw IOException("header failed")
            return artist
        }

        override suspend fun genre(id: Long): GenreEntity? {
            headerLookups.add(id)
            if (failHeader) throw IOException("header failed")
            return genre
        }

        override suspend fun playlist(id: Long): PlaylistEntity? {
            headerLookups.add(id)
            if (failHeader) throw IOException("header failed")
            return playlist
        }

        override suspend fun albumTracks(albumId: Long, offset: Int, limit: Int) =
            page(albumTracks, offset, limit)

        override suspend fun artistTracks(artistId: Long, offset: Int, limit: Int) =
            page(artistTracks, offset, limit)

        override suspend fun artistAlbums(artistId: Long, offset: Int, limit: Int) =
            page(artistAlbums, offset, limit)

        override suspend fun genreTracks(genreId: Long, offset: Int, limit: Int) =
            page(genreTracks, offset, limit)

        override suspend fun playlistEntries(playlistId: Long, offset: Int, limit: Int) =
            page(playlistEntries, offset, limit)
    }

    private class FakePlaybackController : PlaybackController {

        val plays = mutableListOf<Pair<List<PlaybackQueueEntry>, Int>>()
        val shuffleCalls = mutableListOf<Boolean>()
        val callOrder = mutableListOf<String>()

        private val queueStateFlow = MutableStateFlow(QueueState())
        private val playerStateFlow = MutableStateFlow(PlayerCoreState())

        override val queueState: StateFlow<QueueState> = queueStateFlow

        override val playerState: StateFlow<PlayerCoreState> = playerStateFlow

        override val events: PlaybackEventBus = object : PlaybackEventBus {
            private val flow = MutableSharedFlow<PlaybackEvent>()
            override val events: SharedFlow<PlaybackEvent> = flow
            override fun emit(event: PlaybackEvent) = Unit
        }

        override val queueSources: QueueSources = QueueSources(emptyList())

        override suspend fun play(
            entries: List<PlaybackQueueEntry>,
            startEntriesIndex: Int,
            sourceType: String?,
            sourceData: String?,
        ) {
            plays.add(entries to startEntriesIndex)
            callOrder.add("play")
        }

        override fun playOrPause() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setPlaybackSpeed(scale: Float) = Unit
        override fun setPlaybackPitch(pitch: Float) = Unit
        override fun applyAction(action: NotificationAction) = Unit

        override val renderer: PlaybackRenderer = object : PlaybackRenderer {
            override val rendererId: String = "fake"
            override val isLocal: Boolean = true

            override suspend fun activate(): Nothing =
                error("renderer is not exercised by DetailViewModel tests")

            override fun deactivate() = Unit
            override fun isActive(): Boolean = false
        }

        override suspend fun restoreQueue(): Boolean = false
        override fun release() = Unit
        override fun skipNext() = Unit
        override fun skipPrevious() = Unit
        override fun jumpToOrderIndex(orderIndex: Int) = Unit
        override fun setRepeatMode(mode: RepeatMode) = Unit

        override fun setShuffleEnabled(enabled: Boolean) {
            shuffleCalls.add(enabled)
            callOrder.add("shuffle")
        }

        override fun removeFromQueue(queueItemId: Long) = Unit
        override fun moveInQueue(queueItemId: Long, toEntriesIndex: Int) = Unit
        override fun clearQueueExceptCurrent() = Unit
    }
}
