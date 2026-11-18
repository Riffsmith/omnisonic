package app.omnisonic.feature.library.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.db.RecentPlayRow
import app.omnisonic.feature.library.R
import app.omnisonic.feature.library.browse.BrowseUiState
import app.omnisonic.feature.library.history.HistoryViewModel
import app.omnisonic.provider.api.CoverImageKey
import app.omnisonic.style.OmnisonicTheme
import java.text.NumberFormat

private const val LOAD_MORE_KEY = "load-more"

/**
 * Recent-plays destination: header (title + play count), then the paged
 * newest-first list with its load-more footer, plus loading/empty/error
 * states. Rows are display-only here; the history controls land in the
 * next task.
 */
@Composable
fun HistoryRoute(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    HistoryScreen(
        state = state,
        onRetry = viewModel::load,
        onLoadMore = viewModel::loadMore,
        onScrollInProgress = viewModel::setScrolling,
        modifier = modifier,
    )
}

@Composable
fun HistoryScreen(
    state: BrowseUiState<RecentPlayRow>,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onScrollInProgress: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    Column(modifier = modifier.fillMaxSize()) {
        val loaded = state as? BrowseUiState.Content<RecentPlayRow>
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.layout.pagePadding, vertical = tokens.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.history_title),
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
                BrowseUiState.Loading -> TrackPlaceholders()
                BrowseUiState.Empty -> DetailMessageBody(stringResource(R.string.history_empty))
                BrowseUiState.Error -> DetailErrorBody(onRetry)
                is BrowseUiState.Content -> HistoryListBody(
                    content = state,
                    onLoadMore = onLoadMore,
                    onScrollInProgress = onScrollInProgress,
                )
            }
        }
    }
}

@Composable
private fun HistoryListBody(
    content: BrowseUiState.Content<RecentPlayRow>,
    onLoadMore: () -> Unit,
    onScrollInProgress: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    val listState = rememberLazyListState()
    ScrollGateReporter(listState, onScrollInProgress)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = tokens.layout.pagePadding,
            vertical = tokens.spacing.md,
        ),
    ) {
        items(content.items, key = { it.eventId }) { row ->
            HistoryRow(row = row)
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
private fun HistoryRow(row: RecentPlayRow) {
    val tokens = OmnisonicTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = tokens.layout.listRowMinHeight)
            .padding(vertical = tokens.spacing.sm),
    ) {
        CoverImage(
            cover = row.imagePath?.let { CoverImageKey(row.providerId, it) },
            contentDescription = null,
            modifier = Modifier.size(tokens.imagery.listThumbSize),
        )
        Spacer(Modifier.width(tokens.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.songTitle ?: row.externalId,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = metaLine(
                listOf(
                    row.takeIf { it.skipped }?.let { stringResource(R.string.history_skipped) },
                    row.displayArtist,
                    row.albumTitle,
                ),
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
        Spacer(Modifier.width(tokens.spacing.sm))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatPlayedAt(row.playedAtMs),
                style = tokens.typography.caption.toTextStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = formatDuration(row.durationMs),
                style = tokens.typography.caption.toTextStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.End,
            )
        }
    }
}
