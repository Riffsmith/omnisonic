package app.omnisonic.feature.downloads.workspace

import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.dao.ProviderIdentityRow
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadRuleEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.downloads.AutomaticRuleKind
import app.omnisonic.core.data.downloads.DownloadDestination
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var port: FakeDownloadsPort
    private lateinit var viewModel: DownloadsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        port = FakeDownloadsPort()
        viewModel = DownloadsViewModel(port)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scoped reads map rows and never expose a hidden provider`() = runTest(dispatcher) {
        port.providerRows.value = listOf(ProviderIdentityRow(1, "One"), ProviderIdentityRow(2, "Hidden"))
        port.hiddenIds.value = setOf(2L)
        port.queue.value = listOf(
            download(id = 3, providerId = 1, status = DownloadStatus.ACTIVE, progress = 41),
            download(id = 4, providerId = 2, status = DownloadStatus.PENDING),
            download(id = 5, providerId = 1, status = DownloadStatus.FAILED, errorCount = 6),
        )
        port.offline.value = listOf(
            OfflineFileEntity(
                id = 9,
                providerId = 1,
                mediaType = "track",
                sourceKey = "s-1",
                offlinePath = "/offline/1",
                title = "Offline one",
                sizeBytes = 2_000,
                downloadedAt = 10,
                tempCache = true,
                autoRule = "rule",
            ),
            OfflineFileEntity(
                id = 10,
                providerId = 2,
                mediaType = "track",
                sourceKey = "s-2",
                offlinePath = "/offline/2",
            ),
        )
        port.rules.value = listOf(
            DownloadRuleEntity(id = 1, providerId = 1, kind = AutomaticRuleKind.FAVORITES.name, targetExternalId = "", createdAt = 1, updatedAt = 1),
            DownloadRuleEntity(id = 2, providerId = 2, kind = AutomaticRuleKind.FAVORITES.name, targetExternalId = "", createdAt = 1, updatedAt = 1),
        )

        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.queue.map { it.id }).containsExactly(3L, 5L)
        assertThat(state.queue.first().providerName).isEqualTo("One")
        assertThat(state.queue.first().actions.canPause).isTrue()
        assertThat(state.queue.first().indeterminate).isFalse()
        assertThat(state.queue.last().parked).isTrue()
        assertThat(state.queue.last().actions.canRetry).isTrue()
        assertThat(state.offline.map { it.id }).containsExactly(9L)
        assertThat(state.offline.single().permanent).isFalse()
        assertThat(state.offline.single().automatic).isTrue()
        assertThat(state.rules.map { it.id }).containsExactly(1L)
        assertThat(state.providerNames.keys).containsExactly(1L)
    }

    @Test
    fun `a repeated row command while the first is running is ignored`() = runTest(dispatcher) {
        port.queue.value = listOf(download(id = 3, providerId = 1, status = DownloadStatus.FAILED))
        port.pauseGate = CompletableDeferred()
        advanceUntilIdle()

        viewModel.pauseItem(3)
        viewModel.pauseItem(3)
        advanceUntilIdle()

        assertThat(port.pauseCalls).containsExactly(3L)
        assertThat(viewModel.state.value.busyRowIds).containsExactly(3L)

        port.pauseGate?.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.state.value.busyRowIds).isEmpty()
    }

    @Test
    fun `resume all releases only rows the user has not paused`() = runTest(dispatcher) {
        port.queue.value = listOf(
            download(id = 1, providerId = 1, status = DownloadStatus.PAUSED),
            download(id = 2, providerId = 1, status = DownloadStatus.USER_PAUSED),
        )
        port.pausedFlow.value = true
        advanceUntilIdle()

        viewModel.resumeAll()
        advanceUntilIdle()

        assertThat(port.resumeAllCalls).isEqualTo(1)
        val rows = viewModel.state.value.queue.associateBy { it.id }
        assertThat(rows.getValue(1).status).isEqualTo(DownloadStatus.PAUSED)
        assertThat(rows.getValue(2).status).isEqualTo(DownloadStatus.USER_PAUSED)
        assertThat(rows.getValue(2).actions.canResume).isTrue()
    }

    @Test
    fun `preferred destination writes are forwarded and survive a failed write`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.setPreferredDestination(DownloadDestination.EXPORT)
        advanceUntilIdle()

        assertThat(port.destinationWrites).containsExactly(DownloadDestination.EXPORT)
        assertThat(viewModel.state.value.preferredDestination).isEqualTo(DownloadDestination.EXPORT)

        port.failDestinationWrite = true
        viewModel.setPreferredDestination(DownloadDestination.OFFLINE)
        advanceUntilIdle()

        assertThat(viewModel.state.value.preferredDestination).isEqualTo(DownloadDestination.EXPORT)
    }

    @Test
    fun `delete and rule failures surface typed notices`() = runTest(dispatcher) {
        port.deleteResult = false
        advanceUntilIdle()

        viewModel.deleteOffline(9)
        advanceUntilIdle()

        assertThat(viewModel.state.value.notice).isEqualTo(DownloadsNotice.OFFLINE_DELETE_FAILED)
        viewModel.dismissNotice()
        advanceUntilIdle()
        assertThat(viewModel.state.value.notice).isNull()

        port.removeRuleResult = true
        viewModel.removeRule(4)
        advanceUntilIdle()

        assertThat(viewModel.state.value.notice).isEqualTo(DownloadsNotice.RULE_REMOVED)
    }

    @Test
    fun `rule candidates load once and label matching rules`() = runTest(dispatcher) {
        port.candidates = DownloadRuleCandidatesUi(
            playlists = listOf(DownloadRuleCandidateUi(1, "pl-1", "Road trip")),
        )
        port.rules.value = listOf(
            DownloadRuleEntity(id = 4, providerId = 1, kind = AutomaticRuleKind.PLAYLIST.name, targetExternalId = "pl-1", createdAt = 1, updatedAt = 1),
        )
        advanceUntilIdle()

        viewModel.loadRuleCandidates()
        advanceUntilIdle()

        assertThat(viewModel.state.value.candidates.candidates?.playlists).hasSize(1)
        assertThat(viewModel.state.value.rules.single().targetLabel).isEqualTo("Road trip")
    }

    @Test
    fun `a terminal read failure is retryable without losing the loaded chrome`() = runTest(dispatcher) {
        port.providerRows.value = listOf(ProviderIdentityRow(1, "One"))
        port.failReads = true
        advanceUntilIdle()

        assertThat(viewModel.state.value.failed).isTrue()
        assertThat(viewModel.state.value.loading).isFalse()

        port.failReads = false
        port.queue.value = listOf(download(id = 1, providerId = 1, status = DownloadStatus.PENDING))
        viewModel.reload()
        advanceUntilIdle()

        assertThat(viewModel.state.value.failed).isFalse()
        assertThat(viewModel.state.value.queue).hasSize(1)
        assertThat(viewModel.state.value.providerNames).containsEntry(1L, "One")
    }

    private fun download(
        id: Long,
        providerId: Long,
        status: DownloadStatus,
        progress: Int = 0,
        errorCount: Int = 0,
    ) = DownloadItemEntity(
        id = id,
        mediaKey = "key-$id",
        providerId = providerId,
        status = status.name,
        progressPercent = progress,
        errorCount = errorCount,
        createdAt = 1,
        updatedAt = 1,
    )

    private class FakeDownloadsPort : DownloadsPort {

        val queue = MutableStateFlow<List<DownloadItemEntity>>(emptyList())
        val offline = MutableStateFlow<List<OfflineFileEntity>>(emptyList())
        val rules = MutableStateFlow<List<DownloadRuleEntity>>(emptyList())
        val providerRows = MutableStateFlow<List<ProviderIdentityRow>>(emptyList())
        val hiddenIds = MutableStateFlow<Set<Long>>(emptySet())
        val pausedFlow = MutableStateFlow(false)
        val preferredFlow = MutableStateFlow(DownloadDestination.OFFLINE)

        val pauseCalls = mutableListOf<Long>()
        var pauseGate: CompletableDeferred<Unit>? = null
        var resumeAllCalls = 0
        val destinationWrites = mutableListOf<DownloadDestination>()
        var failDestinationWrite = false
        var deleteResult = true
        var removeRuleResult = true
        var candidates = DownloadRuleCandidatesUi()
        var failReads = false

        override fun queue(scope: ProfileScope): Flow<List<DownloadItemEntity>> = flow {
            if (failReads) throw IOException("read failed")
            emitAll(queue.map { rows -> rows.filter { it.providerId !in scope.excludedProviderIds } })
        }

        override fun offline(scope: ProfileScope): Flow<List<OfflineFileEntity>> =
            offline.map { rows -> rows.filter { it.providerId !in scope.excludedProviderIds } }

        override fun rules(scope: ProfileScope): Flow<List<DownloadRuleEntity>> =
            rules.map { rows -> rows.filter { it.providerId !in scope.excludedProviderIds } }

        override val providers: Flow<List<ProviderIdentityRow>> = providerRows
        override val hiddenProviderIds: Flow<Set<Long>> = hiddenIds
        override val globallyPaused: Flow<Boolean> = pausedFlow
        override val preferredDestination: Flow<DownloadDestination> = preferredFlow

        override suspend fun pauseAll() = Unit
        override suspend fun resumeAll() {
            resumeAllCalls++
            queue.update { rows ->
                rows.map { row ->
                    if (row.status == DownloadStatus.USER_PAUSED.name) row
                    else row.copy(status = DownloadStatus.PAUSED.name)
                }
            }
            pausedFlow.value = false
        }

        override suspend fun pauseItem(rowId: Long): Boolean {
            pauseCalls += rowId
            pauseGate?.await()
            return true
        }

        override suspend fun resumeItem(rowId: Long): Boolean = true
        override suspend fun retry(rowId: Long): Boolean = true
        override suspend fun retryAll(): Int = 0
        override suspend fun cancel(rowId: Long): Boolean = true
        override suspend fun deleteOffline(offlineId: Long): Boolean = deleteResult
        override suspend fun setOfflinePermanent(offlineId: Long, permanent: Boolean): Boolean = true

        override suspend fun addRule(
            providerId: Long,
            kind: AutomaticRuleKind,
            targetExternalId: String,
        ): Boolean = true

        override suspend fun removeRule(ruleId: Long): Boolean = removeRuleResult

        override suspend fun ruleCandidates(scope: ProfileScope): DownloadRuleCandidatesUi = candidates

        override suspend fun setPreferredDestination(destination: DownloadDestination) {
            destinationWrites += destination
            if (!failDestinationWrite) preferredFlow.value = destination
        }
    }
}
