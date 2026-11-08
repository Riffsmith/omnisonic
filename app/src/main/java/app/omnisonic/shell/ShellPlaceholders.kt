package app.omnisonic.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.omnisonic.R
import app.omnisonic.style.OmnisonicTheme

/**
 * Phase 5 placeholder screens for routes whose content lands in later tasks
 * (home content = Phase 9; library/search/detail/now-playing/queue/tasks 4-7).
 * Home follows the UI_SPEC.md §3.3 empty state.
 */
@Composable
internal fun PlaceholderPane(text: String, modifier: Modifier = Modifier) {
    val tokens = OmnisonicTheme.tokens
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = tokens.spacing.lg),
        )
    }
}

@Composable
internal fun HomePlaceholder(hasProvider: Boolean, onSetup: () -> Unit) {
    val tokens = OmnisonicTheme.tokens
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.home_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = tokens.spacing.lg),
            )
            if (!hasProvider) {
                Button(onClick = onSetup) {
                    Text(stringResource(R.string.home_empty_action))
                }
            }
        }
    }
}

@Composable
internal fun DetailPlaceholder() {
    PlaceholderPane(stringResource(R.string.placeholder_screen_body))
}
