package app.omnisonic.style

import androidx.compose.runtime.Immutable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics

/**
 * One gesture and its mandatory replacement for pointer-free users
 * (UI_SPEC.md §1.3, the button-twin rule): nothing is swipe-only — every
 * gesture carries both a custom accessibility action (installed by
 * [Modifier.gestureTwins]) and a visible control on the same screen.
 */
@Immutable
data class GestureTwin(
    val gestureId: String,
    val actionLabel: String,
    val twinControlLabel: String,
)

/** Installs the twins as custom accessibility actions on the gesture surface. */
fun Modifier.gestureTwins(
    twins: List<GestureTwin>,
    perform: (gestureId: String) -> Unit,
): Modifier = semantics {
    if (twins.isNotEmpty()) {
        customActions = twins.map { twin ->
            CustomAccessibilityAction(twin.actionLabel) { perform(twin.gestureId); true }
        }
    }
}

/**
 * The audit half of the twin rule (UI_SPEC.md §5): given a semantics tree and
 * a screen's twin declarations, verifies each gesture has its custom
 * accessibility action registered and a visible clickable control wearing the
 * twin label. Screen tasks call [assertTwinsPresent] in their compose tests;
 * a failure lists the untwinned gestures.
 */
object ButtonTwinAudit {

    fun findMissingTwins(root: SemanticsNode, twins: Collection<GestureTwin>): List<GestureTwin> {
        val nodes = root.flattenInclusive()
        return twins.filterNot { twin -> isTwinned(nodes, twin) }
    }

    fun assertTwinsPresent(root: SemanticsNode, twins: Collection<GestureTwin>) {
        val missing = findMissingTwins(root, twins)
        if (missing.isNotEmpty()) {
            throw AssertionError(
                "Gestures without a button twin (UI_SPEC.md §1.3): " + missing.joinToString {
                    "${it.gestureId} (action '${it.actionLabel}', control '${it.twinControlLabel}')"
                },
            )
        }
    }

    private fun isTwinned(nodes: List<SemanticsNode>, twin: GestureTwin): Boolean =
        hasCustomAction(nodes, twin.actionLabel) && hasVisibleControl(nodes, twin.twinControlLabel)

    private fun hasCustomAction(nodes: List<SemanticsNode>, label: String): Boolean = nodes.any { node ->
        node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun hasVisibleControl(nodes: List<SemanticsNode>, label: String): Boolean = nodes.any { node ->
        if (node.config.getOrNull(SemanticsProperties.InvisibleToUser) != null) return@any false
        val labelled = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
        labelled && node.config.getOrNull(SemanticsActions.OnClick) != null
    }

    private fun SemanticsNode.flattenInclusive(): List<SemanticsNode> =
        listOf(this) + children.flatMap { it.flattenInclusive() }
}
