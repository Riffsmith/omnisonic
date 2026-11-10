package app.omnisonic.feature.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.feature.library.R
import app.omnisonic.feature.library.browse.BrowseUiState
import app.omnisonic.feature.library.detail.DetailKind
import app.omnisonic.feature.library.detail.DetailUiState
import app.omnisonic.feature.library.detail.DetailViewModel
import app.omnisonic.provider.api.CoverImageKey
import app.omnisonic.style.OmnisonicTheme

/**
 * Artist detail (UI_SPEC.md §3.5): header, top tracks (most played first),
 * then the artist's albums strip — one scrollable body so both sections
 * share the page. "Appears on" is reserved (Phase 9).
 */
@Composable
fun ArtistDetailScreen(
    state: DetailUiState,
    onPlayAll: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onShuffle: () -> Unit,
    onLoadMoreTracks: () -> Unit,
    onLoadMoreAlbums: () -> Unit,
    onRetryTracks: () -> Unit,
    onRetryAlbums: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val current = state) {
        DetailUiState.Loading -> DetailLoadingBody()
        DetailUiState.Missing -> DetailMissingBody(onBack)
        DetailUiState.Error -> DetailErrorBody(onRetry)
        is DetailUiState.Artist -> Column(modifier = modifier.fillMaxSize()) {
            DetailHeader(
                cover = current.header.imagePath?.let { CoverImageKey(current.header.providerId, it) },
                title = current.header.title,
                subtitle = null,
                meta = artistMeta(current.header),
                onPlay = onPlayAll,
                onShuffle = onShuffle,
            )
            ArtistBody(
                tracks = current.tracks,
                albums = current.albums,
                onPlayFrom = onPlayFrom,
                onLoadMoreTracks = onLoadMoreTracks,
                onLoadMoreAlbums = onLoadMoreAlbums,
                onRetryTracks = onRetryTracks,
                onRetryAlbums = onRetryAlbums,
                onOpenAlbum = onOpenAlbum,
                modifier = Modifier.weight(1f),
            )
        }
        is DetailUiState.Album, is DetailUiState.Genre, is DetailUiState.Playlist -> Unit
    }
}

@Composable
fun ArtistDetailRoute(
    viewModel: DetailViewModel,
    artistId: Long,
    onOpenAlbum: (Long) -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(viewModel, artistId) { viewModel.load(DetailKind.ARTIST, artistId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ArtistDetailScreen(
        state = state,
        onPlayAll = viewModel::playAll,
        onPlayFrom = viewModel::playFrom,
        onShuffle = viewModel::shuffleAll,
        onLoadMoreTracks = viewModel::loadMoreTracks,
        onLoadMoreAlbums = viewModel::loadMoreAlbums,
        onRetryTracks = viewModel::retryTracks,
        onRetryAlbums = viewModel::retryAlbums,
        onOpenAlbum = onOpenAlbum,
        onRetry = { viewModel.load(DetailKind.ARTIST, artistId) },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun ArtistBody(
    tracks: BrowseUiState<SongEntity>,
    albums: BrowseUiState<AlbumEntity>,
    onPlayFrom: (Int) -> Unit,
    onLoadMoreTracks: () -> Unit,
    onLoadMoreAlbums: () -> Unit,
    onRetryTracks: () -> Unit,
    onRetryAlbums: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "tracks-title") {
            DetailSectionTitle(stringResource(R.string.detail_artist_top_tracks))
        }
        when (tracks) {
            is BrowseUiState.Content -> {
                itemsIndexed(tracks.items, key = { _, song -> song.id }) { index, song ->
                    TrackRow(song = song, onClick = { onPlayFrom(index) })
                }
                if (tracks.hasMore) {
                    item(key = "tracks-more") {
                        LoadMoreFooter(
                            appending = tracks.appending,
                            appendFailed = tracks.appendFailed,
                            onLoadMore = onLoadMoreTracks,
                        )
                    }
                }
            }
            BrowseUiState.Loading -> item(key = "tracks-loading") {
                TrackPlaceholders(Modifier.fillParentMaxHeight(ARTIST_SECTION_MAX_FRACTION))
            }
            BrowseUiState.Empty -> item(key = "tracks-empty") {
                DetailMessageBody(
                    text = stringResource(R.string.detail_body_empty),
                    modifier = Modifier.fillParentMaxHeight(ARTIST_SECTION_MAX_FRACTION),
                )
            }
            BrowseUiState.Error -> item(key = "tracks-error") {
                DetailErrorBody(
                    onRetry = onRetryTracks,
                    modifier = Modifier.fillParentMaxHeight(ARTIST_SECTION_MAX_FRACTION),
                )
            }
        }
        item(key = "albums-title") {
            DetailSectionTitle(stringResource(R.string.detail_artist_albums))
        }
        when (albums) {
            is BrowseUiState.Content -> item(key = "albums-strip") {
                AlbumStrip(
                    content = albums,
                    onOpenAlbum = onOpenAlbum,
                    onLoadMore = onLoadMoreAlbums,
                )
            }
            BrowseUiState.Error -> item(key = "albums-error") {
                TextButton(onClick = onRetryAlbums) {
                    Text(stringResource(R.string.library_retry))
                }
            }
            BrowseUiState.Loading, BrowseUiState.Empty -> Unit
        }
    }
}

/** Compact horizontal album strip with its own paging surface. */
@Composable
private fun AlbumStrip(
    content: BrowseUiState.Content<AlbumEntity>,
    onOpenAlbum: (Long) -> Unit,
    onLoadMore: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    LazyRow(
        contentPadding = PaddingValues(horizontal = tokens.layout.pagePadding),
        horizontalArrangement = Arrangement.spacedBy(tokens.layout.gridSpacing),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(content.items, key = { it.id }) { album ->
            AlbumStripTile(
                album = album,
                onClick = { onOpenAlbum(album.id) },
            )
        }
        if (content.hasMore) {
            item(key = "load-more") {
                LoadMoreFooter(
                    appending = content.appending,
                    appendFailed = content.appendFailed,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

@Composable
private fun AlbumStripTile(
    album: AlbumEntity,
    onClick: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val tileWidth = tokens.imagery.listThumbSize * 2
    Column(
        modifier = Modifier
            .width(tileWidth)
            .clickable(onClick = onClick)
            .padding(vertical = tokens.spacing.sm),
    ) {
        CoverImage(
            cover = album.imagePath?.let { CoverImageKey(album.providerId, it) },
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(tokens.imagery.gridAspectRatio),
        )
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = tokens.spacing.xs),
        )
    }
}

/** Placeholder/error bodies inside the artist body cap their height so the albums section stays reachable. */
private const val ARTIST_SECTION_MAX_FRACTION = 0.5f

@Composable
private fun DetailSectionTitle(text: String) {
    val tokens = OmnisonicTheme.tokens
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(
            horizontal = tokens.layout.pagePadding,
            vertical = tokens.spacing.sm,
        ),
    )
}

@Composable
private fun artistMeta(header: ArtistEntity): String? =
    metaLine(
        listOf(
            header.albumCount.takeIf { it > 0 }?.let { pluralMeta(R.plurals.detail_meta_albums, it) },
            header.songCount.takeIf { it > 0 }?.let { pluralMeta(R.plurals.detail_meta_songs, it) },
        ),
    )
