package app.omnisonic.feature.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.db.entity.AlbumEntity
import app.omnisonic.core.data.db.entity.ArtistEntity
import app.omnisonic.core.data.db.entity.GenreEntity
import app.omnisonic.core.data.db.entity.PlaylistEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.core.data.search.SearchCategory
import app.omnisonic.feature.search.R
import app.omnisonic.feature.search.search.SearchPhase
import app.omnisonic.feature.search.search.SearchUiState
import app.omnisonic.feature.search.search.SearchViewModel
import app.omnisonic.provider.api.CoverImageKey
import app.omnisonic.style.FilterChipLayout
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.SearchPageConfig
import coil3.compose.AsyncImage

/**
 * Search tab entry (UI_SPEC.md §3.6). Result taps hand off to the shell's
 * route callbacks; `:feature-search` never references shell route strings.
 */
@Composable
fun SearchRoute(
    viewModel: SearchViewModel,
    config: SearchPageConfig = SearchPageConfig.DEFAULT,
    isActive: Boolean = true,
    onOpenAlbum: (Long) -> Unit = {},
    onOpenArtist: (Long) -> Unit = {},
    onOpenGenre: (Long) -> Unit = {},
    onOpenPlaylist: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchScreen(
        state = state,
        config = config,
        isActive = isActive,
        onQueryChange = viewModel::onQueryChange,
        onSubmit = viewModel::submit,
        onClearQuery = viewModel::clearQuery,
        onToggleCategory = viewModel::toggleCategory,
        onRecentSearch = viewModel::selectRecentSearch,
        onRemoveRecentSearch = viewModel::removeRecentSearch,
        onClearRecentSearches = viewModel::clearRecentSearches,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onOpenAlbum = onOpenAlbum,
        onOpenArtist = onOpenArtist,
        onOpenGenre = onOpenGenre,
        onOpenPlaylist = onOpenPlaylist,
        modifier = modifier,
    )
}

@Composable
fun SearchScreen(
    state: SearchUiState,
    config: SearchPageConfig,
    isActive: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClearQuery: () -> Unit,
    onToggleCategory: (SearchCategory) -> Unit,
    onRecentSearch: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit,
    onOpenGenre: (Long) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isActive) {
        if (isActive && config.autofocusOnEntry && state.phase == SearchPhase.IDLE) {
            focusRequester.requestFocus()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = onClearQuery) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.search_clear),
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    onSubmit()
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = tokens.layout.pagePadding,
                    end = tokens.layout.pagePadding,
                    top = tokens.spacing.md,
                )
                .focusRequester(focusRequester),
        )

        CategoryChips(
            enabled = state.enabledCategories,
            layout = config.chipLayout,
            onToggle = onToggleCategory,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when (state.phase) {
                SearchPhase.IDLE -> IdleBody(
                    state = state,
                    showRecent = config.showRecentSearches,
                    onRecentSearch = onRecentSearch,
                    onRemoveRecentSearch = onRemoveRecentSearch,
                    onClearRecentSearches = onClearRecentSearches,
                )

                SearchPhase.SEARCHING -> CenteredBody {
                    CircularProgressIndicator(modifier = Modifier.size(tokens.imagery.uiIconSize))
                }

                SearchPhase.ERROR -> CenteredBody {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.search_error_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.size(tokens.spacing.sm))
                        Button(onClick = onRetry) {
                            Text(stringResource(R.string.search_retry))
                        }
                    }
                }

                SearchPhase.NO_RESULTS -> CenteredBody {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(
                                R.string.search_no_results_title,
                                state.submittedQuery,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.size(tokens.spacing.xs))
                        Text(
                            text = stringResource(R.string.search_no_results_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                SearchPhase.RESULTS -> ResultsBody(
                    state = state,
                    onLoadMore = onLoadMore,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onOpenGenre = onOpenGenre,
                    onOpenPlaylist = onOpenPlaylist,
                )
            }
        }
    }
}

