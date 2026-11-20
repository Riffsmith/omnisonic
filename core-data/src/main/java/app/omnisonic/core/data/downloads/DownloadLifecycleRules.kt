package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.entity.DownloadStatus

/** Every queue command whose legality depends on the row's current state. */
enum class DownloadQueueCommand {
    CLAIM,
    PAUSE_ALL,
    RESUME_ALL,
    PAUSE_ONE,
    RESUME_ONE,
    CANCEL,
    RETRY,
    COMPLETE,
}

/**
 * Pure rules for the persistent download queue
 * (`.agents/plans/phase-7-tasks.md` §Task 1 lifecycle, §retry and enqueue,
 * §no task 1 schema migration). No I/O, no clock, no mutable state: callers
 * pass observed values in, so every rule is directly testable.
 */
object DownloadLifecycleRules {

    const val MAX_COUNTED_FAILURES = 6
    const val BACKOFF_STEP_MS = 10_000L
    const val MAX_ERROR_MESSAGE_LENGTH = 300

    /** Persisted progress marker while the response length is unknown. */
    const val PROGRESS_UNKNOWN = -1

    private val controlAndWhitespace = Regex("[\\p{Cntrl}\\s]+")

    /** Unknown or invalid persisted values parse to null and fail closed. */
    fun parse(rawStatus: String?): DownloadStatus? =
        DownloadStatus.entries.firstOrNull { it.name == rawStatus }

    fun isKnown(rawStatus: String?): Boolean = parse(rawStatus) != null

    fun canApply(command: DownloadQueueCommand, status: DownloadStatus): Boolean = when (command) {
        DownloadQueueCommand.CLAIM -> status == DownloadStatus.PENDING
        DownloadQueueCommand.PAUSE_ALL -> status == DownloadStatus.PENDING || status == DownloadStatus.ACTIVE
        DownloadQueueCommand.RESUME_ALL -> status == DownloadStatus.PAUSED
        DownloadQueueCommand.PAUSE_ONE ->
            status == DownloadStatus.PENDING ||
                status == DownloadStatus.PAUSED ||
                status == DownloadStatus.ACTIVE
        DownloadQueueCommand.RESUME_ONE -> status == DownloadStatus.USER_PAUSED
        DownloadQueueCommand.CANCEL -> true
        DownloadQueueCommand.RETRY -> status == DownloadStatus.FAILED
        DownloadQueueCommand.COMPLETE -> status == DownloadStatus.ACTIVE
    }

    fun canApply(command: DownloadQueueCommand, rawStatus: String?): Boolean =
        parse(rawStatus)?.let { canApply(command, it) } ?: false

    /** New unique work starts paused while the global gate is set. */
    fun enqueueStatus(globallyPaused: Boolean): DownloadStatus =
        if (globallyPaused) DownloadStatus.PAUSED else DownloadStatus.PENDING

    /** Resume enters the current global gate instead of bypassing it. */
    fun resumeTarget(globallyPaused: Boolean): DownloadStatus = enqueueStatus(globallyPaused)

    /** The sixth counted failure parks the row; earlier ones return it to the queue. */
    fun failureTarget(nextErrorCount: Int): DownloadStatus =
        if (nextErrorCount >= MAX_COUNTED_FAILURES) DownloadStatus.FAILED else DownloadStatus.PENDING

    /**
     * Effective retry position. Count zero is due immediately (wall clock may
     * move backwards); counts 1..5 wait `errorCount × 10 s`; anything else is
     * corrupt and sorts last. Saturating arithmetic never yields an early due
     * time from overflow.
     */
    fun effectiveDueAtMs(updatedAt: Long, errorCount: Int): Long = when {
        errorCount == 0 -> updatedAt
        errorCount in 1..(MAX_COUNTED_FAILURES - 1) ->
            saturatingAdd(updatedAt, errorCount.toLong() * BACKOFF_STEP_MS)
        else -> Long.MAX_VALUE
    }

    fun isDue(updatedAt: Long, errorCount: Int, nowMs: Long): Boolean = when {
        errorCount == 0 -> true
        errorCount in 1..(MAX_COUNTED_FAILURES - 1) -> effectiveDueAtMs(updatedAt, errorCount) <= nowMs
        else -> false
    }

    fun sanitizeFailureMessage(raw: String?): String? {
        val collapsed = raw?.replace(controlAndWhitespace, " ")?.trim().orEmpty()
        return collapsed.takeIf { it.isNotEmpty() }?.take(MAX_ERROR_MESSAGE_LENGTH)
    }

    fun sanitizeWorkerCount(requested: Int): Int = requested.coerceAtLeast(1)

    private fun saturatingAdd(base: Long, delta: Long): Long =
        if (base > Long.MAX_VALUE - delta) Long.MAX_VALUE else base + delta
}
