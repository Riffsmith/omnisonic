package app.omnisonic.feature.library.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.omnisonic.feature.library.R
import app.omnisonic.feature.library.miniplayer.MiniPlayerExpandRules
import app.omnisonic.feature.library.miniplayer.MiniPlayerSettle
import app.omnisonic.feature.library.miniplayer.MiniPlayerUiState
import app.omnisonic.feature.library.player.PlayerSheetStops
import app.omnisonic.feature.library.player.PlayerSheetVelocity
import app.omnisonic.style.GestureTwin
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.SwipeAxis
import app.omnisonic.style.SwipeZone
import app.omnisonic.style.SwipeZoneRegistry
import app.omnisonic.style.SwipeZoneRole
import app.omnisonic.style.SwipeZoneRules
import app.omnisonic.style.gestureTwins
import app.omnisonic.style.swipeZone
import kotlinx.coroutines.launch

/**
 * Compact bar above the navigation slot (UI_SPEC.md §3.10). Purely a function
 * of [MiniPlayerUiState]; hidden by the caller when nothing is playing.
 *
 * The bar is a vertical drag handle (drag up = expand, tap = expand — the
 * button twin, UI_SPEC.md §1.3). The whole bar carries the "Expand player"
 * label, so the controls themselves are previous / play-pause / next. The drag
 * is claimed through the SwipeZone contract; expand navigates at the settle
 * decision and collapse springs back with `MotionTokens.nowPlayingExpand`
 * (ui/player-ux-fixes.md §3.4-3.5).
 *
 * When the host supplies [sheetDrag] the drag instead moves the player sheet
 * pixel-for-pixel (ui/player-sheet.md §2): the bar forwards raw deltas and the
 * release velocity, and the sheet's own settle drives the motion. The bar is
 * then the sheet's peek detent — [sheetPosition] makes it a pure function of
 * the sheet (it slides out as the sheet expands), so no second animation
 * system runs next to the sheet.
 */
