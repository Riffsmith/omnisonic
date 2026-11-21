package app.omnisonic.feature.downloads.workspace

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.dao.ProviderIdentityRow
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadRuleEntity
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.downloads.AutomaticRuleAddOutcome
import app.omnisonic.core.data.downloads.AutomaticRuleKind
import app.omnisonic.core.data.downloads.AutomaticRuleRepository
import app.omnisonic.core.data.downloads.DownloadDestination
import app.omnisonic.core.data.downloads.DownloadQueueSettingsStore
import app.omnisonic.core.data.downloads.DownloadWorkStart
import app.omnisonic.core.data.downloads.DownloadWorkspaceRepository
import app.omnisonic.core.data.downloads.OfflineMediaDeletion
import app.omnisonic.core.data.search.ProviderVisibilityStore
import app.omnisonic.feature.downloads.service.DownloadUserActions
import kotlinx.coroutines.flow.Flow

/**
 * Production binding of [DownloadsPort]. Queue/offline/rules reads stay scoped
 * through [DownloadWorkspaceRepository]; commands go through the foreground
 * runtime's one coordinator. Rule edits wake the download session only after
 * the write commits, so editing a rule while the service is stopped still runs
 * the observed expand/prune pass (`.agents/plans/phase-7-tasks.md` §Task 9).
 */
class RepositoryDownloadsPort(
    private val workspace: DownloadWorkspaceRepository,
    private val rules: AutomaticRuleRepository,
    private val settingsStore: DownloadQueueSettingsStore,
    private val visibility: ProviderVisibilityStore,
    private val actions: DownloadUserActions,
    private val deletion: OfflineMediaDeletion,
    private val workStart: DownloadWorkStart,
) : DownloadsPort {

    override fun queue(scope: ProfileScope): Flow<List<DownloadItemEntity>> = workspace.queue(scope)

    override fun offline(scope: ProfileScope): Flow<List<OfflineFileEntity>> = workspace.offline(scope)

    override fun rules(scope: ProfileScope): Flow<List<DownloadRuleEntity>> = workspace.rules(scope)

    override val providers: Flow<List<ProviderIdentityRow>> = workspace.providers

    override val hiddenProviderIds: Flow<Set<Long>> = visibility.hiddenProviderIds

    override val globallyPaused: Flow<Boolean> = settingsStore.globallyPaused

    override val preferredDestination: Flow<DownloadDestination> =
        settingsStore.preferredDestination

    override suspend fun pauseAll() = actions.pauseAll()

    override suspend fun resumeAll() = actions.resumeAll()

    override suspend fun pauseItem(rowId: Long): Boolean = actions.pauseItem(rowId)

    override suspend fun resumeItem(rowId: Long): Boolean = actions.resumeItem(rowId)

    override suspend fun retry(rowId: Long): Boolean = actions.retry(rowId)

    override suspend fun retryAll(): Int = actions.retryAll()

    override suspend fun cancel(rowId: Long): Boolean = actions.cancel(rowId)

    override suspend fun deleteOffline(offlineId: Long): Boolean =
        deletion.deleteOffline(offlineId).deleted

    override suspend fun setOfflinePermanent(offlineId: Long, permanent: Boolean): Boolean =
        workspace.setManualOwnership(offlineId, permanent)

    override suspend fun addRule(
        providerId: Long,
        kind: AutomaticRuleKind,
        targetExternalId: String,
    ): Boolean {
        val outcome = rules.add(providerId, kind, targetExternalId)
        if (outcome == AutomaticRuleAddOutcome.ADDED) workStart.requestStart()
        return outcome != AutomaticRuleAddOutcome.INVALID
    }

    override suspend fun removeRule(ruleId: Long): Boolean {
        val rule = rules.findById(ruleId, ProfileScope.Unrestricted) ?: return false
        if (!rules.remove(rule.providerId, rule.kind, rule.targetExternalId)) return false
        workStart.requestStart()
        return true
    }

    override suspend fun ruleCandidates(scope: ProfileScope): DownloadRuleCandidatesUi {
        val candidates = workspace.ruleCandidates(scope)
        return DownloadRuleCandidatesUi(
            playlists = candidates.playlists.map {
                DownloadRuleCandidateUi(it.providerId, it.externalId, it.title)
            },
            albums = candidates.albums.map {
                DownloadRuleCandidateUi(it.providerId, it.externalId, it.title)
            },
        )
    }

    override suspend fun setPreferredDestination(destination: DownloadDestination) {
        settingsStore.setPreferredDestination(destination)
    }
}
