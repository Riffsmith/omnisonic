package app.omnisonic.feature.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            )
            when (val tracks = current.tracks) {
                BrowseUiState.Loading -> DetailLoadingBody()
                BrowseUiState.Empty -> DetailEmptyTracksBody()
                BrowseUiState.Error -> DetailErrorBody(onRetry)
                is BrowseUiState.Content -> TrackListBody(
                    content = tracks,
                    onPlayFrom = onPlayFrom,
                    onLoadMore = onLoadMore,
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
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(viewModel, albumId) { viewModel.load(DetailKind.ALBUM, albumId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    AlbumDetailScreen(
        state = state,
        onPlayAll = viewModel::playAll,
        onPlayFrom = viewModel::playFrom,
        onShuffle = viewModel::shuffleAll,
        onLoadMore = viewModel::loadMoreTracks,
        onRetry = { viewModel.load(DetailKind.ALBUM, albumId) },
        onBack = onBack,
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
