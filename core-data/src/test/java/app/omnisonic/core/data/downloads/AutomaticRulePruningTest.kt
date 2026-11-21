package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.DatabaseTestBase
import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.silentTestLogger
import app.omnisonic.core.data.sync.LibrarySyncStatusRepository
import app.omnisonic.provider.api.ProviderRunGate
import app.omnisonic.provider.api.ProviderStatus
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CancellationException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticRulePruningTest : DatabaseTestBase() {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private var now = 10_000L
    private var sequence = 0L
    private lateinit var settings: DownloadQueueSettingsStore
    private lateinit var root: File
    private lateinit var storage: OfflineStorage
    private val sync = LibrarySyncStatusRepository()
    private val generations = AutomaticRuleGenerationStore()

    private fun prepareStorage(roots: OfflineRootSource? = null): OfflineStorage {
        root = File(temp.root, "root").apply { mkdirs() }
        settings = DownloadQueueSettingsStore.inMemory()
        storage = OfflineStorage(
            roots = roots ?: OfflineRootSource { listOf(OfflineRoot("test-root", root)) },
            settings = settings,
            usableSpace = { OfflinePathRules.MIN_FREE_SPACE_BYTES * 10 },
            writableProbe = { true },
        )
        return storage
    }

    private fun rules(at: Long = now) = AutomaticRuleRepository(db, nowMs = { at })

    private fun pruner(
        over: OfflineStorage = storage,
        cancellation: DownloadQueueCancellation = DownloadQueueCancellation { rowId ->
            downloadDao.deleteById(rowId) == 1
        },
    ) = AutomaticRulePruner(
        db = db,
        storage = over,
        rules = rules(),
        syncStatus = sync,
        generations = generations,
        cancellation = cancellation,
        logger = silentTestLogger,
    )

    private fun coordinator(): DownloadWorkerCoordinator = DownloadWorkerCoordinator(
        repository = DownloadQueueRepository(downloadDao),
        settings = settings,
        runGate = ProviderRunGate { ProviderStatus.OK },
        workerCount = 1,
        nowMs = { 4_000L },
    )

    private suspend fun publish(
        wanted: Set<String> = emptySet(),
        ruleSnapshot: List<AutomaticRule>? = null,
    ) {
        val current = ruleSnapshot ?: rules().list(ProfileScope.Unrestricted)
        val expansions = current.map { rule ->
            AutomaticRuleExpansion(rule, AutomaticRuleExpansionOutcome.EXPANDED)
        }
        generations.publish(
            AutomaticRuleGeneration(
                sequence = ++sequence,
                verifiedAtMs = now,
                ruleCount = current.size,
                wantedMediaKeys = wanted,
                providerIds = current.mapTo(LinkedHashSet()) { it.providerId },
                expansions = expansions,
            ),
        )
    }

    private suspend fun addFavoritesRule(
        providerId: Long = 1,
        bitrateKbps: Int? = null,
        at: Long = now,
    ): AutomaticRule {
        rules(at).add(providerId, AutomaticRuleKind.FAVORITES, transcodeBitrateKbps = bitrateKbps)
        return rules().list(ProfileScope.Unrestricted).single {
            it.providerId == providerId && it.kind == AutomaticRuleKind.FAVORITES
        }
    }

    private suspend fun queue(
        providerId: Long = 1,
        externalId: String = "song-1",
        bitrateKbps: Int? = null,
        status: DownloadStatus = DownloadStatus.PENDING,
        autoRuleId: Long? = 1L,
        tempCache: Boolean = true,
        exportTarget: String? = null,
    ): DownloadItemEntity {
        val key = DownloadKeys.mediaKey(providerId, externalId, bitrateKbps, null)
        downloadDao.upsert(
            DownloadItemEntity(
                mediaKey = key,
                providerId = providerId,
                status = status.name,
                title = externalId,
                transcodeBitrateKbps = bitrateKbps,
                tempCache = tempCache,
                exportTarget = exportTarget,
                autoRuleId = autoRuleId,
                createdAt = 1_000,
                updatedAt = 1_000,
            ),
        )
        return downloadDao.selectByMediaKey(key) ?: error("queue row missing")
    }

    private data class SeededOffline(
        val row: OfflineFileEntity,
        val file: File,
    )

    private suspend fun offline(
        providerId: Long = 1,
        externalId: String = "song-1",
        bitrateKbps: Int? = null,
        autoRule: String? = "1",
        tempCache: Boolean = true,
        withFile: Boolean = true,
        sourceKeyOverride: String? = null,
    ): SeededOffline {
        val plan = (
            storage.planPath(
                OfflineMediaIdentity(
                    providerId = providerId,
                    providerPath = "/music/library/$externalId.flac",
                    transcodeBitrateKbps = bitrateKbps,
                ),
            ) as OfflinePathResult.Planned
            ).plan
        val file = File(root, plan.relativePath)
        if (withFile) {
            file.parentFile?.mkdirs()
            file.writeBytes(ByteArray(16))
        }
        val key = sourceKeyOverride ?: DownloadKeys.mediaKey(providerId, externalId, bitrateKbps, null)
        val id = offlineFileDao.upsert(
            OfflineFileEntity(
                providerId = providerId,
                mediaType = "SONG",
                sourceKey = key,
                offlinePath = plan.relativePath,
                sizeBytes = if (withFile) 16 else null,
                autoRule = autoRule,
                tempCache = tempCache,
                externalId = if (sourceKeyOverride == null) externalId else null,
                downloadedAt = now,
            ),
        )
        return SeededOffline(offlineFileDao.selectById(id)!!, file)
    }

    @Test
    fun `no verified generation deletes nothing`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val queueRow = queue()
        val offlineRow = offline()

        val absent = pruner().prune()
        assertThat(absent.status).isEqualTo(AutomaticRulePruneStatus.NOT_VERIFIED)

        publish()
        generations.markStale(AutomaticRuleGenerationStaleReason.RULES_CHANGED)
        val stale = pruner().prune()

        assertThat(stale.status).isEqualTo(AutomaticRulePruneStatus.NOT_VERIFIED)
        assertThat(downloadDao.selectById(queueRow.id)).isNotNull()
        assertThat(offlineFileDao.selectById(offlineRow.row.id)).isNotNull()
        assertThat(offlineRow.file.isFile).isTrue()
    }

    @Test
    fun `every known unwanted queue status is cancelled`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val statusRows = DownloadStatus.entries.map { status ->
            queue(externalId = "status-${status.name}", status = status)
        }
        val manualRolling = queue(externalId = "manual-rolling", autoRuleId = null)
        val automaticPermanent = queue(externalId = "auto-permanent", tempCache = false)
        val exported = queue(
            externalId = "exported",
            exportTarget = "MEDIASTORE_DOWNLOADS",
        )
        publish()

        val result = pruner().prune()

        assertThat(result.status).isEqualTo(AutomaticRulePruneStatus.COMPLETED)
        assertThat(result.counts.queueRowsCancelled).isEqualTo(statusRows.size)
        statusRows.forEach { assertThat(downloadDao.selectById(it.id)).isNull() }
        assertThat(downloadDao.selectById(manualRolling.id)).isNotNull()
        assertThat(downloadDao.selectById(automaticPermanent.id)).isNotNull()
        assertThat(downloadDao.selectById(exported.id)).isNotNull()
    }

    @Test
    fun `an active unwanted transfer receives the cancel signal`() = runDbTest {
        prepareStorage()
        newProvider(1)
        queue(externalId = "active")
        val coordinator = coordinator()
        val lease = coordinator.claimNext(ProfileScope.Unrestricted)!!
        publish()

        val result = pruner(
            cancellation = DownloadQueueCancellation { rowId -> coordinator.cancel(rowId) },
        ).prune()

        assertThat(result.counts.queueRowsCancelled).isEqualTo(1)
        assertThat(coordinator.interruptionFor(lease).await())
            .isEqualTo(DownloadInterruption.CANCEL)
        assertThat(downloadDao.selectById(lease.rowId)).isNull()
    }

    @Test
    fun `a wanted automatic row survives while an unwanted one is cancelled`() = runDbTest {
        prepareStorage()
        newProvider(1)
        newProvider(2)
        val wantedKey = DownloadKeys.mediaKey(1, "wanted", null, null)
        val wanted = queue(externalId = "wanted")
        val unwanted = queue(externalId = "unwanted")
        val otherProviderWanted = queue(providerId = 2, externalId = "other")
        publish(
            wanted = setOf(
                wantedKey,
                DownloadKeys.mediaKey(2, "other", null, null),
            ),
        )

        val result = pruner().prune()

        assertThat(result.counts.queueRowsCancelled).isEqualTo(1)
        assertThat(downloadDao.selectById(unwanted.id)).isNull()
        assertThat(downloadDao.selectById(wanted.id)).isNotNull()
        assertThat(downloadDao.selectById(otherProviderWanted.id)).isNotNull()
    }

    @Test
    fun `manual and permanent offline rows are never candidates`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val automatic = offline(externalId = "auto")
        val manualRolling = offline(externalId = "manual", autoRule = null, tempCache = true)
        val permanent = offline(externalId = "permanent", tempCache = false)
        publish()

        val result = pruner().prune()

        assertThat(result.counts.offlineRowsDeleted).isEqualTo(1)
        assertThat(offlineFileDao.selectById(automatic.row.id)).isNull()
        assertThat(automatic.file.exists()).isFalse()
        assertThat(offlineFileDao.selectById(manualRolling.row.id)).isNotNull()
        assertThat(manualRolling.file.isFile).isTrue()
        assertThat(offlineFileDao.selectById(permanent.row.id)).isNotNull()
        assertThat(permanent.file.isFile).isTrue()
    }

    @Test
    fun `a failed file delete leaves the row retryable`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val seeded = offline(externalId = "blocked")
        seeded.file.delete()
        seeded.file.mkdirs()
        File(seeded.file, "child").writeBytes(ByteArray(4))
        publish()

        val failed = pruner().prune()

        assertThat(failed.status).isEqualTo(AutomaticRulePruneStatus.COMPLETED)
        assertThat(failed.counts.offlineFileDeleteFailures).isEqualTo(1)
        assertThat(failed.counts.offlineRowsDeleted).isEqualTo(0)
        assertThat(offlineFileDao.selectById(seeded.row.id)).isNotNull()

        seeded.file.deleteRecursively()
        seeded.file.writeBytes(ByteArray(16))
        val retried = pruner().prune()

        assertThat(retried.counts.offlineRowsDeleted).isEqualTo(1)
        assertThat(offlineFileDao.selectById(seeded.row.id)).isNull()
        assertThat(seeded.file.exists()).isFalse()
    }

    @Test
    fun `an unavailable root keeps the row and the file untouched`() = runDbTest {
        val usable = prepareStorage()
        newProvider(1)
        val seeded = offline()
        val unavailable = OfflineStorage(
            roots = OfflineRootSource { emptyList() },
            settings = settings,
            usableSpace = { 0L },
            writableProbe = { true },
        )
        publish()

        val result = pruner(over = unavailable).prune()

        assertThat(result.counts.offlineFileDeleteFailures).isEqualTo(1)
        assertThat(offlineFileDao.selectById(seeded.row.id)).isNotNull()
        assertThat(seeded.file.isFile).isTrue()
        assertThat(usable.resolveStoredPath(seeded.row.offlinePath)).isNotNull()
    }

    @Test
    fun `a missing file removes its stale row`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val seeded = offline(withFile = false)
        publish()

        val result = pruner().prune()

        assertThat(result.counts.offlineRowsDeleted).isEqualTo(1)
        assertThat(offlineFileDao.selectById(seeded.row.id)).isNull()
    }

    @Test
    fun `an old automatic variant is retained until the wanted variant publishes`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val old = offline(externalId = "song-1", bitrateKbps = 128)
        val wantedKey = DownloadKeys.mediaKey(1, "song-1", 320, null)
        publish(wanted = setOf(wantedKey))

        val retained = pruner().prune()

        assertThat(retained.counts.replacementRowsRetained).isEqualTo(1)
        assertThat(retained.counts.offlineRowsDeleted).isEqualTo(0)
        assertThat(offlineFileDao.selectById(old.row.id)).isNotNull()
        assertThat(old.file.isFile).isTrue()

        val failedReplacement = queue(
            externalId = "song-1",
            bitrateKbps = 320,
            status = DownloadStatus.FAILED,
        )
        val failed = pruner().prune()

        assertThat(failed.counts.replacementRowsRetained).isEqualTo(1)
        assertThat(failed.counts.queueRowsCancelled).isEqualTo(0)
        assertThat(downloadDao.selectById(failedReplacement.id)).isNotNull()
        assertThat(offlineFileDao.selectById(old.row.id)).isNotNull()

        val replacement = offline(externalId = "song-1", bitrateKbps = 320)
        val completed = pruner().prune()

        assertThat(completed.counts.offlineRowsDeleted).isEqualTo(1)
        assertThat(offlineFileDao.selectById(old.row.id)).isNull()
        assertThat(old.file.exists()).isFalse()
        assertThat(offlineFileDao.selectById(replacement.row.id)).isNotNull()
        assertThat(replacement.file.isFile).isTrue()
    }

    @Test
    fun `an unparseable automatic identity is retained`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val seeded = offline(sourceKeyOverride = "not-a-media-key", externalId = "song-1")
        publish()

        val result = pruner().prune()

        assertThat(result.counts.unknownRowsRetained).isEqualTo(1)
        assertThat(offlineFileDao.selectById(seeded.row.id)).isNotNull()
        assertThat(seeded.file.isFile).isTrue()
    }

    @Test
    fun `a sync running at pass start blocks every delete group`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val queueRow = queue()
        val offlineRow = offline()
        publish()
        sync.tryBegin(1)

        val blocked = pruner().prune()

        assertThat(blocked.status).isEqualTo(AutomaticRulePruneStatus.SYNC_RUNNING)
        assertThat(blocked.involvedProviderIds).containsExactly(1L)
        assertThat(downloadDao.selectById(queueRow.id)).isNotNull()
        assertThat(offlineFileDao.selectById(offlineRow.row.id)).isNotNull()

        sync.finish(1)
        val completed = pruner().prune()

        assertThat(completed.counts.queueRowsCancelled).isEqualTo(1)
        assertThat(completed.counts.offlineRowsDeleted).isEqualTo(1)
    }

    @Test
    fun `a sync starting during a pass stops the next provider group`() = runDbTest {
        prepareStorage()
        newProvider(1)
        newProvider(2)
        val first = queue(providerId = 1, externalId = "first")
        val second = queue(providerId = 2, externalId = "second")
        publish()
        val cancellation = DownloadQueueCancellation { rowId ->
            val removed = downloadDao.deleteById(rowId) == 1
            if (removed) sync.tryBegin(2)
            removed
        }

        val result = pruner(cancellation = cancellation).prune()

        assertThat(result.status).isEqualTo(AutomaticRulePruneStatus.SYNC_RUNNING)
        assertThat(result.counts.queueRowsCancelled).isEqualTo(1)
        assertThat(downloadDao.selectById(first.id)).isNull()
        assertThat(downloadDao.selectById(second.id)).isNotNull()

        sync.finish(2)
        val completed = pruner().prune()
        assertThat(completed.status).isEqualTo(AutomaticRulePruneStatus.COMPLETED)
        assertThat(downloadDao.selectById(second.id)).isNull()
    }

    @Test
    fun `a queue row converted to export during the pass is not cancelled`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val first = queue(externalId = "first")
        val converted = queue(externalId = "converted")
        publish()
        val cancellation = DownloadQueueCancellation { rowId ->
            if (rowId == first.id) {
                val current = downloadDao.selectById(converted.id)!!
                downloadDao.upsert(current.copy(exportTarget = "MEDIASTORE_DOWNLOADS"))
            }
            downloadDao.deleteById(rowId) == 1
        }

        val result = pruner(cancellation = cancellation).prune()

        assertThat(result.counts.queueRowsCancelled).isEqualTo(1)
        assertThat(downloadDao.selectById(first.id)).isNull()
        assertThat(downloadDao.selectById(converted.id)?.exportTarget).isEqualTo("MEDIASTORE_DOWNLOADS")
    }

    @Test
    fun `an offline row pinned during the pass keeps its row and file`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val queueRow = queue()
        val pinned = offline()
        publish()
        val cancellation = DownloadQueueCancellation { rowId ->
            val removed = downloadDao.deleteById(rowId) == 1
            offlineFileDao.setManualOwnership(pinned.row.id, tempCache = false)
            removed
        }

        val result = pruner(cancellation = cancellation).prune()

        assertThat(result.counts.queueRowsCancelled).isEqualTo(1)
        assertThat(downloadDao.selectById(queueRow.id)).isNull()
        assertThat(offlineFileDao.selectById(pinned.row.id)?.tempCache).isFalse()
        assertThat(pinned.file.isFile).isTrue()
    }

    @Test
    fun `removing one overlapping rule prunes only its unshared rolling rows and files`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val favorites = addFavoritesRule()
        rules().add(1, AutomaticRuleKind.PLAYLIST, "pl-1")
        val playlistRule = rules().list(ProfileScope.Unrestricted)
            .single { it.kind == AutomaticRuleKind.PLAYLIST }
        val sharedQueue = queue(externalId = "song-1", autoRuleId = favorites.id)
        val unsharedQueue = queue(externalId = "song-2", autoRuleId = favorites.id)
        val shared = offline(externalId = "song-1", autoRule = favorites.id.toString())
        val unshared = offline(externalId = "song-2", autoRule = favorites.id.toString())

        rules().remove(1, AutomaticRuleKind.FAVORITES, "")
        publish(
            wanted = setOf(DownloadKeys.mediaKey(1, "song-1", null, null)),
            ruleSnapshot = listOf(playlistRule),
        )

        val result = pruner().prune()

        assertThat(result.status).isEqualTo(AutomaticRulePruneStatus.COMPLETED)
        assertThat(result.counts.queueRowsCancelled).isEqualTo(1)
        assertThat(downloadDao.selectById(sharedQueue.id)).isNotNull()
        assertThat(downloadDao.selectById(unsharedQueue.id)).isNull()
        assertThat(offlineFileDao.selectById(shared.row.id)).isNotNull()
        assertThat(shared.file.isFile).isTrue()
        assertThat(offlineFileDao.selectById(unshared.row.id)).isNull()
        assertThat(unshared.file.exists()).isFalse()
    }

    @Test
    fun `an offline identity belonging to another provider is retained`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val foreignKey = DownloadKeys.mediaKey(2, "song-1", null, null)
        val seeded = offline(providerId = 1, sourceKeyOverride = foreignKey)
        publish()

        val result = pruner().prune()

        assertThat(result.counts.unknownRowsRetained).isEqualTo(1)
        assertThat(result.counts.offlineRowsDeleted).isEqualTo(0)
        assertThat(offlineFileDao.selectById(seeded.row.id)).isNotNull()
        assertThat(seeded.file.isFile).isTrue()
    }

    @Test
    fun `provider deletion cascades before pruning leaves no orphan candidates`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val rule = addFavoritesRule()
        publish(ruleSnapshot = listOf(rule))
        queue()
        offline()

        providerDao.deleteById(1)
        val result = pruner().prune()

        assertThat(result.status).isEqualTo(AutomaticRulePruneStatus.RULES_CHANGED)
        assertThat(downloadDao.selectAll(ProfileScope.Unrestricted)).isEmpty()
        assertThat(offlineFileDao.selectAll()).isEmpty()
    }

    @Test
    fun `a rule change after publication blocks pruning`() = runDbTest {
        prepareStorage()
        newProvider(1)
        val rule = addFavoritesRule()
        publish(ruleSnapshot = listOf(rule))
        val queueRow = queue()
        rules().remove(1, AutomaticRuleKind.FAVORITES, "")

        val result = pruner().prune()

        assertThat(result.status).isEqualTo(AutomaticRulePruneStatus.RULES_CHANGED)
        assertThat(downloadDao.selectById(queueRow.id)).isNotNull()
    }

    @Test
    fun `cancellation is propagated without deleting the offline group`() = runDbTest {
        prepareStorage()
        newProvider(1)
        queue()
        val offlineRow = offline()
        publish()
        var thrown = false

        try {
            pruner(
                cancellation = DownloadQueueCancellation {
                    throw CancellationException("stop")
                },
            ).prune()
        } catch (_: CancellationException) {
            thrown = true
        }

        assertThat(thrown).isTrue()
        assertThat(offlineFileDao.selectById(offlineRow.row.id)).isNotNull()
        assertThat(offlineRow.file.isFile).isTrue()
    }
}
