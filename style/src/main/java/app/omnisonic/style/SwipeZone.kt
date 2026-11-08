package app.omnisonic.style

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.ModifierNodeElement
import kotlin.math.abs

/**
 * Drag axis a zone answers for (GESTURES.md §4.1 rule 1: a zone claims only
 * after touch slop and only when its axis dominates the other one).
 */
enum class SwipeAxis { HORIZONTAL, VERTICAL }

/**
 * Explicit drag surfaces win over content zones (GESTURES.md §4.1 rule 2):
 * a mini-player bar or sheet handle outranks anything registered as content.
 */
enum class SwipeZoneRole { DRAG_HANDLE, CONTENT }

/** One registered gesture surface a parent container must yield to. */
@Immutable
data class SwipeZone(
    val key: Any,
    val axis: SwipeAxis,
    val role: SwipeZoneRole,
)

/** Answer to "does this zone own this drag, or does the parent pager/sheet?" */
sealed interface SwipeZoneOwner {
    data class Zone(val zone: SwipeZone) : SwipeZoneOwner

    /** No zone claims the gesture — the asking container proceeds. */
    data object Parent : SwipeZoneOwner

    /** Touch slop not crossed (or a diagonal tie) — nobody may claim yet. */
    data object Unresolved : SwipeZoneOwner
}

/**
 * The disambiguation rules written down once (GESTURES.md §4.1, UI_SPEC.md
 * §1.1). Pure logic over (dx, dy, slop) tuples; gesture feel stays
 * device-gated. Same-axis content conflicts are forbidden by construction —
 * when two same-role zones claim one drag the answer is deliberately
 * [SwipeZoneOwner.Parent] instead of an arbitrary pick.
 */
object SwipeZoneRules {

    fun slopCrossed(dx: Float, dy: Float, touchSlopPx: Float): Boolean =
        maxOf(abs(dx), abs(dy)) >= touchSlopPx

    fun claims(zone: SwipeZone, dx: Float, dy: Float, touchSlopPx: Float): Boolean {
        if (!slopCrossed(dx, dy, touchSlopPx)) return false
        return when (zone.axis) {
            SwipeAxis.HORIZONTAL -> abs(dx) > abs(dy)
            SwipeAxis.VERTICAL -> abs(dy) > abs(dx)
        }
    }

    fun resolveOwner(
        zones: List<SwipeZone>,
        dx: Float,
        dy: Float,
        touchSlopPx: Float,
    ): SwipeZoneOwner {
        if (!slopCrossed(dx, dy, touchSlopPx)) return SwipeZoneOwner.Unresolved
        val claimants = zones.filter { claims(it, dx, dy, touchSlopPx) }
        if (claimants.isEmpty()) return SwipeZoneOwner.Parent
        val handles = claimants.filter { it.role == SwipeZoneRole.DRAG_HANDLE }
        val pool = handles.ifEmpty { claimants }
        return if (pool.size == 1) SwipeZoneOwner.Zone(pool.first()) else SwipeZoneOwner.Parent
    }

    /**
     * Rule 4: a fling settles by velocity, not position. Returns the target
     * fraction (0 = collapsed/start, 1 = expanded/end); below the threshold the
     * side the gesture is already past wins.
     */
    fun velocitySettle(velocity: Float, fraction: Float, velocityThreshold: Float): Float {
        require(velocityThreshold > 0f) { "velocityThreshold must be positive (was $velocityThreshold)" }
        val clamped = fraction.coerceIn(0f, 1f)
        return when {
            velocity >= velocityThreshold -> 1f
            velocity <= -velocityThreshold -> 0f
            clamped >= 0.5f -> 1f
            else -> 0f
        }
    }

    /**
     * Edge exclusion (UI_SPEC.md §1.2): the system back gesture owns the strip
     * within [edgeExclusionPx] of the left/right edge, so the pager never starts
     * a drag there. Vertical drags are exempt from the strip by definition —
     * they are not horizontal drags at all.
     */
    fun pagerClaimsHorizontalDrag(
        startX: Float,
        containerWidth: Float,
        edgeExclusionPx: Float,
        dx: Float,
        dy: Float,
        touchSlopPx: Float,
    ): Boolean {
        if (containerWidth <= 0f) return false
        if (!slopCrossed(dx, dy, touchSlopPx)) return false
        if (abs(dy) >= abs(dx)) return false
        if (edgeExclusionPx > 0f &&
            (startX < edgeExclusionPx || startX > containerWidth - edgeExclusionPx)
        ) {
            return false
        }
        return true
    }
}

