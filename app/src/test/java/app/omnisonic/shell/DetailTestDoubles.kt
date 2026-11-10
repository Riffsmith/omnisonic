package app.omnisonic.shell

import app.omnisonic.core.data.browse.BrowsePage
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.feature.library.detail.DetailSource
import app.omnisonic.feature.library.detail.DetailViewModel
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Shell tests only render the detail destinations; read and playback
 * behavior is exercised in `:core-data` and `:feature-library`. The empty
 * source keeps detail destinations on their empty/missing states.
 */
internal class EmptyDetailSource : DetailSource {
    override val libraryChanges: Flow<Unit> = emptyFlow()
    override suspend fun album(id: Long): AlbumEntity? = null
    override suspend fun artist(id: Long): ArtistEntity? = null
    override suspend fun genre(id: Long): GenreEntity? = null
    override suspend fun playlist(id: Long): PlaylistEntity? = null
    override suspend fun albumTracks(albumId: Long, offset: Int, limit: Int) = BrowsePage<SongEntity>(emptyList(), offset, limit, false)
    override suspend fun artistTracks(artistId: Long, offset: Int, limit: Int) = BrowsePage<SongEntity>(emptyList(), offset, limit, false)
    override suspend fun artistAlbums(artistId: Long, offset: Int, limit: Int) = BrowsePage<AlbumEntity>(emptyList(), offset, limit, false)
    override suspend fun genreTracks(genreId: Long, offset: Int, limit: Int) = BrowsePage<SongEntity>(emptyList(), offset, limit, false)
    override suspend fun playlistEntries(playlistId: Long, offset: Int, limit: Int) = BrowsePage<PlaylistEntryEntity>(emptyList(), offset, limit, false)
}

internal class NoopPlaybackController : PlaybackController {

    private val playerStateFlow = MutableStateFlow(PlayerCoreState())

    override val queueState: StateFlow<QueueState> =
        MutableStateFlow(QueueState())

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
    ) = Unit

    override fun playOrPause() = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setPlaybackSpeed(scale: Float) = Unit
    override fun setPlaybackPitch(pitch: Float) = Unit
    override fun applyAction(action: NotificationAction) = Unit

    override val renderer: PlaybackRenderer = object : PlaybackRenderer {
        override val rendererId: String = "noop"
        override val isLocal: Boolean = true
        override suspend fun activate(): Nothing = error("renderer is not exercised by shell tests")
        override fun deactivate() = Unit
        override fun isActive(): Boolean = false
    }

    override suspend fun restoreQueue(): Boolean = false
    override fun release() = Unit
    override fun skipNext() = Unit
    override fun skipPrevious() = Unit
    override fun jumpToOrderIndex(orderIndex: Int) = Unit
    override fun setRepeatMode(mode: RepeatMode) = Unit
    override fun setShuffleEnabled(enabled: Boolean) = Unit
    override fun onPlayWhenReady(playWhenReady: Boolean): Boolean = false
}

internal fun testDetailViewModel(): DetailViewModel =
    DetailViewModel(EmptyDetailSource(), NoopPlaybackController())
