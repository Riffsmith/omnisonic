package app.omnisonic.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.omnisonic.core.data.db.entity.PlaylistEntryEntity
import app.omnisonic.core.data.db.entity.SongEntity
import app.omnisonic.feature.library.R
import app.omnisonic.feature.library.browse.BrowseUiState
import app.omnisonic.provider.api.CoverImageKey
import app.omnisonic.style.OmnisonicTheme

/**
 * Detail header (UI_SPEC.md §3.5): art/placeholder, title, subtitle, meta
 * line, play + shuffle. Sizes and colors come from tokens only; the
 * favorite action has no write path yet and stays out until the favorites
 * phase ships it.
 */
@Composable
internal fun DetailHeader(
    cover: CoverImageKey?,
    title: String,
    subtitle: String?,
    meta: String?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = tokens.layout.pagePadding,
                vertical = tokens.spacing.md,
            ),
        ) {
            CoverImage(
                cover = cover,
                contentDescription = null,
                modifier = Modifier.size(tokens.imagery.detailArtSize),
            )
            Spacer(Modifier.width(tokens.spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = tokens.spacing.xs),
                    )
                }
                meta?.let { text ->
                    Text(
                        text = text,
                        style = OmnisonicTheme.tokens.typography.caption.toTextStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = tokens.spacing.xs),
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            modifier = Modifier.padding(horizontal = tokens.layout.pagePadding),
        ) {
            DetailActionButton(
                label = stringResource(R.string.detail_play),
                onClick = onPlay,
            )
            DetailActionButton(
                label = stringResource(R.string.detail_shuffle),
                onClick = onShuffle,
            )
        }
    }
}

@Composable
private fun DetailActionButton(
    label: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick) {
        Text(label)
    }
}

@Composable
internal fun DetailLoadingBody() {
    TrackPlaceholders()
}

/** Deleted on the server (UI_SPEC.md §3.5 empty state): friendly copy + back. */
@Composable
internal fun DetailMissingBody(onBack: () -> Unit) {
    val tokens = OmnisonicTheme.tokens
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.md),
            modifier = Modifier.padding(horizontal = tokens.layout.pagePadding),
        ) {
            Text(
                text = stringResource(R.string.detail_missing_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.detail_missing_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onBack) {
                Text(stringResource(R.string.detail_back))
            }
        }
    }
}

@Composable
internal fun DetailErrorBody(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.md),
            modifier = Modifier.padding(horizontal = tokens.layout.pagePadding),
        ) {
            Text(
                text = stringResource(R.string.detail_error_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.library_retry))
            }
        }
    }
}

@Composable
internal fun DetailEmptyTracksBody(modifier: Modifier = Modifier) {
    DetailMessageBody(
        text = stringResource(R.string.detail_body_empty),
        modifier = modifier,
    )
}

@Composable
internal fun DetailMessageBody(
    text: String,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = tokens.layout.pagePadding),
        )
    }
}

/** Static placeholder rows for a loading detail body (not scrollable by itself). */
@Composable
internal fun TrackPlaceholders(modifier: Modifier = Modifier) {
    val tokens = OmnisonicTheme.tokens
    Column(modifier = modifier.fillMaxSize()) {
        repeat(PLACEHOLDER_ROW_COUNT) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = tokens.layout.listRowMinHeight)
                    .padding(
                        horizontal = tokens.layout.pagePadding,
                        vertical = tokens.spacing.sm,
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .size(tokens.imagery.listThumbSize)
                        .detailPlaceholderBackground(),
                )
                Spacer(Modifier.width(tokens.spacing.md))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = tokens.spacing.lg)
                        .detailPlaceholderBackground(),
                )
            }
        }
    }
}

private const val PLACEHOLDER_ROW_COUNT = 6


/**
 * Plural meta fragments for the detail meta line ("12 songs", "3 albums").
 */
@Composable
internal fun pluralMeta(pluralRes: Int, count: Int): String =
    pluralStringResource(pluralRes, count, count)

internal fun metaLine(parts: List<String?>): String? =
    parts.filterNotNull().filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(META_SEPARATOR)

private const val META_SEPARATOR = " · "


@Composable
private fun Modifier.detailPlaceholderBackground(): Modifier {
    val tokens = OmnisonicTheme.tokens
    return background(
        tokens.colors.coverPlaceholder.copy(alpha = tokens.imagery.placeholderTintAlpha),
        shape = MaterialTheme.shapes.small,
    )
}

/** Paged track list; a row tap plays from that row (UI_SPEC.md §3.5 actions). */
@Composable
internal fun TrackListBody(
    content: BrowseUiState.Content<SongEntity>,
    onPlayFrom: (Int) -> Unit,
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
        itemsIndexed(content.items, key = { _, song -> song.id }) { index, song ->
            TrackRow(song = song, onClick = { onPlayFrom(index) })
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
internal fun TrackRow(
    song: SongEntity,
    onClick: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = tokens.layout.listRowMinHeight)
            .clickable(onClick = onClick)
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

/** Playlist entry list in `position` order (UI_SPEC.md §3.5 body). */
@Composable
internal fun EntryListBody(
    content: BrowseUiState.Content<PlaylistEntryEntity>,
    onPlayFrom: (Int) -> Unit,
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
        itemsIndexed(content.items, key = { _, entry -> entry.id }) { index, entry ->
            EntryRow(entry = entry, onClick = { onPlayFrom(index) })
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
internal fun EntryRow(
    entry: PlaylistEntryEntity,
    onClick: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = tokens.layout.listRowMinHeight)
            .clickable(onClick = onClick)
            .padding(vertical = tokens.spacing.sm),
    ) {
        CoverImage(
            cover = entry.imagePath?.let { CoverImageKey(entry.providerId, it) },
            contentDescription = null,
            modifier = Modifier.size(tokens.imagery.listThumbSize),
        )
        Spacer(Modifier.width(tokens.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.displayArtist?.let { artist ->
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        entry.durationMs?.let { duration ->
            Spacer(Modifier.width(tokens.spacing.sm))
            Text(
                text = formatDuration(duration),
                style = OmnisonicTheme.tokens.typography.caption.toTextStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
