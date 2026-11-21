package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.DownloadKeys
import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadItemEntity
import app.omnisonic.core.data.db.entity.OfflineFileEntity
import app.omnisonic.core.data.sync.LibrarySyncStatusRepository
import app.omnisonic.core.model.log.OmnisonicLogger
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

enum class AutomaticRulePruneStatus {
    /** Every candidate group was processed; individual failures stay retryable. */
    COMPLETED,

    /** No current verified generation; nothing may be deleted. */
    NOT_VERIFIED,

    /** A provider sync was running at a guard; no further group was started. */
    SYNC_RUNNING,

    /** A trigger replaced or invalidated the generation at a guard. */
    GENERATION_CHANGED,

    /** The persisted rule snapshot changed after publication. */
    RULES_CHANGED,

    /** The rule snapshot could not be read, so nothing may be deleted. */
    RULE_READ_FAILED,
}

/** Applied destructive work plus retryable failures from one pruning pass. */
data class AutomaticRulePruneCounts(
    val queueRowsCancelled: Int = 0,
    val queueRowsAlreadyGone: Int = 0,
    val queueCancelFailures: Int = 0,
    val offlineRowsDeleted: Int = 0,
    val offlineRowsAlreadyGone: Int = 0,
    val offlineFileDeleteFailures: Int = 0,
    val offlineRowDeleteFailures: Int = 0,
    val replacementRowsRetained: Int = 0,
    val unknownRowsRetained: Int = 0,
)

data class AutomaticRulePruneResult(
    val status: AutomaticRulePruneStatus,
    val counts: AutomaticRulePruneCounts = AutomaticRulePruneCounts(),
    /** Providers whose running sync blocked a group; empty for non-sync stops. */
    val involvedProviderIds: Set<Long> = emptySet(),
)

/**
 * Destructive half of task 8 (task 8b). It acts only on a
 * [AutomaticRuleGenerationState.verified] generation, cancels unwanted
 * automatic rolling queue rows through the process-wide coordinator, and
 * removes an unwanted automatic rolling offline row only after its stored file
 * was deleted (or was already absent). A failed file deletion leaves the row
 * intact for the next pass.
 *
 * The candidate list is a snapshot, so ownership is revalidated against the
 * current persisted row immediately before each destructive action: a row
 * pinned or manualized while the pass runs is no longer automatic rolling, and
 * a queue row converted to an export destination is protected. Offline pinning
 * and this deletion share [ownership] so the conversion cannot interleave with
 * the file-first delete; queue cancellation is conditional under the
 * coordinator's roster lock for the same reason.
 *
 * Ownership is never inferred from `autoRuleId`/`autoRule` alone: a row is an
 * automatic candidate only when it also carries rolling intent, so a manual
 * rolling row (`autoRule = null`) and every permanent row are untouchable.
 * Other providers and unparseable or identity-mismatched rows are likewise left
 * alone. An older automatic variant is retained while a wanted variant of the
 * same song has no offline row yet, checked against fresh offline state so a
 * replacement disappearing mid-pass cannot cost playback its last file.
 *
 * Every destructive group is preceded by the same generation, rule-snapshot
 * and running-sync guards. A sync that starts mid-pass aborts before the next
 * group; neither the pass nor a group ever runs from a stale generation.
 */