@Composable
fun MiniPlayerBar(
    state: MiniPlayerUiState,
    swipeZoneRegistry: SwipeZoneRegistry,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    sheetDrag: PlayerSheetDrag? = null,
    sheetPosition: (() -> Float)? = null,
) {
    val entry = state.entry ?: return
    val tokens = OmnisonicTheme.tokens
    val surface = tokens.surfaces.miniPlayer
    val expandLabel = stringResource(R.string.mini_player_expand)
    val playPauseLabel = stringResource(R.string.now_playing_play_pause)
    val previousLabel = stringResource(R.string.now_playing_previous)
    val nextLabel = stringResource(R.string.now_playing_next)
    val twins = remember(expandLabel) {
        listOf(GestureTwin(GESTURE_MINI_EXPAND, expandLabel, expandLabel))
    }
    val density = LocalDensity.current
    val travelPx = with(density) { tokens.layout.nowPlayingBarHeight.toPx() }
    val liftPx = with(density) { tokens.spacing.xl.toPx() }
    val barExitPx = with(density) {
        (tokens.layout.nowPlayingBarHeight + tokens.spacing.md).toPx()
    }
    val motion = tokens.motion.nowPlayingExpand
    val expandSpec = remember(motion) {
        spring<Float>(dampingRatio = motion.dampingRatio, stiffness = motion.stiffness)
    }
    val context = LocalContext.current
    val flingVelocityPx = remember(context) {
        android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    }
    val scope = rememberCoroutineScope()
    val sheetDragState = rememberUpdatedState(sheetDrag)
    var expandFraction by remember { mutableFloatStateOf(MiniPlayerExpandRules.COLLAPSED) }
    // The hairline freezes while the expand gesture owns the bar (drag through
    // settle) so 500 ms ticker updates do not fight the lift animation; it
    // resumes from live state once the gesture is over.
    var progressHold by remember { mutableStateOf(false) }
    var heldProgress by remember { mutableFloatStateOf(0f) }
    val hairlineProgress by animateFloatAsState(
        targetValue = if (progressHold) heldProgress else state.progress,
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = surface.alpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (surface.borderAlpha > 0f) {
            BorderStroke(
                Dp.Hairline,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = surface.borderAlpha),
            )
        } else {
            null
        },
        modifier = modifier
            .fillMaxWidth()
            .height(tokens.layout.nowPlayingBarHeight)
            .then(
                if (sheetPosition == null) {
                    Modifier
                } else {
                    Modifier.graphicsLayer {
                        val exit = barExitProgress(sheetPosition())
                        translationY = exit * barExitPx
                        alpha = 1f - exit
                    }
                },
            )
            .swipeZone(swipeZoneRegistry, SwipeAxis.VERTICAL, SwipeZoneRole.DRAG_HANDLE)
            .gestureTwins(twins) { onExpand() }
            .pointerInput(expandSpec, travelPx, flingVelocityPx) {
                val dragZone = SwipeZone(
                    key = DRAG_ZONE_KEY,
                    axis = SwipeAxis.VERTICAL,
                    role = SwipeZoneRole.DRAG_HANDLE,
                )
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = PlayerSheetVelocity()
                    tracker.add(down)
                    var claimed = false
                    var startFraction = expandFraction
                    var lastY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        val deltaY = change.position.y - lastY
                        lastY = change.position.y
                        tracker.add(change)
                        if (!claimed &&
                            SwipeZoneRules.claims(dragZone, dx, dy, viewConfiguration.touchSlop)
                        ) {
                            claimed = true
                            startFraction = expandFraction
                            if (sheetDragState.value == null) {
                                progressHold = true
                                heldProgress = hairlineProgress
                            }
                        }
                        if (claimed) {
                            event.changes.forEach { it.consume() }
                            val sheet = sheetDragState.value
                            if (sheet != null) {
                                sheet.onDrag(deltaY)
                            } else {
                                expandFraction =
                                    MiniPlayerExpandRules.dragProgress(startFraction, dy, travelPx)
                            }
                        }
                        if (!change.pressed) {
                            if (claimed) {
                                val velocityY = tracker.velocityY()
                                val sheet = sheetDragState.value
                                if (sheet != null) {
                                    // The sheet owns the settle; the bar stays put
                                    // underneath it.
                                    sheet.onRelease(velocityY)
                                    expandFraction = MiniPlayerExpandRules.COLLAPSED
                                    break
                                }
                                val target = MiniPlayerExpandRules.settleTarget(
                                    velocityYPx = velocityY,
                                    fraction = expandFraction,
                                    velocityThresholdPx = flingVelocityPx,
                                )
                                val expanding = target == MiniPlayerSettle.EXPANDED
                                // Navigate at the settle decision, not after the
                                // spring: the now-playing route transition owns
                                // the motion from here (ui/player-ux-fixes.md §3.5).
                                if (expanding) onExpand()
                                val start = expandFraction
                                // `animate` works in fraction units: hand it the
                                // travel-normalised velocity, not raw px/s.
                                val fractionVelocity = velocityY / travelPx
                                scope.launch {
                                    animate(
                                        initialValue = start,
                                        targetValue = if (expanding) {
                                            MiniPlayerExpandRules.EXPANDED
                                        } else {
                                            MiniPlayerExpandRules.COLLAPSED
                                        },
                                        initialVelocity = fractionVelocity,
                                        animationSpec = expandSpec,
                                    ) { value, _ ->
                                        expandFraction = value
                                    }
                                    progressHold = false
                                }
                            }
                            break
                        }
                    }
                }
            }
            .semantics { contentDescription = expandLabel }
            .clickable(role = Role.Button, onClick = onExpand),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer {
                        if (sheetPosition == null) {
                            translationY = -expandFraction * liftPx
                            val liftScale = 1f + expandFraction * (MAX_LIFT_SCALE - 1f)
                            scaleX = liftScale
                            scaleY = liftScale
                        }
                    }
                    .padding(horizontal = tokens.spacing.sm),
            ) {
                CoverImage(
                    cover = entry.cover,
                    contentDescription = null,
                    modifier = Modifier.size(tokens.imagery.listThumbSize),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.artistName?.let { artist ->
                        Text(
                            text = artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onPrevious) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = previousLabel,
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (state.isPlaying) {
                            Icons.Filled.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = playPauseLabel,
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = nextLabel,
                    )
                }
            }
            ProgressHairline(
                progress = hairlineProgress,
                rangeProgress = state.progress,
                height = tokens.layout.miniPlayerProgressHeight,
                trackColor = tokens.colors.progressTrack,
                activeColor = tokens.colors.progressActive,
            )
        }
    }
}

/**
 * The determinate progress hairline docked to the bar's bottom edge
 * (ui/player-ux-fixes.md §3.2): full-width track, fill sized by the smoothed
 * playback fraction. Non-interactive — seek lives on the now-playing screen.
 */
@Composable
private fun ProgressHairline(
    progress: Float,
    rangeProgress: Float,
    height: Dp,
    trackColor: Color,
    activeColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(rangeProgress, 0f..1f)
            }
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(activeColor),
        )
    }
}

private const val GESTURE_MINI_EXPAND = "mini-expand"
private const val DRAG_ZONE_KEY = "mini-player-bar"

/** Drag-lift scale ceiling (`ui/player-ux-fixes.md` §3.4). */
private const val MAX_LIFT_SCALE = 1.03f

/**
 * How far the bar has left its peek slot: 0 at [PlayerSheetStops.PEEK], 1 once
 * the sheet reaches now-playing (or further up). The bar slides down and fades
 * out by that fraction, animated by the sheet's own spring.
 */
internal fun barExitProgress(position: Float): Float =
    ((PlayerSheetStops.PEEK - position) /
        (PlayerSheetStops.PEEK - PlayerSheetStops.NOW_PLAYING)).coerceIn(0f, 1f)
