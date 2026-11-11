package app.omnisonic.feature.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.feature.library.R
import app.omnisonic.feature.library.nowplaying.NowPlayingUiState
import app.omnisonic.feature.library.nowplaying.NowPlayingViewModel
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.style.BackdropSurface
import app.omnisonic.style.GestureTwin
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.gestureTwins
import kotlin.math.abs

@Composable
fun NowPlayingRoute(
    viewModel: NowPlayingViewModel,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NowPlayingScreen(
        state = state,
        onTogglePlayPause = viewModel::togglePlayPause,
        onNext = viewModel::next,
        onPrevious = viewModel::previous,
        onSeek = viewModel::seekTo,
        onToggleShuffle = viewModel::toggleShuffle,
        onCycleRepeat = viewModel::cycleRepeat,
        onCollapse = onCollapse,
        onOpenQueue = onOpenQueue,
        onOpenLibrary = onOpenLibrary,
    )
}

/**
 * Expanded now-playing layout (UI_SPEC.md §3.8). State arrives exclusively
 * through [NowPlayingUiState] (mapped from the playback controller); the
 * "Subtle" backdrop stack comes from tokens. Every art gesture registers a
 * button twin (UI_SPEC.md §1.3): tap ↔ play/pause, horizontal drag ↔
 * previous/next, drag up ↔ queue button, drag down ↔ collapse button.
 */
@Composable
fun NowPlayingScreen(
    state: NowPlayingUiState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    BackdropSurface(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            if (state.isEmpty) {
                NowPlayingEmpty(onOpenLibrary = onOpenLibrary)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = tokens.layout.pagePadding),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = tokens.layout.listRowMinHeight),
                    ) {
                        IconButton(onClick = onCollapse) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.now_playing_collapse),
                            )
                        }
                        Spacer(Modifier.width(tokens.spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.entry?.title.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            state.entry?.artistName?.let { artist ->
                                Text(
                                    text = artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(1f),
                    ) {
                        NowPlayingArt(
                            state = state,
                            onTogglePlayPause = onTogglePlayPause,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            onCollapse = onCollapse,
                            onOpenQueue = onOpenQueue,
                        )
                        Spacer(Modifier.height(tokens.spacing.lg))
                        Text(
                            text = state.entry?.title.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.entry?.artistName?.let { artist ->
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = tokens.spacing.xs),
                            )
                        }
                    }
                    SeekRow(state = state, onSeek = onSeek)
                    TransportRow(
                        state = state,
                        onTogglePlayPause = onTogglePlayPause,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                    )
                    SecondaryRow(onOpenQueue = onOpenQueue)
                }
            }
        }
    }
}