@Composable
private fun CategoryChips(
    enabled: Set<SearchCategory>,
    layout: FilterChipLayout,
    onToggle: (SearchCategory) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val chips: @Composable () -> Unit = {
        SearchCategory.entries.forEach { category ->
            FilterChip(
                selected = category in enabled,
                onClick = { onToggle(category) },
                label = { Text(stringResource(category.labelRes)) },
            )
        }
    }
    when (layout) {
        FilterChipLayout.SINGLE_LINE -> LazyRow(
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            contentPadding = PaddingValues(
                horizontal = tokens.layout.pagePadding,
                vertical = tokens.spacing.sm,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)) {
                    chips()
                }
            }
        }

        FilterChipLayout.WRAPPED -> WrappedChips(
            padding = PaddingValues(
                horizontal = tokens.layout.pagePadding,
                vertical = tokens.spacing.sm,
            ),
        ) { chips() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrappedChips(padding: PaddingValues, content: @Composable () -> Unit) {
    val tokens = OmnisonicTheme.tokens
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
        content = { content() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(padding),
    )
}

@Composable
private fun IdleBody(
    state: SearchUiState,
    showRecent: Boolean,
    onRecentSearch: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    if (!showRecent || state.recentSearches.isEmpty()) {
        CenteredBody {
            Text(
                text = stringResource(R.string.search_idle_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = tokens.layout.pagePadding),
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = tokens.layout.pagePadding,
            end = tokens.layout.pagePadding,
            bottom = tokens.spacing.xl,
        ),
    ) {
        item(key = "recent-header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.md, bottom = tokens.spacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.search_recent_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearRecentSearches) {
                    Text(stringResource(R.string.search_recent_clear))
                }
            }
        }
        items(state.recentSearches, key = { it }) { recent ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = tokens.layout.listRowMinHeight)
                    .clickable { onRecentSearch(recent) },
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(tokens.imagery.uiIconSize),
                )
                Spacer(Modifier.width(tokens.spacing.md))
                Text(
                    text = recent,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemoveRecentSearch(recent) }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.search_recent_remove, recent),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsBody(
    state: SearchUiState,
    onLoadMore: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit,
    onOpenGenre: (Long) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val results = state.results ?: return
    val thumb = tokens.imagery.listThumbSize

    LazyColumn(
        contentPadding = PaddingValues(
            start = tokens.layout.pagePadding,
            end = tokens.layout.pagePadding,
            bottom = tokens.spacing.xl,
        ),
    ) {
        if (results.albums.items.isNotEmpty()) {
            item(key = "header-albums") { SectionHeader(stringResource(R.string.search_category_albums)) }
            items(results.albums.items, key = { "album-${it.id}" }) { album ->
                AlbumRow(album, thumb, onClick = { onOpenAlbum(album.id) })
            }
        }
        if (results.artists.items.isNotEmpty()) {
            item(key = "header-artists") { SectionHeader(stringResource(R.string.search_category_artists)) }
            items(results.artists.items, key = { "artist-${it.id}" }) { artist ->
                ArtistRow(artist, thumb, onClick = { onOpenArtist(artist.id) })
            }
        }
        if (results.songs.items.isNotEmpty()) {
            item(key = "header-songs") { SectionHeader(stringResource(R.string.search_category_songs)) }
            items(results.songs.items, key = { "song-${it.id}" }) { song ->
                SongRow(
                    song = song,
                    thumb = thumb,
                    onClick = {
                        val albumId = song.albumId
                        if (albumId != null) onOpenAlbum(albumId)
                    },
                )
            }
        }
        if (results.genres.items.isNotEmpty()) {
            item(key = "header-genres") { SectionHeader(stringResource(R.string.search_category_genres)) }
            items(results.genres.items, key = { "genre-${it.id}" }) { genre ->
                GenreRow(genre, thumb, onClick = { onOpenGenre(genre.id) })
            }
        }
        if (results.playlists.items.isNotEmpty()) {
            item(key = "header-playlists") {
                SectionHeader(stringResource(R.string.search_category_playlists))
            }
            items(results.playlists.items, key = { "playlist-${it.id}" }) { playlist ->
                PlaylistRow(playlist, thumb, onClick = { onOpenPlaylist(playlist.id) })
            }
        }
        if (state.hasMore || state.appending || state.appendFailed) {
            item(key = "load-more") {
                SearchLoadMoreFooter(
                    appending = state.appending,
                    appendFailed = state.appendFailed,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

/**
 * Doubles as the paging trigger: the next window is requested when the footer
 * enters composition. Like the library footer, it leaves composition once the
 * appended rows push it below the viewport, so a further page is requested on
 * the next scroll rather than chained eagerly.
 */
@Composable
private fun SearchLoadMoreFooter(
    appending: Boolean,
    appendFailed: Boolean,
    onLoadMore: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    LaunchedEffect(Unit) {
        if (!appending && !appendFailed) onLoadMore()
    }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.lg),
    ) {
        if (appendFailed) {
            Text(
                text = stringResource(R.string.search_load_more_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(tokens.spacing.sm))
            TextButton(onClick = onLoadMore) {
                Text(stringResource(R.string.search_retry))
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(tokens.imagery.uiIconSize),
                strokeWidth = tokens.spacing.xs,
            )
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    val tokens = OmnisonicTheme.tokens
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = tokens.spacing.lg, bottom = tokens.spacing.xs),
    )
}

@Composable
private fun AlbumRow(album: AlbumEntity, thumb: Dp, onClick: () -> Unit) {
    ResultRow(
        cover = album.imagePath?.let { CoverImageKey(album.providerId, it) },
        placeholderIcon = Icons.Filled.Album,
        title = album.title,
        subtitle = album.displayArtist,
        thumb = thumb,
        onClick = onClick,
    )
}

@Composable
private fun ArtistRow(artist: ArtistEntity, thumb: Dp, onClick: () -> Unit) {
    ResultRow(
        cover = artist.imagePath?.let { CoverImageKey(artist.providerId, it) },
        placeholderIcon = Icons.Filled.Person,
        title = artist.title,
        subtitle = null,
        thumb = thumb,
        onClick = onClick,
    )
}

@Composable
private fun SongRow(song: SongEntity, thumb: Dp, onClick: () -> Unit) {
    ResultRow(
        cover = song.imagePath?.let { CoverImageKey(song.providerId, it) },
        placeholderIcon = Icons.Filled.MusicNote,
        title = song.title,
        subtitle = song.displayArtist,
        thumb = thumb,
        onClick = onClick,
    )
}

@Composable
private fun GenreRow(genre: GenreEntity, thumb: Dp, onClick: () -> Unit) {
    ResultRow(
        cover = null,
        placeholderIcon = Icons.Filled.LibraryMusic,
        title = genre.title,
        subtitle = null,
        thumb = thumb,
        onClick = onClick,
    )
}

@Composable
private fun PlaylistRow(playlist: PlaylistEntity, thumb: Dp, onClick: () -> Unit) {
    ResultRow(
        cover = playlist.imagePath?.let { CoverImageKey(playlist.providerId, it) },
        placeholderIcon = Icons.Filled.PlaylistPlay,
        title = playlist.title,
        subtitle = null,
        thumb = thumb,
        onClick = onClick,
    )
}

@Composable
private fun ResultRow(
    cover: CoverImageKey?,
    placeholderIcon: ImageVector,
    title: String,
    subtitle: String?,
    thumb: Dp,
    onClick: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = tokens.layout.listRowMinHeight)
            .clickable(onClick = onClick)
            .padding(vertical = tokens.spacing.xs),
    ) {
        SearchCover(cover = cover, size = thumb, placeholderIcon = placeholderIcon)
        Spacer(Modifier.width(tokens.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Bounded cover request: the pixel size is bucketed so neighbouring rows share
 * one cache entry and no result ever asks the provider for original-size art
 * (D-17).
 */
@Composable
private fun SearchCover(cover: CoverImageKey?, size: Dp, placeholderIcon: ImageVector) {
    val tokens = OmnisonicTheme.tokens
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }
    val request = remember(cover, sizePx) {
        cover?.copy(sizePx = ((sizePx + 63) / 64 * 64).coerceAtMost(1024))
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(tokens.shapes.cover))
            .background(tokens.colors.coverPlaceholder),
    ) {
        Icon(
            imageVector = placeholderIcon,
            contentDescription = null,
            tint = tokens.colors.onCoverPlaceholder.copy(alpha = tokens.imagery.placeholderTintAlpha),
        )
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CenteredBody(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}

private val SearchCategory.labelRes: Int
    get() = when (this) {
        SearchCategory.ALBUMS -> R.string.search_category_albums
        SearchCategory.ARTISTS -> R.string.search_category_artists
        SearchCategory.SONGS -> R.string.search_category_songs
        SearchCategory.GENRES -> R.string.search_category_genres
        SearchCategory.PLAYLISTS -> R.string.search_category_playlists
    }
