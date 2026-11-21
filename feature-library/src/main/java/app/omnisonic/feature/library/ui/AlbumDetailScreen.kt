package app.omnisonic.feature.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.downloads.DownloadDestination
import app.omnisonic.feature.library.downloads.LibraryDownloadActionsViewModel
import app.omnisonic.feature.library.downloads.LibraryDownloadUiState
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.feature.library.R
import app.omnisonic.feature.library.browse.BrowseUiState
import app.omnisonic.feature.library.detail.DetailKind
import app.omnisonic.feature.library.detail.DetailUiState
import app.omnisonic.feature.library.detail.DetailViewModel
import app.omnisonic.provider.api.CoverImageKey

/**
 * Album detail (UI_SPEC.md §3.5): header (art, title, artist, meta) + the
 * album's track list in disc/track order. The "more from artist" section is
 * reserved (Phase 9 detail pages).
 */
@Composable
fun AlbumDetailScreen(
    state: DetailUiState,
    onPlayAll: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onShuffle: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    downloadActions: DetailDownloadActionsUi? = null,
    songActions: SongDownloadActionsUi? = null,
    modifier: Modifier = Modifier,
) {
    when (val current = state) {
        DetailUiState.Loading -> DetailLoadingBody()
        DetailUiState.Missing -> DetailMissingBody(onBack)
        DetailUiState.Error -> DetailErrorBody(onRetry)
        is DetailUiState.Album -> Column(modifier = modifier.fillMaxSize()) {
            DetailHeader(
                cover = current.header.imagePath?.let { CoverImageKey(current.header.providerId, it) },
                title = current.header.title,
                subtitle = current.header.displayArtist,
                meta = albumMeta(current.header),
                onPlay = onPlayAll,
                onShuffle = onShuffle,
                downloadActions = downloadActions,
            )
            when (val tracks = current.tracks) {
                BrowseUiState.Loading -> DetailLoadingBody()
                BrowseUiState.Empty -> DetailEmptyTracksBody()
                BrowseUiState.Error -> DetailErrorBody(onRetry)
                is BrowseUiState.Content -> TrackListBody(
                    content = tracks,
                    onPlayFrom = onPlayFrom,
                    onLoadMore = onLoadMore,
                    songActions = songActions,
                )
            }
        }
        is DetailUiState.Artist, is DetailUiState.Genre, is DetailUiState.Playlist -> Unit
    }
}

@Composable
fun AlbumDetailRoute(
    viewModel: DetailViewModel,
    albumId: Long,
    onBack: () -> Unit = {},
    downloadViewModel: LibraryDownloadActionsViewModel? = null,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(viewModel, albumId) { viewModel.load(DetailKind.ALBUM, albumId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads = downloadViewModel?.state?.collectAsStateWithLifecycle()?.value
        ?: LibraryDownloadUiState()
    val header = (state as? DetailUiState.Album)?.header
    LaunchedEffect(header?.providerId, header?.externalId, albumId) {
        header?.let { downloadViewModel?.bindAlbum(it.providerId, it.externalId, albumId) }
    }
    AlbumDetailScreen(
        state = state,
        onPlayAll = viewModel::playAll,
        onPlayFrom = viewModel::playFrom,
        onShuffle = viewModel::shuffleAll,
        onLoadMore = viewModel::loadMoreTracks,
        onRetry = { viewModel.load(DetailKind.ALBUM, albumId) },
        onBack = onBack,
        downloadActions = if (header != null && downloadViewModel != null) {
            DetailDownloadActionsUi(
                enabled = downloads.ready,
                busy = downloads.busy,
                preferredDestination = downloads.preferredDestination,
                ruleEnabled = downloads.ruleEnabled,
                ruleSupported = downloads.ruleSupported,
                notice = downloads.notice,
                onDownloadPreferred = downloadViewModel::downloadPreferred,
                onKeepOffline = { downloadViewModel.download(DownloadDestination.OFFLINE) },
                onExport = { downloadViewModel.download(DownloadDestination.EXPORT) },
                onToggleRule = downloadViewModel::toggleRule,
                onDismissNotice = downloadViewModel::dismissNotice,
            )
        } else {
            null
        },
        songActions = if (header != null && downloadViewModel != null) {
            SongDownloadActionsUi(
                onKeepOffline = { songId ->
                    downloadViewModel.enqueueSong(songId, DownloadDestination.OFFLINE)
                },
                onExport = { songId ->
                    downloadViewModel.enqueueSong(songId, DownloadDestination.EXPORT)
                },
            )
        } else {
            null
        },
        modifier = modifier,
    )
}

@Composable
private fun albumMeta(header: AlbumEntity): String? =
    metaLine(
        listOf(
            header.year?.toString(),
            header.songCount.takeIf { it > 0 }?.let { pluralMeta(R.plurals.detail_meta_songs, it) },
        ),
    )
