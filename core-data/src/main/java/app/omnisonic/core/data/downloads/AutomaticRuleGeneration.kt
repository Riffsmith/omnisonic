package app.omnisonic.core.data.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One verified expansion of every current rule (task 8a). [wantedMediaKeys]
 * is the union resolved by media-key identity, so overlap never depends on a
 * stored `autoRuleId`. A generation exists only when every rule resolved
 * completely, the rule snapshot did not change during the pass and no
 * provider sync was running at hand-off; task 8b may act destructively only on
 * [AutomaticRuleGenerationState.verified] generations.
 */
data class AutomaticRuleGeneration(
    val sequence: Long,
    val verifiedAtMs: Long,
    val ruleCount: Int,
    val wantedMediaKeys: Set<String>,
    val providerIds: Set<Long>,
    val expansions: List<AutomaticRuleExpansion>,
)

/** Why the last pass could not publish a verified generation. */
enum class AutomaticRuleGenerationStaleReason {
    RULE_READ_FAILED,
    INCOMPLETE_EXPANSION,
    SYNC_RUNNING,
    RULES_CHANGED,
    PASS_FAILED,
}

/**
 * The latest generation plus its validity. A stale state keeps the last
 * verified generation for diagnostics but never authorizes pruning; an
 * incomplete read, a sync starting, or a rule change during the pass all mark
 * the state stale until a fresh complete pass replaces it.
 */
data class AutomaticRuleGenerationState(
    val generation: AutomaticRuleGeneration? = null,
    val verified: Boolean = false,
    val staleReason: AutomaticRuleGenerationStaleReason? = null,
)

/** Process-wide hand-off point from task 8a's non-destructive pass to task 8b. */
class AutomaticRuleGenerationStore {

    private val _state = MutableStateFlow(AutomaticRuleGenerationState())
    val state: StateFlow<AutomaticRuleGenerationState> = _state.asStateFlow()

    fun publish(generation: AutomaticRuleGeneration) {
        _state.value = AutomaticRuleGenerationState(
            generation = generation,
            verified = true,
            staleReason = null,
        )
    }

    fun markStale(reason: AutomaticRuleGenerationStaleReason) {
        _state.update { it.copy(verified = false, staleReason = reason) }
    }

    val current: AutomaticRuleGeneration? get() = _state.value.generation
}