class AutomaticRulePruner(
    private val db: OmnisonicDatabase,
    private val storage: OfflineStorage,
    private val rules: AutomaticRuleRepository,
    private val syncStatus: LibrarySyncStatusRepository,
    private val generations: AutomaticRuleGenerationStore,
    private val cancellation: DownloadQueueCancellation,
    private val logger: OmnisonicLogger,
    private val ownership: OfflineOwnershipGuard = OfflineOwnershipGuard(),
) {

    private data class SongIdentity(val providerId: Long, val externalId: String)

    private data class OfflineCandidate(
        val row: OfflineFileEntity,
        val identity: SongIdentity?,
    )

    suspend fun prune(): AutomaticRulePruneResult {
        val state = generations.state.value
        val generation = state.generation
        if (!state.verified || generation == null) {
            return AutomaticRulePruneResult(AutomaticRulePruneStatus.NOT_VERIFIED)
        }

        val queueRows = db.downloadDao().selectAllRaw("")
        val offlineRows = db.offlineFileDao().selectAll()
        val queueCandidates = queueRows.filter {
            isAutomaticRollingQueueRow(it) && it.mediaKey !in generation.wantedMediaKeys
        }
        val offlineCandidates = offlineRows
            .filter { isAutomaticRollingOfflineRow(it) && it.sourceKey !in generation.wantedMediaKeys }
            .map { OfflineCandidate(it, identityOf(it)) }

        val involvedProviders = buildSet {
            addAll(generation.providerIds)
            queueCandidates.mapTo(this) { it.providerId }
            offlineCandidates.mapTo(this) { it.row.providerId }
        }
        if (syncRunning(involvedProviders)) {
            return aborted(AutomaticRulePruneStatus.SYNC_RUNNING, involvedProviders)
        }

        when (rulesSnapshot(generation)) {
            RuleSnapshot.MATCHED -> Unit
            RuleSnapshot.CHANGED -> return aborted(AutomaticRulePruneStatus.RULES_CHANGED)
            RuleSnapshot.READ_FAILED -> return aborted(AutomaticRulePruneStatus.RULE_READ_FAILED)
        }

        val wantedBySong = generation.wantedMediaKeys
            .mapNotNull { key ->
                DownloadKeys.parseMediaKey(key)?.let { SongIdentity(it.providerId, it.externalId) to key }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, keys) -> keys.toSet() }

        var counts = AutomaticRulePruneCounts()

        for ((providerId, group) in queueCandidates.groupBy { it.providerId }.toSortedMap()) {
            guard(generation, involvedProviders)?.let { return it.with(counts) }
            for (row in group) {
                counts = cancelQueueRow(row, generation, counts)
            }
            logger.debug(TAG, "pruned queue provider=$providerId rows=${group.size}")
        }

        for ((providerId, group) in offlineCandidates.groupBy { it.row.providerId }.toSortedMap()) {
            guard(generation, involvedProviders)?.let { return it.with(counts) }
            for (candidate in group) {
                counts = pruneOfflineRow(candidate, generation, wantedBySong, counts)
            }
            logger.debug(TAG, "pruned offline provider=$providerId rows=${group.size}")
        }

        if (counts != AutomaticRulePruneCounts()) {
            logger.info(
                TAG,
                "rule prune cancelled=${counts.queueRowsCancelled} gone=${counts.queueRowsAlreadyGone} " +
                    "queueFailed=${counts.queueCancelFailures} files=${counts.offlineRowsDeleted} " +
                    "retained=${counts.replacementRowsRetained} fileFailed=${counts.offlineFileDeleteFailures}",
            )
        }
        return AutomaticRulePruneResult(AutomaticRulePruneStatus.COMPLETED, counts)
    }

    private suspend fun cancelQueueRow(
        row: DownloadItemEntity,
        generation: AutomaticRuleGeneration,
        counts: AutomaticRulePruneCounts,
    ): AutomaticRulePruneCounts {
        val current = db.downloadDao().selectById(row.id)
            ?: return counts.copy(queueRowsAlreadyGone = counts.queueRowsAlreadyGone + 1)
        if (!isCandidateQueueRow(current, generation)) return counts

        val cancelled = try {
            cancellation.cancelIfAutomaticRolling(current.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(TAG, "queue cancellation failed id=${current.id}", failure)
            false
        }
        if (cancelled) return counts.copy(queueRowsCancelled = counts.queueRowsCancelled + 1)

        val after = db.downloadDao().selectById(current.id)
        return when {
            after == null -> counts.copy(queueRowsAlreadyGone = counts.queueRowsAlreadyGone + 1)
            // Converted to an export destination or manualized between the
            // candidate snapshot and the cancellation: protected, not a failure.
            !isCandidateQueueRow(after, generation) -> counts
            else -> counts.copy(queueCancelFailures = counts.queueCancelFailures + 1)
        }
    }

    private suspend fun pruneOfflineRow(
        candidate: OfflineCandidate,
        generation: AutomaticRuleGeneration,
        wantedBySong: Map<SongIdentity, Set<String>>,
        counts: AutomaticRulePruneCounts,
    ): AutomaticRulePruneCounts = ownership.withOwnershipLock {
        val current = db.offlineFileDao().selectById(candidate.row.id)
            ?: return@withOwnershipLock counts.copy(
                offlineRowsAlreadyGone = counts.offlineRowsAlreadyGone + 1,
            )
        if (!isAutomaticRollingOfflineRow(current) || current.sourceKey in generation.wantedMediaKeys) {
            return@withOwnershipLock counts
        }
        val identity = identityOf(current)
        if (identity == null) {
            return@withOwnershipLock counts.copy(unknownRowsRetained = counts.unknownRowsRetained + 1)
        }
        val wanted = wantedBySong[identity].orEmpty()
        if (wanted.isNotEmpty() && wanted.none { db.offlineFileDao().selectBySourceKey(it) != null }) {
            return@withOwnershipLock counts.copy(
                replacementRowsRetained = counts.replacementRowsRetained + 1,
            )
        }
        deleteOfflineRow(current, counts)
    }

    private suspend fun deleteOfflineRow(
        row: OfflineFileEntity,
        counts: AutomaticRulePruneCounts,
    ): AutomaticRulePruneCounts {
        val file = try {
            storage.resolveStoredPath(row.offlinePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (file == null || (file.exists() && (!file.isFile || !deleteFile(file)))) {
            return counts.copy(offlineFileDeleteFailures = counts.offlineFileDeleteFailures + 1)
        }
        val removed = try {
            db.offlineFileDao().deleteById(row.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(TAG, "offline row delete failed id=${row.id}", failure)
            -1
        }
        return when (removed) {
            1 -> counts.copy(offlineRowsDeleted = counts.offlineRowsDeleted + 1)
            0 -> counts.copy(offlineRowsAlreadyGone = counts.offlineRowsAlreadyGone + 1)
            else -> counts.copy(offlineRowDeleteFailures = counts.offlineRowDeleteFailures + 1)
        }
    }

    private fun deleteFile(file: File): Boolean = try {
        file.delete()
    } catch (_: Exception) {
        false
    }

    /**
     * Rechecks the published generation, the persisted rule snapshot and every
     * involved sync before a destructive group. A queue/offline observer change
     * keeps the generation usable; a rule or sync change does not.
     */
    private suspend fun guard(
        generation: AutomaticRuleGeneration,
        providers: Set<Long>,
    ): AutomaticRulePruneResult? {
        val state = generations.state.value
        if (!state.verified || state.generation?.sequence != generation.sequence) {
            return aborted(AutomaticRulePruneStatus.GENERATION_CHANGED)
        }
        if (syncRunning(providers)) return aborted(AutomaticRulePruneStatus.SYNC_RUNNING, providers)
        return when (rulesSnapshot(generation)) {
            RuleSnapshot.MATCHED -> null
            RuleSnapshot.CHANGED -> aborted(AutomaticRulePruneStatus.RULES_CHANGED)
            RuleSnapshot.READ_FAILED -> aborted(AutomaticRulePruneStatus.RULE_READ_FAILED)
        }
    }

    private enum class RuleSnapshot { MATCHED, CHANGED, READ_FAILED }

    private suspend fun rulesSnapshot(generation: AutomaticRuleGeneration): RuleSnapshot {
        val current = try {
            rules.list(ProfileScope.Unrestricted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RuleSnapshot.READ_FAILED
        }
        return if (current == generation.expansions.map { it.rule }) {
            RuleSnapshot.MATCHED
        } else {
            RuleSnapshot.CHANGED
        }
    }

    private fun syncRunning(providers: Set<Long>): Boolean =
        providers.any(syncStatus::isRunning)

    private fun isCandidateQueueRow(
        row: DownloadItemEntity,
        generation: AutomaticRuleGeneration,
    ): Boolean = isAutomaticRollingQueueRow(row) && row.mediaKey !in generation.wantedMediaKeys

    private fun isAutomaticRollingQueueRow(row: DownloadItemEntity): Boolean =
        row.autoRuleId != null && row.tempCache && row.exportTarget == null

    private fun isAutomaticRollingOfflineRow(row: OfflineFileEntity): Boolean =
        row.autoRule != null && row.tempCache

    /**
     * A parsed key must agree with the row's own provider (and stated external
     * id); a mismatch fails closed as unknown rather than borrowing another
     * provider's identity and becoming prunable.
     */
    private fun identityOf(row: OfflineFileEntity): SongIdentity? {
        val parsed = DownloadKeys.parseMediaKey(row.sourceKey)
        if (parsed != null) {
            if (parsed.providerId != row.providerId) return null
            val stated = row.externalId?.takeIf { it.isNotBlank() }
            if (stated != null && stated != parsed.externalId) return null
            return SongIdentity(parsed.providerId, parsed.externalId)
        }
        val externalId = row.externalId?.takeIf { it.isNotBlank() } ?: return null
        return SongIdentity(row.providerId, externalId)
    }

    private fun aborted(
        status: AutomaticRulePruneStatus,
        providers: Set<Long> = emptySet(),
    ): AutomaticRulePruneResult = AutomaticRulePruneResult(status, involvedProviderIds = providers)

    private fun AutomaticRulePruneResult.with(counts: AutomaticRulePruneCounts): AutomaticRulePruneResult =
        copy(counts = counts)

    private companion object {
        const val TAG = "RulePrune"
    }
}
