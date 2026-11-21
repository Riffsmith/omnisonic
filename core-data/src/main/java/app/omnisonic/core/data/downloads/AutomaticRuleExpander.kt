package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.provider.api.DownloadEnqueueResult
import app.omnisonic.provider.api.ProviderStatus
import kotlin.coroutines.cancellation.CancellationException

/** Whether a rule's current catalog snapshot could be expanded at all. */
enum class AutomaticRuleExpansionOutcome {
    /** Resolution succeeded; per-track gate/destination counts explain any refused row. */
    EXPANDED,

    /** The provider, playlist or album no longer exists locally. */
    STALE_TARGET,

    /** The target exists but its expected songs are not in the local catalog yet. */
    UNSYNCED,

    /** The catalog read failed; the pass enqueued nothing for this rule. */
    READ_FAILED,
}

/**
 * Result of one rule in one complete-snapshot pass. [acceptedCount] counts
 * [DownloadEnqueueResult.Enqueued], which includes a strict duplicate no-op;
 * no existing queue row is ever rewritten by expansion. [wantedMediaKeys] is
 * the rule's owned identity set after cross-rule variant selection, including
 * keys already offline or already queued, so a generation can be compared
 * without trusting one row.
 */
data class AutomaticRuleExpansion(
    val rule: AutomaticRule,
    val outcome: AutomaticRuleExpansionOutcome,
    val wantedCount: Int = 0,
    val acceptedCount: Int = 0,
    val blockedCount: Int = 0,
    val blockedBy: Map<ProviderStatus, Int> = emptyMap(),
    val unsupportedCount: Int = 0,
    val destinationConflictCount: Int = 0,
    val wantedMediaKeys: Set<String> = emptySet(),
    val alreadyPresentCount: Int = 0,
)

/**
 * Expands the current persisted rules into unique rolling queue rows (task
 * 7b). This pass is additive only: it never cancels a row, deletes a file or
 * prunes a previous snapshot. Task 8b owns the destructive wanted-set pass.
 *
 * Rules are processed in the repository's deterministic provider/kind/target
 * order. When two current rules resolve the same song at different variants,
 * the newest rule (`updatedAt`, then `id`) owns the wanted identity; the older
 * rule's variant is not enqueued. This keeps conflicting rules from replacing
 * or pruning each other on every pass. Overlapping rules with the same variant
 * still enqueue the same strict duplicate, so the surviving row keeps the
 * first rule's `autoRuleId` hint. Nothing here treats that hint as ownership,
 * and manual/permanent rows never participate in this selection.
 *
 * Every enqueue goes through [DownloadEnqueuer], so the provider capability,
 * run gate, global pause and insert-if-absent lock all apply per track. A
 * blocked or unsupported provider writes nothing and is reported in the typed
 * counts; calling the pass again is a fresh explicit invocation, never an
 * internal retry loop.
 */
