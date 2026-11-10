package app.omnisonic.feature.library.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.feature.library.R
import app.omnisonic.feature.library.browse.BrowseUiState
import app.omnisonic.feature.library.browse.LibrarySection
import app.omnisonic.provider.api.CoverImageKey
import app.omnisonic.style.OmnisonicTheme
import java.text.NumberFormat

private const val LOAD_MORE_KEY = "load-more"

@Composable
private fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

@Composable
private fun tokenGridColumns(): Int = OmnisonicTheme.tokens.layout.gridColumnsFor(isLandscape())

/**
 * Library section scaffold (UI_SPEC.md §3.4): header row (title + item
 * count), then the loading/content/empty/error states. The [content] slot
 * renders the loaded page list (grid or song list) including the load-more
 * footer.
 */
@Composable
fun <T> LibrarySectionScreen(
    section: LibrarySection,
    state: BrowseUiState<T>,
    showGridPlaceholder: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (content: BrowseUiState.Content<T>) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Column(modifier = modifier.fillMaxSize()) {
        val loaded = state as? BrowseUiState.Content<T>
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.layout.pagePadding, vertical = tokens.spacing.md),
        ) {
            Text(
                text = stringResource(section.labelRes),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            loaded?.totalCount?.let { count ->
                Spacer(Modifier.width(tokens.spacing.sm))
                Text(
                    text = NumberFormat.getIntegerInstance().format(count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = tokens.spacing.xxs),
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (state) {
                BrowseUiState.Loading -> SectionPlaceholderBody(showGridPlaceholder)
                BrowseUiState.Empty -> SectionMessageBody(emptyCopy(section))
                BrowseUiState.Error -> SectionErrorBody(onRetry)
                is BrowseUiState.Content -> content(state)
            }
        }
    }
}

@Composable
private fun emptyCopy(section: LibrarySection): String = stringResource(
    when (section) {
        LibrarySection.ALBUMS -> R.string.library_empty_albums
        LibrarySection.ARTISTS -> R.string.library_empty_artists
        LibrarySection.GENRES -> R.string.library_empty_genres
        LibrarySection.SONGS -> R.string.library_empty_songs
        LibrarySection.PLAYLISTS -> R.string.library_empty_playlists
    },
)

@Composable
private fun SectionMessageBody(text: String) {
    val tokens = OmnisonicTheme.tokens
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = tokens.layout.pagePadding),
        )
    }
}

@Composable
private fun SectionErrorBody(onRetry: () -> Unit) {
    val tokens = OmnisonicTheme.tokens
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.md),
            modifier = Modifier.padding(horizontal = tokens.layout.pagePadding),
        ) {
            Text(
                text = stringResource(R.string.library_error_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.library_retry))
            }
        }
    }
}

/** Token-sized stand-in grid/list shown while the first page loads. */
@Composable
private fun SectionPlaceholderBody(showGrid: Boolean) {
    val tokens = OmnisonicTheme.tokens
    if (showGrid) {
        val columns = tokenGridColumns()
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(
                horizontal = tokens.layout.pagePadding,
                vertical = tokens.spacing.md,
            ),
            horizontalArrangement = Arrangement.spacedBy(tokens.layout.gridSpacing),
            verticalArrangement = Arrangement.spacedBy(tokens.layout.gridSpacing),
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
        ) {
            items((0 until columns * 3).toList(), key = { it }) {
                Box(
                    modifier = Modifier
                        .aspectRatio(tokens.imagery.gridAspectRatio)
                        .placeholderBackground(),
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(
                horizontal = tokens.layout.pagePadding,
                vertical = tokens.spacing.md,
            ),
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
        ) {
            items((0 until 6).toList(), key = { it }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tokens.layout.listRowMinHeight)
                        .padding(vertical = tokens.spacing.sm),
                ) {
                    Box(
                        modifier = Modifier
                            .size(tokens.imagery.listThumbSize)
                            .placeholderBackground(),
                    )
                    Spacer(Modifier.width(tokens.spacing.md))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(tokens.spacing.lg)
                            .placeholderBackground(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Modifier.placeholderBackground(): Modifier {
    val tokens = OmnisonicTheme.tokens
    return background(
        tokens.colors.coverPlaceholder.copy(alpha = tokens.imagery.placeholderTintAlpha),
        shape = MaterialTheme.shapes.small,
    )
}

/** Cover grid for albums/artists/genres/playlists with the load-more footer. */
@Composable
fun <T> CoverGridBody(
    content: BrowseUiState.Content<T>,
    itemKey: (T) -> Long,
    cover: ((T) -> CoverImageKey?)?,
    title: (T) -> String,
    subtitle: ((T) -> String?)?,
    titleInsideTile: (T) -> Boolean = { false },
    onClick: ((T) -> Unit)?,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    LazyVerticalGrid(
        columns = GridCells.Fixed(tokenGridColumns()),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = tokens.layout.pagePadding,
            vertical = tokens.spacing.md,
        ),
        horizontalArrangement = Arrangement.spacedBy(tokens.layout.gridSpacing),
        verticalArrangement = Arrangement.spacedBy(tokens.layout.gridSpacing),
    ) {
        items(content.items, key = itemKey) { item ->
            val tileClick: (() -> Unit)? = onClick?.let { callback -> { callback(item) } }
            CoverTile(
                cover = cover?.invoke(item),
                title = title(item),
                subtitle = subtitle?.invoke(item),
                titleInsideTile = titleInsideTile(item),
                onClick = tileClick,
            )
        }
        if (content.hasMore) {
            item(key = LOAD_MORE_KEY, span = { GridItemSpan(maxLineSpan) }) {
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
private fun CoverTile(
    cover: CoverImageKey?,
    title: String,
    subtitle: String?,
    titleInsideTile: Boolean,
    onClick: (() -> Unit)?,
) {
    val tokens = OmnisonicTheme.tokens
    val tileModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(modifier = tileModifier) {
        if (titleInsideTile) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .aspectRatio(tokens.imagery.gridAspectRatio)
                    .placeholderBackground(),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = OmnisonicTheme.tokens.colors.onCoverPlaceholder,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(tokens.spacing.md),
                )
            }
        } else {
            CoverImage(
                cover = cover,
                contentDescription = null,
                modifier = Modifier.aspectRatio(tokens.imagery.gridAspectRatio),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = tokens.spacing.xs),
        )
        subtitle?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Alphabetical song list with the load-more footer. */
@Composable
fun SongListBody(
    content: BrowseUiState.Content<SongEntity>,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = tokens.layout.pagePadding,
            vertical = tokens.spacing.md,
        ),
    ) {
        items(content.items, key = { it.id }) { song ->
            SongRow(song)
        }
        if (content.hasMore) {
            item(key = LOAD_MORE_KEY) {
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
private fun SongRow(song: SongEntity) {
    val tokens = OmnisonicTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = tokens.layout.listRowMinHeight)
            .padding(vertical = tokens.spacing.sm),
    ) {
        CoverImage(
            cover = song.imagePath?.let { CoverImageKey(song.providerId, it) },
            contentDescription = null,
            modifier = Modifier.size(tokens.imagery.listThumbSize),
        )
        Spacer(Modifier.width(tokens.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            song.displayArtist?.let { artist ->
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        song.durationMs?.let { duration ->
            Spacer(Modifier.width(tokens.spacing.sm))
            Text(
                text = formatDuration(duration),
                style = OmnisonicTheme.tokens.typography.caption.toTextStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Load-more footer shared with the detail bodies (ui/Common.kt) — kept as a
 * thin local alias so the paging contract stays one implementation.
 */

