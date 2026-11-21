package app.omnisonic.shell

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.dao.ProviderIdentityRow
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadRuleEntity
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.downloads.AutomaticRuleKind
import app.omnisonic.core.data.downloads.DownloadDestination
import app.omnisonic.feature.downloads.workspace.DownloadRuleCandidatesUi
import app.omnisonic.feature.downloads.workspace.DownloadsPort
import app.omnisonic.feature.downloads.workspace.DownloadsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Empty workspace port so shell tests can resolve the downloads route without Hilt/Room. */
class TestDownloadsPort : DownloadsPort {

    override fun queue(scope: ProfileScope): Flow<List<DownloadItemEntity>> = flowOf(emptyList())
    override fun offline(scope: ProfileScope): Flow<List<OfflineFileEntity>> = flowOf(emptyList())
    override fun rules(scope: ProfileScope): Flow<List<DownloadRuleEntity>> = flowOf(emptyList())
    override val providers: Flow<List<ProviderIdentityRow>> = flowOf(emptyList())
    override val hiddenProviderIds: Flow<Set<Long>> = flowOf(emptySet())
    override val globallyPaused: Flow<Boolean> = flowOf(false)
    override val preferredDestination: Flow<DownloadDestination> = flowOf(DownloadDestination.OFFLINE)

    override suspend fun pauseAll() = Unit
    override suspend fun resumeAll() = Unit
    override suspend fun pauseItem(rowId: Long): Boolean = true
    override suspend fun resumeItem(rowId: Long): Boolean = true
    override suspend fun retry(rowId: Long): Boolean = true
    override suspend fun retryAll(): Int = 0
    override suspend fun cancel(rowId: Long): Boolean = true
    override suspend fun deleteOffline(offlineId: Long): Boolean = true
    override suspend fun setOfflinePermanent(offlineId: Long, permanent: Boolean): Boolean = true
    override suspend fun addRule(
        providerId: Long,
        kind: AutomaticRuleKind,
        targetExternalId: String,
    ): Boolean = true
    override suspend fun removeRule(ruleId: Long): Boolean = true
    override suspend fun ruleCandidates(scope: ProfileScope): DownloadRuleCandidatesUi =
        DownloadRuleCandidatesUi()
    override suspend fun setPreferredDestination(destination: DownloadDestination) = Unit
}

fun testDownloadsViewModel(): DownloadsViewModel = DownloadsViewModel(TestDownloadsPort())
