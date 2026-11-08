package app.omnisonic.style

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The SwipeZone ownership contract as pure logic (GESTURES.md §4.1,
 * UI_SPEC.md §1.1-1.2): orthogonal slop, drag-handle precedence, velocity
 * settle, edge exclusion.
 */
class SwipeZoneRulesTest {

    private val slop = 10f

    @Test
    fun horizontalZoneClaimsOnlyWhenHorizontalDominates() {
        val zone = SwipeZone("z", SwipeAxis.HORIZONTAL, SwipeZoneRole.CONTENT)
        assertThat(SwipeZoneRules.claims(zone, dx = 30f, dy = 5f, touchSlopPx = slop)).isTrue()
        assertThat(SwipeZoneRules.claims(zone, dx = 5f, dy = 30f, touchSlopPx = slop)).isFalse()
    }

    @Test
    fun verticalZoneMirrorsTheOrthogonalRule() {
        val zone = SwipeZone("z", SwipeAxis.VERTICAL, SwipeZoneRole.CONTENT)
        assertThat(SwipeZoneRules.claims(zone, dx = 5f, dy = 30f, touchSlopPx = slop)).isTrue()
        assertThat(SwipeZoneRules.claims(zone, dx = 30f, dy = 5f, touchSlopPx = slop)).isFalse()
    }

    @Test
    fun nothingClaimsBeforeTouchSlop() {
        val zone = SwipeZone("z", SwipeAxis.HORIZONTAL, SwipeZoneRole.CONTENT)
        assertThat(SwipeZoneRules.claims(zone, dx = 9f, dy = 0f, touchSlopPx = slop)).isFalse()
        assertThat(
            SwipeZoneRules.resolveOwner(listOf(zone), dx = 9f, dy = 0f, touchSlopPx = slop),
        ).isEqualTo(SwipeZoneOwner.Unresolved)
    }

    @Test
    fun diagonalTieStaysUnresolvedForZonesAndParentForTheAsker() {
        val zone = SwipeZone("z", SwipeAxis.HORIZONTAL, SwipeZoneRole.CONTENT)
        assertThat(SwipeZoneRules.claims(zone, dx = 20f, dy = 20f, touchSlopPx = slop)).isFalse()
        assertThat(
            SwipeZoneRules.resolveOwner(listOf(zone), dx = 20f, dy = 20f, touchSlopPx = slop),
        ).isEqualTo(SwipeZoneOwner.Parent)
    }

    @Test
    fun dragHandleBeatsContentForTheSameDrag() {
        val content = SwipeZone("content", SwipeAxis.HORIZONTAL, SwipeZoneRole.CONTENT)
        val handle = SwipeZone("handle", SwipeAxis.HORIZONTAL, SwipeZoneRole.DRAG_HANDLE)
        val owner = SwipeZoneRules.resolveOwner(listOf(content, handle), dx = 30f, dy = 2f, touchSlopPx = slop)
        assertThat(owner).isEqualTo(SwipeZoneOwner.Zone(handle))
    }

    @Test
    fun ambiguousSameRoleClaimantsStayWithTheParent() {
        val first = SwipeZone("first", SwipeAxis.HORIZONTAL, SwipeZoneRole.CONTENT)
        val second = SwipeZone("second", SwipeAxis.HORIZONTAL, SwipeZoneRole.CONTENT)
        val twoContents = SwipeZoneRules.resolveOwner(listOf(first, second), dx = 30f, dy = 2f, touchSlopPx = slop)
        assertThat(twoContents).isEqualTo(SwipeZoneOwner.Parent)
        val handleA = SwipeZone("a", SwipeAxis.HORIZONTAL, SwipeZoneRole.DRAG_HANDLE)
        val handleB = SwipeZone("b", SwipeAxis.HORIZONTAL, SwipeZoneRole.DRAG_HANDLE)
        val twoHandles = SwipeZoneRules.resolveOwner(listOf(handleA, handleB), dx = 30f, dy = 2f, touchSlopPx = slop)
        assertThat(twoHandles).isEqualTo(SwipeZoneOwner.Parent)
    }

    @Test
    fun oneHandleOverAmbiguousContentStillWins() {
        val handle = SwipeZone("handle", SwipeAxis.HORIZONTAL, SwipeZoneRole.DRAG_HANDLE)
        val contentA = SwipeZone("a", SwipeAxis.HORIZONTAL, SwipeZoneRole.CONTENT)
        val contentB = SwipeZone("b", SwipeAxis.HORIZONTAL, SwipeZoneRole.CONTENT)
        val owner = SwipeZoneRules.resolveOwner(listOf(handle, contentA, contentB), dx = 30f, dy = 2f, touchSlopPx = slop)
        assertThat(owner).isEqualTo(SwipeZoneOwner.Zone(handle))
    }

    @Test
    fun noClaimantOnTheDragAxisHandsTheDragToTheParent() {
        val vertical = SwipeZone("v", SwipeAxis.VERTICAL, SwipeZoneRole.DRAG_HANDLE)
        val owner = SwipeZoneRules.resolveOwner(listOf(vertical), dx = 30f, dy = 2f, touchSlopPx = slop)
        assertThat(owner).isEqualTo(SwipeZoneOwner.Parent)
    }

