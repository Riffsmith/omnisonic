package app.omnisonic.feature.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.feature.library.R
import app.omnisonic.feature.library.browse.BrowseUiState
import app.omnisonic.feature.library.detail.DetailKind
import app.omnisonic.feature.library.detail.DetailUiState
import app.omnisonic.feature.library.detail.DetailViewModel
import app.omnisonic.provider.api.CoverImageKey

/**
 * Playlist detail (UI_SPEC.md §3.5): header + the entry list in `position`
 * order (the server-reported sequence).
 */
@Composable
fun PlaylistDetailScreen(
    state: DetailUiState,
    onPlayAll: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onShuffle: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRetryEntries: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val current = state) {
        DetailUiState.Loading -> DetailLoadingBody()
        DetailUiState.Missing -> DetailMissingBody(onBack)
        DetailUiState.Error -> DetailErrorBody(onRetry)
        is DetailUiState.Playlist -> Column(modifier = modifier.fillMaxSize()) {
            DetailHeader(
                cover = current.header.imagePath?.let { CoverImageKey(current.header.providerId, it) },
                title = current.header.title,
                subtitle = current.header.description,
                meta = current.header.entryCount.takeIf { it > 0 }
                    ?.let { pluralMeta(R.plurals.detail_meta_tracks, it) },
                onPlay = onPlayAll,
                onShuffle = onShuffle,
            )
            when (val entries = current.entries) {
                BrowseUiState.Loading -> DetailLoadingBody()
                BrowseUiState.Empty -> DetailEmptyTracksBody()
                BrowseUiState.Error -> DetailErrorBody(onRetryEntries)
                is BrowseUiState.Content -> EntryListBody(
                    content = entries,
                    onPlayFrom = onPlayFrom,
                    onLoadMore = onLoadMore,
                )
            }
        }
        is DetailUiState.Album, is DetailUiState.Artist, is DetailUiState.Genre -> Unit
    }
}

@Composable
fun PlaylistDetailRoute(
    viewModel: DetailViewModel,
    playlistId: Long,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(viewModel, playlistId) { viewModel.load(DetailKind.PLAYLIST, playlistId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlaylistDetailScreen(
        state = state,
        onPlayAll = viewModel::playAll,
        onPlayFrom = viewModel::playFrom,
        onShuffle = viewModel::shuffleAll,
        onLoadMore = viewModel::loadMoreEntries,
        onRetry = { viewModel.load(DetailKind.PLAYLIST, playlistId) },
        onRetryEntries = viewModel::retryEntries,
        onBack = onBack,
        modifier = modifier,
    )
}
