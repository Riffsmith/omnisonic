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

/**
 * Genre detail (UI_SPEC.md §3.5): placeholder header (genres carry no art)
 * + the genre's tracks. Albums/artists tabs are reserved (Phase 9).
 */
@Composable
fun GenreDetailScreen(
    state: DetailUiState,
    onPlayAll: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onShuffle: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRetryTracks: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val current = state) {
        DetailUiState.Loading -> DetailLoadingBody()
        DetailUiState.Missing -> DetailMissingBody(onBack)
        DetailUiState.Error -> DetailErrorBody(onRetry)
        is DetailUiState.Genre -> Column(modifier = modifier.fillMaxSize()) {
            DetailHeader(
                cover = null,
                title = current.header.title,
                subtitle = null,
                meta = current.header.songCount.takeIf { it > 0 }
                    ?.let { pluralMeta(R.plurals.detail_meta_songs, it) },
                onPlay = onPlayAll,
                onShuffle = onShuffle,
            )
            when (val tracks = current.tracks) {
                BrowseUiState.Loading -> DetailLoadingBody()
                BrowseUiState.Empty -> DetailEmptyTracksBody()
                BrowseUiState.Error -> DetailErrorBody(onRetryTracks)
                is BrowseUiState.Content -> TrackListBody(
                    content = tracks,
                    onPlayFrom = onPlayFrom,
                    onLoadMore = onLoadMore,
                )
            }
        }
        is DetailUiState.Album, is DetailUiState.Artist, is DetailUiState.Playlist -> Unit
    }
}

@Composable
fun GenreDetailRoute(
    viewModel: DetailViewModel,
    genreId: Long,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(viewModel, genreId) { viewModel.load(DetailKind.GENRE, genreId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    GenreDetailScreen(
        state = state,
        onPlayAll = viewModel::playAll,
        onPlayFrom = viewModel::playFrom,
        onShuffle = viewModel::shuffleAll,
        onLoadMore = viewModel::loadMoreTracks,
        onRetry = { viewModel.load(DetailKind.GENRE, genreId) },
        onRetryTracks = viewModel::retryTracks,
        onBack = onBack,
        modifier = modifier,
    )
}