    @Test
    fun velocityDecidesAboveTheThreshold() {
        assertThat(
            SwipeZoneRules.velocitySettle(velocity = 500f, fraction = 0.2f, velocityThreshold = 300f),
        ).isEqualTo(1f)
        assertThat(
            SwipeZoneRules.velocitySettle(velocity = -500f, fraction = 0.8f, velocityThreshold = 300f),
        ).isEqualTo(0f)
    }

    @Test
    fun positionDecidesBelowTheThreshold() {
        assertThat(
            SwipeZoneRules.velocitySettle(velocity = 100f, fraction = 0.6f, velocityThreshold = 300f),
        ).isEqualTo(1f)
        assertThat(
            SwipeZoneRules.velocitySettle(velocity = -100f, fraction = 0.4f, velocityThreshold = 300f),
        ).isEqualTo(0f)
    }

    @Test
    fun fractionIsClampedIntoRange() {
        assertThat(
            SwipeZoneRules.velocitySettle(velocity = 0f, fraction = 1.7f, velocityThreshold = 300f),
        ).isEqualTo(1f)
        assertThat(
            SwipeZoneRules.velocitySettle(velocity = 0f, fraction = -0.7f, velocityThreshold = 300f),
        ).isEqualTo(0f)
    }

    @Test
    fun velocitySettleRequiresPositiveThreshold() {
        assertThrows<IllegalArgumentException> {
            SwipeZoneRules.velocitySettle(velocity = 1f, fraction = 0.5f, velocityThreshold = 0f)
        }
    }

    @Test
    fun pagerVetoesDragsInsideTheEdgeStrip() {
        assertThat(
            SwipeZoneRules.pagerClaimsHorizontalDrag(
                startX = 12f, containerWidth = 400f, edgeExclusionPx = 24f,
                dx = 50f, dy = 2f, touchSlopPx = slop,
            ),
        ).isFalse()
        assertThat(
            SwipeZoneRules.pagerClaimsHorizontalDrag(
                startX = 388f, containerWidth = 400f, edgeExclusionPx = 24f,
                dx = -50f, dy = 2f, touchSlopPx = slop,
            ),
        ).isFalse()
    }

    @Test
    fun pagerClaimsDragsOutsideTheEdgeStrip() {
        assertThat(
            SwipeZoneRules.pagerClaimsHorizontalDrag(
                startX = 32f, containerWidth = 400f, edgeExclusionPx = 24f,
                dx = 50f, dy = 2f, touchSlopPx = slop,
            ),
        ).isTrue()
        assertThat(
            SwipeZoneRules.pagerClaimsHorizontalDrag(
                startX = 200f, containerWidth = 400f, edgeExclusionPx = 24f,
                dx = -50f, dy = 2f, touchSlopPx = slop,
            ),
        ).isTrue()
    }

    @Test
    fun verticalDragsNeverBelongToThePager() {
        assertThat(
            SwipeZoneRules.pagerClaimsHorizontalDrag(
                startX = 12f, containerWidth = 400f, edgeExclusionPx = 24f,
                dx = 2f, dy = 50f, touchSlopPx = slop,
            ),
        ).isFalse()
    }

    @Test
    fun zeroEdgeExclusionDisablesTheStrip() {
        assertThat(
            SwipeZoneRules.pagerClaimsHorizontalDrag(
                startX = 1f, containerWidth = 400f, edgeExclusionPx = 0f,
                dx = 50f, dy = 2f, touchSlopPx = slop,
            ),
        ).isTrue()
    }

    @Test
    fun slopNotCrossedNeverClaimsForThePager() {
        assertThat(
            SwipeZoneRules.pagerClaimsHorizontalDrag(
                startX = 200f, containerWidth = 400f, edgeExclusionPx = 24f,
                dx = 9f, dy = 0f, touchSlopPx = slop,
            ),
        ).isFalse()
    }
}

class SwipeZoneRegistryTest {

    @Test
    fun registersAndUnregistersZones() {
        val registry = SwipeZoneRegistry()
        val zone = SwipeZone("z", SwipeAxis.HORIZONTAL, SwipeZoneRole.CONTENT)

        registry.register(zone)
        assertThat(registry.active).containsExactly(zone)

        registry.unregister("z")
        assertThat(registry.active).isEmpty()
    }

    @Test
    fun sameKeyReplacesInsteadOfDuplicating() {
        val registry = SwipeZoneRegistry()
        val original = SwipeZone("z", SwipeAxis.HORIZONTAL, SwipeZoneRole.CONTENT)
        val updated = SwipeZone("z", SwipeAxis.VERTICAL, SwipeZoneRole.DRAG_HANDLE)

        registry.register(original)
        registry.register(updated)

        assertThat(registry.active).containsExactly(updated)
    }

    @Test
    fun registryResolvesOwnershipThroughTheRules() {
        val registry = SwipeZoneRegistry()
        registry.register(SwipeZone("content", SwipeAxis.HORIZONTAL, SwipeZoneRole.CONTENT))

        val horizontal = registry.resolveOwner(dx = 30f, dy = 2f, touchSlopPx = 10f)
        assertThat(horizontal).isEqualTo(SwipeZoneOwner.Zone(registry.active.single()))

        val vertical = registry.resolveOwner(dx = 2f, dy = 30f, touchSlopPx = 10f)
        assertThat(vertical).isEqualTo(SwipeZoneOwner.Parent)
    }
}