/**
 * Hoisted per screen (or per NavHost); zones register while composed and the
 * outer pager/sheet asks [resolveOwner] before claiming a drag.
 */
class SwipeZoneRegistry {

    private val zones = LinkedHashMap<Any, SwipeZone>()

    val active: List<SwipeZone> get() = zones.values.toList()

    fun register(zone: SwipeZone) {
        zones[zone.key] = zone
    }

    fun unregister(key: Any) {
        zones.remove(key)
    }

    fun resolveOwner(dx: Float, dy: Float, touchSlopPx: Float): SwipeZoneOwner =
        SwipeZoneRules.resolveOwner(zones.values.toList(), dx, dy, touchSlopPx)
}

@Composable
fun rememberSwipeZoneRegistry(): SwipeZoneRegistry = remember { SwipeZoneRegistry() }

/**
 * Registers the modified surface as a zone for the lifetime of the node.
 * The zone's own gesture handling stays on the surface; this only tells
 * parent containers to yield.
 */
fun Modifier.swipeZone(
    registry: SwipeZoneRegistry,
    axis: SwipeAxis,
    role: SwipeZoneRole,
): Modifier = this then SwipeZoneElement(registry, axis, role)

private class SwipeZoneElement(
    private val registry: SwipeZoneRegistry,
    private val axis: SwipeAxis,
    private val role: SwipeZoneRole,
) : ModifierNodeElement<SwipeZoneNode>() {
    override fun create(): SwipeZoneNode = SwipeZoneNode(registry, axis, role)

    override fun update(node: SwipeZoneNode) = node.retarget(registry, axis, role)

    override fun equals(other: Any?): Boolean =
        other is SwipeZoneElement && other.registry === registry && other.axis == axis && other.role == role

    override fun hashCode(): Int = 31 * axis.hashCode() + role.hashCode()
}

private class SwipeZoneNode(
    private var registry: SwipeZoneRegistry,
    axis: SwipeAxis,
    role: SwipeZoneRole,
) : Modifier.Node() {
    private var spec = SwipeZone(key = this, axis = axis, role = role)

    fun retarget(registry: SwipeZoneRegistry, axis: SwipeAxis, role: SwipeZoneRole) {
        if (registry !== this.registry && isAttached) {
            this.registry.unregister(spec.key)
        }
        this.registry = registry
        if (spec.axis != axis || spec.role != role) {
            spec = spec.copy(axis = axis, role = role)
        }
        if (isAttached) registry.register(spec)
    }

    override fun onAttach() {
        registry.register(spec)
    }

    override fun onDetach() {
        registry.unregister(spec.key)
    }
}

/**
 * Pager-side edge exclusion (UI_SPEC.md §1.2): horizontal drags that start
 * inside the [edgeExclusionPx] strips are consumed so the pager never claims
 * them and the system back gesture owns the edges; vertical drags stay
 * untouched. Runs in the initial pass so it preempts the pager's own
 * drag detection.
 */
fun Modifier.pagerEdgeExclusion(
    edgeExclusionPx: Float,
    touchSlopPx: Float,
): Modifier = this.pointerInput(edgeExclusionPx, touchSlopPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startY = down.position.y
        val startX = down.position.x
        var decided = false
        var excluded = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val tracked = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!decided) {
                val dx = tracked.position.x - startX
                val dy = tracked.position.y - startY
                if (SwipeZoneRules.slopCrossed(dx, dy, touchSlopPx)) {
                    decided = true
                    excluded = !SwipeZoneRules.pagerClaimsHorizontalDrag(
                        startX = startX,
                        containerWidth = size.width.toFloat(),
                        edgeExclusionPx = edgeExclusionPx,
                        dx = dx,
                        dy = dy,
                        touchSlopPx = touchSlopPx,
                    )
                }
            }
            if (excluded) event.changes.forEach { it.consume() }
            if (!tracked.pressed) break
        }
    }
}
