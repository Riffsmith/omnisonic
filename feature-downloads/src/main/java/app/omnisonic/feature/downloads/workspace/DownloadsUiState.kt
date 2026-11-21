package app.omnisonic.feature.downloads.workspace

import app.omnisonic.core.data.db.entity.DownloadStatus
import app.omnisonic.core.data.downloads.AutomaticRuleKind
import app.omnisonic.core.data.downloads.DownloadDestination
import app.omnisonic.core.data.downloads.DownloadLifecycleRules

/** The three surfaces inside the one `downloads` destination. */
enum class DownloadsTab { QUEUE, OFFLINE, RULES }

/** One-shot command feedback; the screen owns the localized copy. */
enum class DownloadsNotice {
    OFFLINE_DELETED,
    OFFLINE_DELETE_FAILED,
    OWNERSHIP_FAILED,
    RULE_ADDED,
    RULE_REMOVED,
    RULE_FAILED,
}

data class DownloadsRuleCandidatesState(
    val loading: Boolean = false,
    val failed: Boolean = false,
    val candidates: DownloadRuleCandidatesUi? = null,
)

/** Provider-scoped playlist/album targets offered by the rules add surface. */
data class DownloadRuleCandidateUi(
    val providerId: Long,
    val externalId: String,
    val title: String,
)

data class DownloadRuleCandidatesUi(
    val playlists: List<DownloadRuleCandidateUi> = emptyList(),
    val albums: List<DownloadRuleCandidateUi> = emptyList(),
)

/**
 * Which row commands are legal for one persisted status. Unknown raw statuses
 * fail closed: nothing but a read is offered, because every conditional DAO
 * command would silently no-op. `FAILED` is the only retryable state; `PAUSED`
 * still offers item-pause so a globally paused row can be made sticky, and
 * resume only releases a `USER_PAUSED` row.
 */
data class DownloadQueueRowActions(
    val canPause: Boolean,
    val canResume: Boolean,
    val canRetry: Boolean,
    val canCancel: Boolean,
)

object DownloadQueueRowRules {

    fun actionsFor(rawStatus: String): DownloadQueueRowActions {
        val status = DownloadStatus.entries.firstOrNull { it.name == rawStatus }
            ?: return DownloadQueueRowActions(
                canPause = false,
                canResume = false,
                canRetry = false,
                canCancel = false,
            )
        return DownloadQueueRowActions(
            canPause = status == DownloadStatus.PENDING ||
                status == DownloadStatus.PAUSED ||
                status == DownloadStatus.ACTIVE,
            canResume = status == DownloadStatus.USER_PAUSED,
            canRetry = status == DownloadStatus.FAILED,
            canCancel = true,
        )
    }

    /** The sixth counted failure parks a row; an uncounted park keeps a lower count. */
    fun isParked(rawStatus: String, errorCount: Int): Boolean =
        rawStatus == DownloadStatus.FAILED.name &&
            errorCount >= DownloadLifecycleRules.MAX_COUNTED_FAILURES

    fun isKnown(rawStatus: String): Boolean =
        DownloadStatus.entries.any { it.name == rawStatus }
}

data class DownloadQueueRowUi(
    val id: Long,
    val title: String?,
    val providerName: String?,
    val rawStatus: String,
    val status: DownloadStatus?,
    val progressPercent: Int,
    val errorCount: Int,
    val lastError: String?,
    val actions: DownloadQueueRowActions,
) {
    val indeterminate: Boolean get() = progressPercent < 0
    val parked: Boolean get() = DownloadQueueRowRules.isParked(rawStatus, errorCount)
}

data class DownloadOfflineRowUi(
    val id: Long,
    val title: String?,
    val providerName: String?,
    val permanent: Boolean,
    val automatic: Boolean,
    val sizeBytes: Long?,
    val downloadedAtMs: Long?,
    val bitrateKbps: Int?,
)

data class DownloadRuleRowUi(
    val id: Long,
    val providerId: Long,
    val providerName: String?,
    val kind: AutomaticRuleKind,
    val targetExternalId: String,
    val targetLabel: String?,
)

/**
 * Workspace state. Queue and offline lists use the persisted row id as their
 * stable lazy key. [globallyPaused] is the persisted gate, never derived from
 * row statuses, so resume-all cannot visually release a sticky item pause.
 */
data class DownloadsUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val tab: DownloadsTab = DownloadsTab.QUEUE,
    val queue: List<DownloadQueueRowUi> = emptyList(),
    val offline: List<DownloadOfflineRowUi> = emptyList(),
    val rules: List<DownloadRuleRowUi> = emptyList(),
    val providerNames: Map<Long, String> = emptyMap(),
    val globallyPaused: Boolean = false,
    val preferredDestination: DownloadDestination = DownloadDestination.OFFLINE,
    val busyRowIds: Set<Long> = emptySet(),
    val busyGlobal: Boolean = false,
    val notice: DownloadsNotice? = null,
    val candidates: DownloadsRuleCandidatesState = DownloadsRuleCandidatesState(),
) {
    val failedCount: Int get() = queue.count { it.status == DownloadStatus.FAILED }

    val canRetryAll: Boolean get() = failedCount > 0 && !busyGlobal
}
