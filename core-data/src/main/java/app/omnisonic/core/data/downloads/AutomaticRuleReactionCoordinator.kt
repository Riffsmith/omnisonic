package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.dao.OfflineFileDao
import app.omnisonic.core.data.sync.LibrarySyncStatusRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes and coalesces automatic-rule change triggers into expand-then-
 * hand-off passes (task 8a). Triggers are level signals: any number of table
 * changes collapse into at most one pending pass, and [passMutex] keeps two
 * callers or a slow pass from expanding concurrently.
 *
 * A pass reads every current rule with [ProfileScope.Unrestricted] (visibility
 * must never shrink the internal wanted set), expands them through the task-7b
 * [AutomaticRuleExpander], and publishes [AutomaticRuleGeneration] only when
 * every rule resolved, the rule snapshot is unchanged, and no synced provider
 * is blocked. Otherwise it marks the hand-off stale; a sync that blocks a pass
 * is retried from [syncCompletions], so a provider finishing its walk always
 * requests a fresh expansion even when no other table changed. When a [pruner]
 * is supplied, the destructive task-8b pass runs immediately after publication
 * under the same pass mutex; it can only see the generation it just published,
 * and a sync/rule change at its own guards invalidates the hand-off.
 */
class AutomaticRuleReactionCoordinator(
    private val rules: AutomaticRuleRepository,
    private val expander: AutomaticRuleExpander,
    private val changes: Flow<AutomaticRuleChangeSource>,
    private val syncStatus: LibrarySyncStatusRepository,
    private val generations: AutomaticRuleGenerationStore,
    private val offline: OfflineFileDao,
    private val pruner: AutomaticRulePruner? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val syncCompletions: Flow<Long> = emptyFlow(),
) {

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val passMutex = Mutex()

    /**
     * First pass of a session, run by the runtime before its worker loop. An
     * empty queue must not let the session go idle before its rules expanded, so
     * this is awaited rather than left to the concurrent loop (task 8a).
     */
    suspend fun runInitialPass() {
        try {
            runPass()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            generations.markStale(AutomaticRuleGenerationStaleReason.PASS_FAILED)
        }
    }

    /** Wakes the loop; a request made during a pass leaves exactly one pending pass. */
    fun requestPass() {
        requests.trySend(Unit)
    }

    /**
     * Collects table changes, sync completions and explicit requests, and runs
     * the serialized loop until cancellation. The observer jobs share this
     * scope, so cancelling [run] stops them too.
     */
    suspend fun run(): Unit = coroutineScope {
        val observer = launch {
            changes.collect { requestPass() }
        }
        val syncObserver = launch {
            syncCompletions.collect { requestPass() }
        }
        try {
            while (true) {
                requests.receive()
                try {
                    runPass()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    generations.markStale(AutomaticRuleGenerationStaleReason.PASS_FAILED)
                }
            }
        } finally {
            observer.cancel()
            syncObserver.cancel()
        }
    }

    suspend fun runPass(): Unit = passMutex.withLock {
        val snapshot = try {
            rules.list(ProfileScope.Unrestricted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            generations.markStale(AutomaticRuleGenerationStaleReason.RULE_READ_FAILED)
            return@withLock
        }
        val providers = snapshot.mapTo(LinkedHashSet()) { it.providerId }

        // A running walk is a yield, not a wait: the completion trigger runs the
        // fresh pass, so this loop never holds the mutex for the sync's duration.
        if (syncRunning(providers)) {
            generations.markStale(AutomaticRuleGenerationStaleReason.SYNC_RUNNING)
            return@withLock
        }

        val expansions = expander.expand(
            currentRules = snapshot,
            scope = ProfileScope.Unrestricted,
            presentMediaKeys = presentMediaKeys(providers),
        )

        val fresh = try {
            rules.list(ProfileScope.Unrestricted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            generations.markStale(AutomaticRuleGenerationStaleReason.RULE_READ_FAILED)
            return@withLock
        }
        if (fresh != snapshot) {
            generations.markStale(AutomaticRuleGenerationStaleReason.RULES_CHANGED)
            requestPass()
            return@withLock
        }

        if (syncRunning(providers)) {
            generations.markStale(AutomaticRuleGenerationStaleReason.SYNC_RUNNING)
            return@withLock
        }

        if (expansions.any { it.outcome != AutomaticRuleExpansionOutcome.EXPANDED }) {
            generations.markStale(AutomaticRuleGenerationStaleReason.INCOMPLETE_EXPANSION)
            return@withLock
        }

        generations.publish(
            AutomaticRuleGeneration(
                sequence = (generations.current?.sequence ?: 0L) + 1,
                verifiedAtMs = nowMs(),
                ruleCount = snapshot.size,
                wantedMediaKeys = expansions.flatMapTo(LinkedHashSet()) { it.wantedMediaKeys },
                providerIds = providers,
                expansions = expansions,
            ),
        )
        prunePublishedGeneration()
    }

    /**
     * The destructive pass is part of the same serialized pass, so it always
     * runs behind the generation just published. A later trigger is not
     * ignored: if it lands during pruning, the pruner's own guards stop the
     * remaining groups and this loop recomputes before touching anything else.
     */
    private suspend fun prunePublishedGeneration() {
        val result = pruner?.prune() ?: return
        when (result.status) {
            // The provider's completion trigger runs the fresh pass; requesting
            // one now would only re-yield while the walk is still running.
            AutomaticRulePruneStatus.SYNC_RUNNING ->
                generations.markStale(AutomaticRuleGenerationStaleReason.SYNC_RUNNING)

            AutomaticRulePruneStatus.RULES_CHANGED -> {
                generations.markStale(AutomaticRuleGenerationStaleReason.RULES_CHANGED)
                requestPass()
            }

            AutomaticRulePruneStatus.RULE_READ_FAILED ->
                generations.markStale(AutomaticRuleGenerationStaleReason.RULE_READ_FAILED)

            AutomaticRulePruneStatus.GENERATION_CHANGED -> requestPass()

            AutomaticRulePruneStatus.COMPLETED,
            AutomaticRulePruneStatus.NOT_VERIFIED,
            -> Unit
        }
    }

    /**
     * Offline rows use the queue media key as `sourceKey`, so an exact match is
     * the same variant and must not be downloaded again. Presence only; file
     * verification and pruning stay with tasks 5b and 8b.
     */
    private suspend fun presentMediaKeys(providers: Set<Long>): Set<String> =
        providers.flatMapTo(HashSet()) { providerId ->
            offline.selectByProvider(providerId).map { it.sourceKey }
        }

    private fun syncRunning(providers: Set<Long>): Boolean =
        providers.any(syncStatus::isRunning)
}
