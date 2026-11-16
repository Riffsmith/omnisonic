package app.omnisonic.feature.library.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import app.omnisonic.feature.library.nowplaying.LyricsUiState
import app.omnisonic.feature.library.nowplaying.NowPlayingPane
import app.omnisonic.feature.library.nowplaying.NowPlayingUiState
import app.omnisonic.feature.library.nowplaying.NowPlayingViewModel
import app.omnisonic.feature.library.player.PlayerSheetVelocity
import app.omnisonic.playback.api.queue.RepeatMode
import app.omnisonic.style.BackdropSurface
import app.omnisonic.style.GestureTwin
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.gestureTwins
import app.omnisonic.style.toEasing
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Live vertical drag callbacks for the player sheet the screen sits in
 * (ui/player-sheet.md). Delta and velocity are pixels, Compose-signed: an
 * upward drag is negative. When a host supplies them the screen no longer
 * decides the up/down actions itself — the sheet settles on release — so the
 * screen can be pulled by the finger instead of playing a fixed transition.
 */
data class PlayerSheetDrag(
    val onDrag: (deltaYPx: Float) -> Unit,
    val onRelease: (velocityYPx: Float) -> Unit,
)

@Composable
fun NowPlayingRoute(
    viewModel: NowPlayingViewModel,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLibrary: () -> Unit,
    sheetDrag: PlayerSheetDrag? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pane by viewModel.pane.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
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
        sheetDrag = sheetDrag,
        pane = pane,
        onTogglePane = viewModel::togglePane,
        lyrics = lyrics,
    )
}

/**
 * Expanded now-playing layout (UI_SPEC.md §3.8). State arrives exclusively
 * through [NowPlayingUiState] (mapped from the playback controller); the
 * "Subtle" backdrop stack comes from tokens. Every art gesture registers a
 * button twin (UI_SPEC.md §1.3): tap ↔ play/pause, horizontal drag ↔
 * next (right-to-left) / previous (left-to-right), drag up ↔ queue button,
 * drag down ↔ collapse button. The art follows horizontal drags up to 24 dp
 * and springs back (ui/player-ux-fixes.md §4.4).
 *
 * The art block is also the lyrics pane's home (D-23): [pane] swaps its content
 * between cover and lyrics with the `artCrossfade` token, and the secondary
 * row's lyrics button is the visible toggle. Lyrics content is a leaf, so the
 * sheet's vertical drag surface keeps working over it.
 *
 * Vertical drags belong to the player sheet ([sheetDrag]): any vertical drag
 * on this surface moves the sheet with the finger. Without a host (goldens,
 * previews) the same gesture falls back to the threshold actions.
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
    sheetDrag: PlayerSheetDrag? = null,
    pane: NowPlayingPane = NowPlayingPane.COVER,
    onTogglePane: () -> Unit = {},
    lyrics: LyricsUiState = LyricsUiState(),
) {
    val tokens = OmnisonicTheme.tokens
    val dragThresholdPx = with(LocalDensity.current) { tokens.spacing.xxl.toPx() }
    BackdropSurface(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .playerSheetDrag(
                    thresholdPx = dragThresholdPx,
                    sheetDrag = sheetDrag,
                    onOpenQueue = onOpenQueue,
                    onCollapse = onCollapse,
                ),
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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        val crossfade = tokens.motion.artCrossfade
                        Crossfade(
                            targetState = pane,
                            animationSpec = tween(
                                durationMillis = (crossfade.durationMs *
                                    tokens.motion.durationScale).roundToInt(),
                                easing = tokens.motion.easingProfile.toEasing(),
                            ),
                            label = "nowPlayingPane",
                        ) { shownPane ->
                            when (shownPane) {
                                NowPlayingPane.COVER -> NowPlayingArt(
                                    state = state,
                                    onTogglePlayPause = onTogglePlayPause,
                                    onNext = onNext,
                                    onPrevious = onPrevious,
                                    onCollapse = onCollapse,
                                    onOpenQueue = onOpenQueue,
                                )
                                NowPlayingPane.LYRICS -> LyricsPane(lyrics)
                            }
                        }
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
                    SecondaryRow(
                        onOpenQueue = onOpenQueue,
                        onTogglePane = onTogglePane,
                        lyricsShown = pane == NowPlayingPane.LYRICS,
                    )
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
    val maxNudgePx = with(LocalDensity.current) { tokens.spacing.xl.toPx() }
    val nudgeMotion = tokens.motion.nowPlayingExpand
    val nudgeSpec = remember(nudgeMotion) {
        spring<Float>(dampingRatio = nudgeMotion.dampingRatio, stiffness = nudgeMotion.stiffness)
    }
    var dragging by remember { mutableStateOf(false) }
    var nudgeTargetPx by remember { mutableFloatStateOf(0f) }
    val nudgePx by animateFloatAsState(
        targetValue = nudgeTargetPx,
        animationSpec = if (dragging) snap<Float>() else nudgeSpec,
        label = "artNudge",
    )
    Box(
        modifier = Modifier
            .size(tokens.imagery.nowPlayingArtSize)
            .gestureTwins(twins, performTwin)
            // Horizontal only: vertical drags belong to the player sheet.
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dx = 0f
                        dragging = true
                        nudgeTargetPx = 0f
                    },
                    onHorizontalDrag = { _, amount ->
                        dx += amount
                        nudgeTargetPx = dx.coerceIn(-maxNudgePx, maxNudgePx)
                    },
                    onDragEnd = {
                        dragging = false
                        nudgeTargetPx = 0f
                        if (abs(dx) >= dragThresholdPx) {
                            performTwin(
                                if (dx < 0f) GESTURE_ART_NEXT else GESTURE_ART_PREVIOUS,
                            )
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        nudgeTargetPx = 0f
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
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = nudgePx },
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
private fun SecondaryRow(
    onOpenQueue: () -> Unit,
    onTogglePane: () -> Unit,
    lyricsShown: Boolean,
) {
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
        IconButton(onClick = onTogglePane) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Notes,
                contentDescription = stringResource(R.string.now_playing_lyrics),
                tint = if (lyricsShown) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * Lyrics half of the art block (D-23). The data layer lands later, so
 * [LyricsUiState] usually has no lines and the pane shows its empty state. The
 * text stays a leaf: a vertical drag the pane cannot scroll falls through to
 * the sheet, while overflowing text consumes its own drags.
 */
