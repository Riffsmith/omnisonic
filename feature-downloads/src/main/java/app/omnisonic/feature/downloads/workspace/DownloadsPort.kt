package app.omnisonic.feature.downloads.workspace

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.dao.ProviderIdentityRow
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadRuleEntity
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.downloads.AutomaticRuleKind
import app.omnisonic.core.data.downloads.DownloadDestination
import kotlinx.coroutines.flow.Flow

/**
 * The workspace's complete data/command surface (task 9a/9b). It keeps Room and
 * the runtime out of the view model so state derivation and command races are
 * testable on the JVM. `queue`, `offline` and `rules` take the real UI
 * [ProfileScope], so a hidden provider never appears here; internal workers
 * keep their own unrestricted reads.
 */
interface DownloadsPort {

    fun queue(scope: ProfileScope): Flow<List<DownloadItemEntity>>

    fun offline(scope: ProfileScope): Flow<List<OfflineFileEntity>>

    fun rules(scope: ProfileScope): Flow<List<DownloadRuleEntity>>

    val providers: Flow<List<ProviderIdentityRow>>

    val hiddenProviderIds: Flow<Set<Long>>

    val globallyPaused: Flow<Boolean>

    val preferredDestination: Flow<DownloadDestination>

    suspend fun pauseAll()

    suspend fun resumeAll()

    suspend fun pauseItem(rowId: Long): Boolean

    suspend fun resumeItem(rowId: Long): Boolean

    suspend fun retry(rowId: Long): Boolean

    suspend fun retryAll(): Int

    suspend fun cancel(rowId: Long): Boolean

    suspend fun deleteOffline(offlineId: Long): Boolean

    suspend fun setOfflinePermanent(offlineId: Long, permanent: Boolean): Boolean

    suspend fun addRule(providerId: Long, kind: AutomaticRuleKind, targetExternalId: String): Boolean

    suspend fun removeRule(ruleId: Long): Boolean

    suspend fun ruleCandidates(scope: ProfileScope): DownloadRuleCandidatesUi

    suspend fun setPreferredDestination(destination: DownloadDestination)
}
