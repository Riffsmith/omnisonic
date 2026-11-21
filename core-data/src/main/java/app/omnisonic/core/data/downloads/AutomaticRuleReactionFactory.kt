package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.providers.ProviderTraitsSource
import app.omnisonic.core.data.sync.LibrarySyncStatusRepository
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderTraits

/**
 * Builds the task-8a reaction coordinator and its task-8b prune pass around the
 * download session's one coordinator, so automatic enqueue and cancellation
 * share the global-pause lock and the production run gate. It stays in
 * `:core-data` so `:feature-downloads` never needs the Room type on its compile
 * path.
 */
class AutomaticRuleReactionFactory(
    private val database: OmnisonicDatabase,
    private val traitsFor: (String) -> ProviderTraits,
    private val syncStatus: LibrarySyncStatusRepository,
    private val generations: AutomaticRuleGenerationStore,
    private val storage: OfflineStorage,
    private val logger: OmnisonicLogger,
    private val ownership: OfflineOwnershipGuard = OfflineOwnershipGuard(),
) {

    fun create(
        coordinator: DownloadWorkerCoordinator,
        runGate: ProviderRunGate,
    ): AutomaticRuleReactionCoordinator {
        val rules = AutomaticRuleRepository(database)
        return AutomaticRuleReactionCoordinator(
            rules = rules,
            expander = AutomaticRuleExpander(
                rules = rules,
                tracks = DatabaseAutomaticRuleTrackSource(database.automaticRuleCatalogDao()),
                enqueuer = DownloadEnqueuer(
                    db = database,
                    coordinator = coordinator,
                    traitsSource = ProviderTraitsSource(traitsFor),
                    runGate = runGate,
                ),
            ),
            changes = AutomaticRuleChangeObserver(database.automaticRuleChangeDao()).observe(),
            syncStatus = syncStatus,
            generations = generations,
            offline = database.offlineFileDao(),
            syncCompletions = syncStatus.completions,
            pruner = AutomaticRulePruner(
                db = database,
                storage = storage,
                rules = rules,
                syncStatus = syncStatus,
                generations = generations,
                cancellation = object : DownloadQueueCancellation {
                    override suspend fun cancel(rowId: Long): Boolean = coordinator.cancel(rowId)

                    override suspend fun cancelIfAutomaticRolling(rowId: Long): Boolean =
                        coordinator.cancelIfAutomaticRolling(rowId)
                },
                logger = logger,
                ownership = ownership,
            ),
        )
    }
}
