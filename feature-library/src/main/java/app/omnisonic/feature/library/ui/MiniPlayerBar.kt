package app.omnisonic.feature.library.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.omnisonic.feature.library.R
import app.omnisonic.feature.library.miniplayer.MiniPlayerExpandRules
import app.omnisonic.feature.library.miniplayer.MiniPlayerSettle
import app.omnisonic.feature.library.miniplayer.MiniPlayerUiState
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
 * button twin, UI_SPEC.md §1.3). The drag is claimed through the SwipeZone
 * contract and settles with the `MotionTokens.nowPlayingExpand` spring.
 */
@Composable
fun MiniPlayerBar(
    state: MiniPlayerUiState,
    swipeZoneRegistry: SwipeZoneRegistry,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = state.entry ?: return
    val tokens = OmnisonicTheme.tokens
    val surface = tokens.surfaces.miniPlayer
    val expandLabel = stringResource(R.string.mini_player_expand)
    val playPauseLabel = stringResource(R.string.now_playing_play_pause)
    val nextLabel = stringResource(R.string.now_playing_next)
    val twins = remember(expandLabel) {
        listOf(GestureTwin(GESTURE_MINI_EXPAND, expandLabel, expandLabel))
    }
    val density = LocalDensity.current
    val travelPx = with(density) { tokens.layout.nowPlayingBarHeight.toPx() }
    val liftPx = with(density) { tokens.spacing.md.toPx() }
    val motion = tokens.motion.nowPlayingExpand
    val expandSpec = remember(motion) {
        spring<Float>(dampingRatio = motion.dampingRatio, stiffness = motion.stiffness)
    }
    val context = LocalContext.current
    val flingVelocityPx = remember(context) {
        android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    }
    val scope = rememberCoroutineScope()
    var expandFraction by remember { mutableFloatStateOf(MiniPlayerExpandRules.COLLAPSED) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = surface.alpha),
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
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var claimed = false
                    var startFraction = expandFraction
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (change.pressed) {
                            tracker.addPosition(change.uptimeMillis, change.position)
                        }
                        if (!claimed &&
                            SwipeZoneRules.claims(dragZone, dx, dy, viewConfiguration.touchSlop)
                        ) {
                            claimed = true
                            startFraction = expandFraction
                        }
                        if (claimed) {
                            event.changes.forEach { it.consume() }
                            expandFraction =
                                MiniPlayerExpandRules.dragProgress(startFraction, dy, travelPx)
                        }
                        if (!change.pressed) {
                            if (claimed) {
                                val velocityY = tracker.calculateVelocity().y
                                val target = MiniPlayerExpandRules.settleTarget(
                                    velocityYPx = velocityY,
                                    fraction = expandFraction,
                                    velocityThresholdPx = flingVelocityPx,
                                )
                                val expanding = target == MiniPlayerSettle.EXPANDED
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
                                    if (expanding) onExpand()
                                }
                            }
                            break
                        }
                    }
                }
            }
            .clickable(role = Role.Button, onClick = onExpand),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = -expandFraction * liftPx }
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
            IconButton(onClick = onExpand) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = expandLabel,
                )
            }
        }
    }
}

private const val GESTURE_MINI_EXPAND = "mini-expand"
private const val DRAG_ZONE_KEY = "mini-player-bar"