@Composable
private fun LyricsPane(lyrics: LyricsUiState) {
    val tokens = OmnisonicTheme.tokens
    Box(
        modifier = Modifier
            .size(tokens.imagery.nowPlayingArtSize)
            .padding(horizontal = tokens.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        if (lyrics.isEmpty) {
            Text(
                text = stringResource(R.string.now_playing_no_lyrics),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                lyrics.lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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

/**
 * Vertical drag plumbing of the player sheet (ui/player-sheet.md §2): claims a
 * drag once it is vertical-dominant and no child consumed it, moves the sheet
 * live, and on release hands the velocity to the host. Without a host the
 * gesture settles by the `SpacingTokens.xxl` threshold, which is the button
 * twin's drag default.
 */
@Composable
private fun Modifier.playerSheetDrag(
    thresholdPx: Float,
    sheetDrag: PlayerSheetDrag?,
    onOpenQueue: () -> Unit,
    onCollapse: () -> Unit,
): Modifier {
    // Host lambdas change identity every frame while the sheet moves; reading
    // them through state keeps the pointer handler (and any drag in flight)
    // alive across those recompositions.
    val currentDrag = rememberUpdatedState(sheetDrag)
    val currentOpenQueue = rememberUpdatedState(onOpenQueue)
    val currentCollapse = rememberUpdatedState(onCollapse)
    return pointerInput(thresholdPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = PlayerSheetVelocity()
            tracker.add(down)
            var dx = 0f
            var dy = 0f
            var claimed = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.positionChange()
                tracker.add(change)
                dx += delta.x
                dy += delta.y
                if (!claimed) {
                    // A child that already started its own drag (art swipe,
                    // slider) owns this gesture; never fight it.
                    if (change.isConsumed) break
                    if (abs(dy) > abs(dx) && abs(dy) >= viewConfiguration.touchSlop) {
                        claimed = true
                    }
                }
                if (claimed) {
                    change.consume()
                    currentDrag.value?.onDrag(delta.y)
                }
                if (!change.pressed) {
                    if (claimed) {
                        val drag = currentDrag.value
                        if (drag != null) {
                            drag.onRelease(tracker.velocityY())
                        } else {
                            when {
                                dy <= -thresholdPx -> currentOpenQueue.value()
                                dy >= thresholdPx -> currentCollapse.value()
                            }
                        }
                    }
                    break
                }
            }
        }
    }
}

private const val GESTURE_ART_TAP = "art-tap"
private const val GESTURE_ART_PREVIOUS = "art-previous"
private const val GESTURE_ART_NEXT = "art-next"
private const val GESTURE_ART_QUEUE = "art-queue"
private const val GESTURE_ART_COLLAPSE = "art-collapse"
