package app.omnisonic.feature.settings.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.core.data.sync.LibrarySyncPhase
import app.omnisonic.feature.settings.R
import app.omnisonic.style.OmnisonicTheme

/**
 * Second onboarding destination (D-16): live walk progress with a phase list,
 * Continue once the library is in, and Retry / Continue-anyway after a failure.
 * The walk itself is owned by the launcher, so leaving this screen never stops
 * it.
 */
@Composable
fun LibrarySyncStepRoute(
    viewModel: LibrarySyncStepViewModel,
    onComplete: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is LibrarySyncStepEvent.Completed) onComplete()
        }
    }
    LibrarySyncStepScreen(
        state = state,
        onRetry = viewModel::retry,
        onContinue = viewModel::continueIntoApp,
    )
}

@Composable
fun LibrarySyncStepScreen(
    state: LibrarySyncStepUiState,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val spacing = tokens.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.library_sync_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.library_sync_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            when {
                !state.loaded || (state.providerId != null && state.phase == null && !state.recordedComplete) ->
                    Preparing()

                state.isRunning -> WalkProgress(state)
                state.isComplete -> ResultLine(stringResource(R.string.library_sync_done))
                state.isFailed -> ResultLine(failureMessage(state.phase))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            if (state.isFailed) {
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.library_sync_retry))
                }
            }
            if (state.canContinue) {
                Button(onClick = onContinue) {
                    Text(
                        stringResource(
                            if (state.isFailed) {
                                R.string.library_sync_continue_anyway
                            } else {
                                R.string.library_sync_continue
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Preparing() {
    val tokens = OmnisonicTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(tokens.imagery.uiIconSize))
        Text(
            text = stringResource(R.string.library_sync_preparing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WalkProgress(state: LibrarySyncStepUiState) {
    val tokens = OmnisonicTheme.tokens
    val spacing = tokens.spacing
    val current = state.phase
    val currentIndex = LibrarySyncWalkPhases.indexOf(current)
    val fraction = state.progress?.fraction

    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    LibrarySyncWalkPhases.forEachIndexed { index, phase ->
        val done = currentIndex > index
        val active = currentIndex == index
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (done) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(tokens.imagery.uiIconSize),
                )
            } else if (active) {
                CircularProgressIndicator(modifier = Modifier.size(tokens.imagery.uiIconSize))
            } else {
                Box(modifier = Modifier.size(tokens.imagery.uiIconSize))
            }
            Text(
                text = phase.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (done || active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            if (active && state.progress != null && state.progress.itemsTotal > 0) {
                Text(
                    text = stringResource(
                        R.string.library_sync_count,
                        state.progress.itemsDone,
                        state.progress.itemsTotal,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResultLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun failureMessage(phase: LibrarySyncPhase?): String = stringResource(
    when (phase) {
        LibrarySyncPhase.BLOCKED -> R.string.library_sync_blocked
        LibrarySyncPhase.SKIPPED -> R.string.library_sync_skipped
        else -> R.string.library_sync_failed
    },
)
