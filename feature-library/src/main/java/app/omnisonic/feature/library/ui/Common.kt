package app.omnisonic.feature.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.omnisonic.feature.library.R
import app.omnisonic.style.OmnisonicTheme

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Load-more footer: doubles as the paging trigger — when it first enters
 * composition with more pages pending it requests the next page once; a failed
 * append swaps the spinner for an inline retry (UI_SPEC.md §3.4 paging). Never
 * keyed on the append state: that re-fired on every completed append and
 * chained all remaining pages (task 4d). Shared by the library sections and
 * the detail bodies.
 */
@Composable
internal fun LoadMoreFooter(
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
                text = stringResource(R.string.library_load_more_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(tokens.spacing.sm))
            TextButton(onClick = onLoadMore) {
                Text(stringResource(R.string.library_retry))
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(tokens.imagery.uiIconSize),
                strokeWidth = tokens.spacing.xs,
            )
        }
    }
}
