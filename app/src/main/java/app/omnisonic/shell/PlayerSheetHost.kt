package app.omnisonic.shell

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import app.omnisonic.feature.library.player.PlayerSheetRules
import app.omnisonic.feature.library.player.PlayerSheetStops
import app.omnisonic.style.OmnisonicTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Position of the player sheet as a screen-height fraction (ui/player-sheet.md
 * §1): `-1` shows the swipe-up target, `0` shows now-playing, `1` is peek (only
 * the mini player). The now-playing surface is drawn at `position * viewport`
 * and the swipe-up target one screen above it, so both follow the finger and
 * the shell beneath is revealed as the sheet moves.
 *
 * This class is the only writer of [position], and it is a small state machine
 * rather than a drag pipeline (D-25):
 *
 * - a gesture opens on a pointer down ([onPointerDown]) and is the only thing
 *   that may cancel a settle, so nothing races the animation from behind;
 * - deltas are accepted only while that gesture is open, so a nested-scroll
 *   callback or a duplicated lift arriving after the finger is gone is dropped
 *   instead of driving the sheet away from the settle;
 * - every release rule returns an anchor ([PlayerSheetRules]), and the settle
 *   writes that anchor even if its animation is cut short, so "no finger down"
 *   always means "at a detent".
 */
@Stable
class PlayerSheetState(
    private val scope: CoroutineScope,
    private val settleSpec: AnimationSpec<Float>,
) {
    /** Screen-height fraction; only ever rests on a [PlayerSheetStops] value. */
    var position by mutableFloatStateOf(PlayerSheetStops.PEEK)
        private set

    /** Sheet viewport height in px; set by the shell root once it is measured. */
    var viewportPx by mutableFloatStateOf(0f)

    val isVisible: Boolean
        get() = viewportPx > 0f && position < PlayerSheetStops.PEEK - VISIBLE_EPSILON

    /** True while a swipe-up target sits above now-playing (queue by default). */
    val swipeUpTargetRevealed: Boolean
        get() = position < PlayerSheetStops.NOW_PLAYING - VISIBLE_EPSILON

    private var settleJob: Job? = null

    /** Guards a cancelled settle from writing its own anchor over a newer one. */
    private var settleGeneration = 0

    /** Whether a pointer is down on the shell and owns the sheet. */
    private var gestureLive = false

    /** Which release rule the live gesture follows: the bounded queue pull or any other drag. */
    private var boundedPull = false

    /**
     * A pointer went down anywhere in the shell. The only caller is the shell
     * root, which sees every gesture, including the ones that start on the mini
     * bar before the sheet is drawn.
     */
    fun onPointerDown() {
        if (gestureLive) return
        gestureLive = true
        boundedPull = false
        settleGeneration++
        settleJob?.cancel()
    }

    /**
     * A pointer stream that ended without the owning surface reporting a
     * release (system cancellation, focus loss, a second pointer lifting last).
     * Settles the live gesture with zero velocity using the rule that started
     * it, so the sheet cannot stay parked with the finger gone.
     */
    fun onAllPointersUp(flingVelocityPx: Float, commitDistancePx: Float) {
        if (!gestureLive) return
        if (boundedPull) {
            releaseFromSwipeUpTarget(0f, flingVelocityPx, commitDistancePx)
        } else {
            release(0f, flingVelocityPx)
        }
    }

    /** Live drag of any surface that follows the finger through the whole range. */
    fun drag(deltaYPx: Float) {
        dragBy(deltaYPx, bounded = false)
    }

    /**
     * Live drag of a gesture that started at the top of the swipe-up target's
     * content: the same finger following, bounded at now-playing.
     */
    fun dragFromSwipeUpTarget(deltaYPx: Float) {
        dragBy(deltaYPx, bounded = true)
    }

    /** Release of an unbounded drag: one detent, chosen by [PlayerSheetRules.settleTarget]. */
    fun release(velocityYPx: Float, flingVelocityPx: Float) {
        if (!gestureLive || viewportPx <= 0f) return
        finish(
            target = PlayerSheetRules.settleTarget(
                position = position,
                velocityYPx = velocityYPx,
                flingVelocityPx = flingVelocityPx,
            ),
            velocityYPx = velocityYPx,
        )
    }

    /**
     * Release of a swipe-up-target pull. Bounded so the gesture can only ever
     * return to now-playing, never skip it for the route underneath. A slow
     * release commits once the pull passed [commitDistancePx]; only a
     * genuinely tiny twitch snaps back (ui/player-queue-return.md §4.1).
     */
    fun releaseFromSwipeUpTarget(
        velocityYPx: Float,
        flingVelocityPx: Float,
        commitDistancePx: Float,
    ) {
        if (!gestureLive || viewportPx <= 0f) return
        finish(
            target = PlayerSheetRules.pullTarget(
                position = position,
                velocityYPx = velocityYPx,
                flingVelocityPx = flingVelocityPx,
                commitDistanceFraction = commitDistancePx / viewportPx,
            ),
            velocityYPx = velocityYPx,
        )
    }

    fun openNowPlaying() = settleTo(PlayerSheetStops.NOW_PLAYING)

    /** System back and the target's back arrow share one detent-aware chain. */
    fun onBack() = settleTo(PlayerSheetRules.backDestination(position))

    fun openSwipeUpTarget() = settleTo(PlayerSheetStops.SWIPE_UP_TARGET)

    fun close() = settleTo(PlayerSheetStops.PEEK)

    private fun dragBy(deltaYPx: Float, bounded: Boolean) {
        if (!gestureLive || viewportPx <= 0f) return
        boundedPull = bounded
        position = if (bounded) {
            PlayerSheetRules.dragPositionFromSwipeUpTarget(position, deltaYPx, viewportPx)
        } else {
            PlayerSheetRules.dragPosition(position, deltaYPx, viewportPx)
        }
    }

    /** Ends the gesture and starts the one settle that will place the sheet. */
    private fun finish(target: Float, velocityYPx: Float) {
        gestureLive = false
        settleTo(target, initialVelocityFraction = velocityYPx / viewportPx)
    }

    /**
     * One spring for every detent settle, from wherever the sheet currently is,
     * so a finger that grabs the sheet mid-animation picks it up from the frame
     * it touched. Programmatic moves close the gesture first: a later all-up
     * from the tap that triggered them must not re-settle the sheet.
     */
    private fun settleTo(target: Float, initialVelocityFraction: Float = 0f) {
        gestureLive = false
        settleJob?.cancel()
        val generation = ++settleGeneration
        val from = position
        if (from == target) {
            position = target
            return
        }
        settleJob = scope.launch {
            try {
                animate(
                    initialValue = from,
                    targetValue = target,
                    initialVelocity = initialVelocityFraction,
                    animationSpec = settleSpec,
                ) { value, _ -> position = value }
                position = target
            } finally {
                if (generation == settleGeneration && !gestureLive) position = target
            }
        }
    }

    private companion object {
        const val VISIBLE_EPSILON = 0.001f
    }
}

@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val scope = rememberCoroutineScope()
    val motion = OmnisonicTheme.tokens.motion.nowPlayingExpand
    val spec = remember(motion) {
        spring<Float>(
            dampingRatio = motion.dampingRatio,
            stiffness = motion.stiffness,
        )
    }
    return remember(scope, spec) { PlayerSheetState(scope, spec) }
}

/**
 * Draws the player sheet over the whole shell, including the mini player and
 * the navigation bars, so the screen underneath genuinely stays composed and
 * is revealed by a downward drag instead of a canned route transition.
 *
 * Hit testing: the layer stack is barrier → swipe-up target → now-playing.
 * The barrier only takes pointer input; it must never consume, because
 * consumption on this ancestor's Main pass cancels child drag starts
 * (`ui/gesture-contract.md`, task 4e bug record).
 */
@Composable
fun PlayerSheetOverlay(
    state: PlayerSheetState,
    swipeUpTarget: @Composable () -> Unit,
    nowPlaying: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isVisible) return
    val viewport = state.viewportPx
    Box(modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) { })
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = (state.position + 1f) * viewport },
        ) {
            swipeUpTarget()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = state.position * viewport },
        ) {
            nowPlaying()
        }
    }
}