@Composable
private fun NowPlayingArt(
    state: NowPlayingUiState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val playPauseLabel = stringResource(R.string.now_playing_play_pause)
    val previousLabel = stringResource(R.string.now_playing_previous)
    val nextLabel = stringResource(R.string.now_playing_next)
    val queueLabel = stringResource(R.string.now_playing_queue)
    val collapseLabel = stringResource(R.string.now_playing_collapse)
    val twins = remember(playPauseLabel, previousLabel, nextLabel, queueLabel, collapseLabel) {
        listOf(
            GestureTwin(GESTURE_ART_TAP, playPauseLabel, playPauseLabel),
            GestureTwin(GESTURE_ART_PREVIOUS, previousLabel, previousLabel),
            GestureTwin(GESTURE_ART_NEXT, nextLabel, nextLabel),
            GestureTwin(GESTURE_ART_QUEUE, queueLabel, queueLabel),
            GestureTwin(GESTURE_ART_COLLAPSE, collapseLabel, collapseLabel),
        )
    }
    val performTwin: (String) -> Unit = { gestureId ->
        when (gestureId) {
            GESTURE_ART_TAP -> onTogglePlayPause()
            GESTURE_ART_PREVIOUS -> onPrevious()
            GESTURE_ART_NEXT -> onNext()
            GESTURE_ART_QUEUE -> onOpenQueue()
            GESTURE_ART_COLLAPSE -> onCollapse()
        }
    }
    val dragThresholdPx = with(LocalDensity.current) { tokens.spacing.xxl.toPx() }
    Box(
        modifier = Modifier
            .size(tokens.imagery.nowPlayingArtSize)
            .gestureTwins(twins, performTwin)
            .pointerInput(Unit) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = {
                        dx = 0f
                        dy = 0f
                    },
                    onDrag = { _, amount ->
                        dx += amount.x
                        dy += amount.y
                    },
                    onDragEnd = {
                        when {
                            abs(dx) >= abs(dy) && abs(dx) >= dragThresholdPx ->
                                if (dx > 0f) onNext() else onPrevious()
                            abs(dy) > abs(dx) && abs(dy) >= dragThresholdPx ->
                                if (dy < 0f) onOpenQueue() else onCollapse()
                        }
                    },
                )
            }
            .semantics {
                contentDescription = playPauseLabel
                role = Role.Button
            }
            .clickable(onClick = onTogglePlayPause),
    ) {
        CoverImage(
            cover = state.entry?.cover,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SeekRow(
    state: NowPlayingUiState,
    onSeek: (Long) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val duration = state.durationMs ?: 0L
    val canSeek = duration > 0L
    var scrubMs by remember(state.entry?.queueItemId) { mutableStateOf<Float?>(null) }
    val shownMs = scrubMs ?: state.positionMs.toFloat()
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = shownMs.coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
            onValueChange = { if (canSeek) scrubMs = it },
            onValueChangeFinished = {
                scrubMs?.let { onSeek(it.toLong()) }
                scrubMs = null
            },
            enabled = canSeek,
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = tokens.colors.progressActive,
                activeTrackColor = tokens.colors.progressActive,
                inactiveTrackColor = tokens.colors.progressTrack,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = formatDuration(shownMs.toLong().coerceAtLeast(0L)),
                style = tokens.typography.caption.toTextStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration((duration - shownMs.toLong()).coerceAtLeast(0L)),
                style = tokens.typography.caption.toTextStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransportRow(
    state: NowPlayingUiState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.sm),
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = stringResource(R.string.now_playing_shuffle),
                tint = if (state.shuffleEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.now_playing_previous),
            )
        }
        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(tokens.layout.listRowMinHeight),
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.now_playing_play_pause),
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.now_playing_next),
            )
        }
        IconButton(onClick = onCycleRepeat) {
            Icon(
                imageVector = if (state.repeatMode == RepeatMode.ONE) {
                    Icons.Filled.RepeatOne
                } else {
                    Icons.Filled.Repeat
                },
                contentDescription = if (state.repeatMode == RepeatMode.ONE) {
                    stringResource(R.string.now_playing_repeat_one)
                } else {
                    stringResource(R.string.now_playing_repeat)
                },
                tint = if (state.repeatMode == RepeatMode.OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun SecondaryRow(onOpenQueue: () -> Unit) {
    val tokens = OmnisonicTheme.tokens
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = tokens.spacing.sm),
    ) {
        IconButton(onClick = onOpenQueue) {
            Icon(
                imageVector = Icons.Filled.QueueMusic,
                contentDescription = stringResource(R.string.now_playing_queue),
            )
        }
        Spacer(Modifier.width(tokens.spacing.lg))
        IconButton(onClick = {}, enabled = false) {
            Icon(
                imageVector = Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(R.string.now_playing_favorite),
            )
        }
    }
}

@Composable
private fun NowPlayingEmpty(onOpenLibrary: () -> Unit) {
    val tokens = OmnisonicTheme.tokens
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.md),
            modifier = Modifier.padding(horizontal = tokens.layout.pagePadding),
        ) {
            Text(
                text = stringResource(R.string.now_playing_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.now_playing_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenLibrary) {
                Text(stringResource(R.string.now_playing_open_library))
            }
        }
    }
}

private const val GESTURE_ART_TAP = "art-tap"
private const val GESTURE_ART_PREVIOUS = "art-previous"
private const val GESTURE_ART_NEXT = "art-next"
private const val GESTURE_ART_QUEUE = "art-queue"
private const val GESTURE_ART_COLLAPSE = "art-collapse"