class AutomaticRuleExpander(
    private val rules: AutomaticRuleRepository,
    private val tracks: AutomaticRuleTrackSource,
    private val enqueuer: DownloadEnqueuer,
) {

    suspend fun expand(scope: ProfileScope): List<AutomaticRuleExpansion> =
        expand(rules.list(scope), scope)

    /**
     * Expands an already-read rule snapshot (task 8a compares it again before
     * handing a generation off). [presentMediaKeys] are identities that
     * already have a verified offline row; they count as wanted but are not
     * enqueued, so observing completion cannot restart the same download.
     */
    suspend fun expand(
        currentRules: List<AutomaticRule>,
        scope: ProfileScope,
        presentMediaKeys: Set<String> = emptySet(),
    ): List<AutomaticRuleExpansion> {
        val resolved = currentRules.map { rule -> resolveRule(rule, scope) }
        val winningKeys = winningMediaKeys(resolved)
        return resolved.map { resolvedRule ->
            when (resolvedRule.outcome) {
                AutomaticRuleExpansionOutcome.EXPANDED ->
                    enqueue(
                        rule = resolvedRule.rule,
                        tracks = resolvedRule.tracks,
                        presentMediaKeys = presentMediaKeys,
                        winningKeys = winningKeys,
                    )

                else -> AutomaticRuleExpansion(resolvedRule.rule, resolvedRule.outcome)
            }
        }
    }

    private data class ResolvedRule(
        val rule: AutomaticRule,
        val outcome: AutomaticRuleExpansionOutcome,
        val tracks: List<AutomaticRuleTrack> = emptyList(),
    )

    private data class SongIdentity(val providerId: Long, val externalId: String)

    private suspend fun resolveRule(
        rule: AutomaticRule,
        scope: ProfileScope,
    ): ResolvedRule {
        val resolution = try {
            tracks.resolve(rule, scope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ResolvedRule(rule, AutomaticRuleExpansionOutcome.READ_FAILED)
        }
        return when (resolution) {
            AutomaticRuleTargetResolution.StaleTarget ->
                ResolvedRule(rule, AutomaticRuleExpansionOutcome.STALE_TARGET)

            AutomaticRuleTargetResolution.Unsynced ->
                ResolvedRule(rule, AutomaticRuleExpansionOutcome.UNSYNCED)

            is AutomaticRuleTargetResolution.Resolved ->
                ResolvedRule(rule, AutomaticRuleExpansionOutcome.EXPANDED, resolution.tracks)
        }
    }

    /**
     * The newest rule that resolves a song owns that song's variant. `updatedAt`
     * is bumped by an explicit variant change, so a quality edit wins over the
     * older rule; `id` makes equal timestamps deterministic.
     */
    private fun winningMediaKeys(resolved: List<ResolvedRule>): Map<SongIdentity, String> {
        val winners = HashMap<SongIdentity, AutomaticRule>()
        for (result in resolved) {
            if (result.outcome != AutomaticRuleExpansionOutcome.EXPANDED) continue
            for (track in result.tracks) {
                val identity = SongIdentity(result.rule.providerId, track.externalId)
                val current = winners[identity]
                if (current == null || isNewer(result.rule, current)) winners[identity] = result.rule
            }
        }
        return winners.mapValues { (identity, rule) ->
            DownloadKeys.mediaKey(
                identity.providerId,
                identity.externalId,
                rule.transcodeBitrateKbps,
                rule.format,
            )
        }
    }

    private fun isNewer(candidate: AutomaticRule, current: AutomaticRule): Boolean =
        candidate.updatedAt > current.updatedAt ||
            (candidate.updatedAt == current.updatedAt && candidate.id > current.id)

    private suspend fun enqueue(
        rule: AutomaticRule,
        tracks: List<AutomaticRuleTrack>,
        presentMediaKeys: Set<String>,
        winningKeys: Map<SongIdentity, String>,
    ): AutomaticRuleExpansion {
        var accepted = 0
        var alreadyPresent = 0
        var blocked = 0
        var unsupported = 0
        var destinationConflicts = 0
        val blockedBy = mutableMapOf<ProviderStatus, Int>()
        val wanted = LinkedHashSet<String>(tracks.size)

        for (track in tracks) {
            val mediaKey = DownloadKeys.mediaKey(
                rule.providerId,
                track.externalId,
                rule.transcodeBitrateKbps,
                rule.format,
            )
            if (winningKeys[SongIdentity(rule.providerId, track.externalId)] != mediaKey) continue
            wanted += mediaKey
            if (mediaKey in presentMediaKeys) {
                alreadyPresent++
                continue
            }
            when (
                val result = enqueuer.enqueue(
                    providerId = rule.providerId,
                    externalId = track.externalId,
                    title = track.title,
                    imagePath = track.imagePath,
                    transcodeBitrateKbps = rule.transcodeBitrateKbps,
                    format = rule.format,
                    tempCache = true,
                    autoRuleId = rule.id,
                )
            ) {
                DownloadEnqueueResult.Enqueued -> accepted++
                DownloadEnqueueResult.SkippedDownloadUnsupported -> unsupported++
                is DownloadEnqueueResult.BlockedByStatus -> {
                    blocked++
                    blockedBy[result.status] = (blockedBy[result.status] ?: 0) + 1
                }
                is DownloadEnqueueResult.NeedsDestinationChange -> destinationConflicts++
            }
        }

        return AutomaticRuleExpansion(
            rule = rule,
            outcome = AutomaticRuleExpansionOutcome.EXPANDED,
            wantedCount = tracks.size,
            acceptedCount = accepted,
            blockedCount = blocked,
            blockedBy = blockedBy,
            unsupportedCount = unsupported,
            destinationConflictCount = destinationConflicts,
            wantedMediaKeys = wanted,
            alreadyPresentCount = alreadyPresent,
        )
    }
}
