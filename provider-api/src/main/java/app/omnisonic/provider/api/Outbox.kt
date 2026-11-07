package app.omnisonic.provider.api

import app.omnisonic.core.model.log.ConsoleLogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.core.model.runCatchingCancellable
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

data class OutboxTask(
    val id: Long,
    val providerId: Long,
    val mediaType: String,
    val externalId: String,
    val action: String,
    val attempts: Int,
    val payloadJson: String?,
)

/**
 * Wire shape of outbox payload JSON; mirrors the enqueue-merge payload model in
 * core-data so rows decode without a module dependency cycle.
 */
@JsonClass(generateAdapter = true)
data class OutboxActionPayload(
    val playCountDelta: Int? = null,
    val isFavorite: Boolean? = null,
    val userRating: Int? = null,
    val resumeMs: Long? = null,
    val lastPlayedAt: Long? = null,
    val playTimestamps: List<Long>? = null,
) {

    /**
     * The timestamps this payload submits: explicit timestamps when present,
     * otherwise [playCountDelta] one-millisecond steps from [lastPlayedAt]
     * (legacy rows written before the timestamp list existed).
     */
    fun playTimestampsForSubmission(): List<Long> {
        playTimestamps?.takeIf { it.isNotEmpty() }?.let { return it }
        val delta = playCountDelta ?: return emptyList()
        if (delta <= 0) return emptyList()
        return lastPlayedAt?.let { start -> List(delta) { offset -> start + offset } }.orEmpty()
    }

    /**
     * Removes at most as many occurrences of each timestamp in [submitted] as
     * the drain actually reported, then recomputes the play fields from what
     * remains. Timestamps enqueued while the submission was in flight are
     * therefore kept, and the row is empty only when nothing is left.
     */
    fun afterSubmitting(submitted: List<Long>): OutboxActionPayload {
        val remaining = playTimestampsForSubmission().withoutConsumed(submitted)
        return copy(
            playCountDelta = remaining.size.takeIf { it > 0 },
            lastPlayedAt = remaining.firstOrNull(),
            playTimestamps = remaining.takeIf { it.isNotEmpty() },
        )
    }

    private fun List<Long>.withoutConsumed(consumed: List<Long>): List<Long> {
        if (consumed.isEmpty()) return this
        val budget = consumed.groupingBy { it }.eachCount().toMutableMap()
        return filter { timestamp ->
            val left = budget[timestamp] ?: 0
            if (left == 0) {
                true
            } else {
                budget[timestamp] = left - 1
                false
            }
        }
    }
}

/**
 * Action vocabulary shared by the enqueue side and the drainer. The Room
 * outbox column stores these constants verbatim.
 */
object OutboxActions {
    const val PLAY_COUNT = "play_count"
    const val FAVORITE = "favorite"
    const val USER_RATING = "user_rating"
    const val RESUME_POINT = "resume_point"

    /** Execution order per item; a resume-point clear never overtakes a play count. */
    val ITEM_ORDER = listOf(PLAY_COUNT, FAVORITE, USER_RATING, RESUME_POINT)

    fun orderOf(action: String): Int = ITEM_ORDER.indexOf(action).let { if (it < 0) ITEM_ORDER.size else it }
}

/** Result of a completion that is conditional on the payload actually submitted. */
sealed interface OutboxCompletion {
    /** The stored payload was still the submitted one; the row is deleted. */
    data object Deleted : OutboxCompletion

    /** A concurrent enqueue replaced the payload; the newer row is untouched. */
    data object Superseded : OutboxCompletion

    /** The row is already gone, e.g. another drain completed it. */
    data object Missing : OutboxCompletion
}

/** Result of removing the play timestamps a drain submitted from the stored row. */
sealed interface OutboxProgress {
    /** Every submitted timestamp was consumed; the row is deleted. */
    data object Cleared : OutboxProgress

    /** The row still carries [remainingTimestamps] play timestamps. */
    data class Retained(val remainingTimestamps: Int) : OutboxProgress

    /** The stored payload cannot be decoded; the row is left untouched. */
    data object Unreadable : OutboxProgress
}

interface OutboxStore {
    suspend fun dueTasks(now: Long, providerId: Long?): List<OutboxTask>

    /**
     * Deletes [task] only while the stored row still carries the payload this
     * drain read and submitted. An enqueue that landed during the submission
     * makes the delete affect zero rows; the newer payload then survives.
     */
    suspend fun complete(task: OutboxTask): OutboxCompletion

    suspend fun fail(id: Long, attempts: Int, nextAttemptAt: Long, lastError: String)

    /**
     * Transactionally removes the play timestamps this drain submitted from
     * the row's current payload. Timestamps enqueued during the submission are
     * preserved and the row is deleted only when no timestamp remains.
     */
    suspend fun progress(
        task: OutboxTask,
        submittedTimestamps: List<Long>,
        nextAttemptAt: Long,
    ): OutboxProgress
}

class OutboxDrainer(
    private val store: OutboxStore,
    private val providerLocator: (Long) -> MediaProvider?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val runGate: ProviderRunGate = AllowAllRunGate,
    private val logger: OmnisonicLogger = OmnisonicLogger(ConsoleLogSink()),
    /** Reports a typed server authentication rejection to the status tracker. */
    private val onAuthFailed: suspend (Long) -> Unit = {},
    /** Reports a successful provider call so a stale auth failure can clear. */
    private val onAuthSucceeded: suspend (Long) -> Unit = {},
) {
    data class DrainReport(
        val completed: Int,
        val progressed: Int,
        val deferred: Int,
        /** Non-null when the run gate blocked the whole drain (status != OK). */
        val blockedBy: ProviderStatus? = null,
    )

    private val probedProviders = mutableSetOf<Long>()

    suspend fun drain(providerId: Long? = null): DrainReport {
        if (providerId != null) {
            val status = runGate.check(providerId)
            if (status != ProviderStatus.OK) {
                logger.debug(TAG, "drain for provider $providerId blocked: $status")
                return DrainReport(completed = 0, progressed = 0, deferred = 0, blockedBy = status)
            }
        }
        var completed = 0
        var progressed = 0
        var deferred = 0
        val authFailedProviders = mutableSetOf<Long>()
        val due = store.dueTasks(clock(), providerId)
        // Per-item action order: for the same (provider, type, id), play_count
        // runs before favorite/user_rating before resume_point. Item groups
        // keep their relative due order.
        val ordered = due
            .groupBy { Triple(it.providerId, it.mediaType, it.externalId) }
            .flatMap { (_, tasks) -> tasks.sortedBy { OutboxActions.orderOf(it.action) } }
        for (task in ordered) {
            if (task.providerId in authFailedProviders) {
                logger.debug(TAG, "outbox task ${task.id} stays due; provider ${task.providerId} rejected authentication")
                continue
            }
            if (providerId == null && runGate.check(task.providerId) != ProviderStatus.OK) {
                logger.debug(TAG, "outbox task ${task.id} stays due; provider ${task.providerId} is blocked by the run gate")
                continue
            }
            val provider = providerLocator(task.providerId)
            if (provider == null) {
                logger.warn(TAG, "outbox task ${task.id} has no registered provider for id ${task.providerId}")
                deferred++
                continue
            }
            val outcome = runCatchingCancellable {
                if (task.action == OutboxActions.PLAY_COUNT) probeOnce(task.providerId, provider)
                execute(task, provider)
            }.getOrElse { error ->
                if (error is ProviderAuthException) {
                    authFailedProviders += task.providerId
                    logger.error(TAG, "outbox task ${task.id} failed authentication", error)
                    runCatchingCancellable { onAuthFailed(task.providerId) }
                } else {
                    logger.error(TAG, "outbox task ${task.id} failed", error)
                    store.fail(task.id, task.attempts + 1, clock() + backoffMs(task.attempts + 1), error.message ?: error.javaClass.simpleName)
                }
                null
            }
            if (outcome != null) runCatchingCancellable { onAuthSucceeded(task.providerId) }
            when (outcome) {
                is TaskOutcome.Submitted -> when (store.complete(task)) {
                    OutboxCompletion.Deleted, OutboxCompletion.Missing -> completed++
                    OutboxCompletion.Superseded -> {
                        if (settleSuperseded(task, outcome.submittedTimestamps)) progressed++ else completed++
                    }
                }
                TaskOutcome.Finished -> completed++
                TaskOutcome.Progressed -> progressed++
                null, TaskOutcome.Deferred -> deferred++
            }
        }
        return DrainReport(completed, progressed, deferred)
    }

    /**
     * A concurrent enqueue replaced the payload after [task] was read. A play
     * count drops only the timestamps this drain submitted, keeping timestamps
     * that landed meanwhile. Favorite, rating and resume rows already carry
     * the newer value, so they are left for the next drain. Returns true when
     * the row still has work (reported as progressed), false when it is gone.
     */
    private suspend fun settleSuperseded(task: OutboxTask, submittedTimestamps: List<Long>?): Boolean {
        if (submittedTimestamps == null) {
            logger.debug(TAG, "outbox task ${task.id} was re-enqueued while submitting; keeping the newer payload")
            return true
        }
        return when (store.progress(task, submittedTimestamps, clock())) {
            OutboxProgress.Cleared -> false
            is OutboxProgress.Retained, OutboxProgress.Unreadable -> true
        }
    }

    /**
     * Probes a provider before its first play-count task. Only a successful
     * probe marks the provider done, so a failed probe is retried on the next
     * drain instead of disabling capability discovery for the drainer lifetime.
     */
    private suspend fun probeOnce(providerId: Long, provider: MediaProvider) {
        if (providerId in probedProviders) return
        runCatchingCancellable { provider.probe() }
            .onSuccess { probedProviders += providerId }
            .onFailure { failure ->
                if (failure is ProviderAuthException) throw failure
                logger.warn(TAG, "capability probe failed for provider $providerId: ${failure.message}")
            }
    }

    private suspend fun execute(task: OutboxTask, provider: MediaProvider): TaskOutcome {
        val mediaType = mediaTypeOf(task)
        val payload = decodePayload(task.payloadJson)
        return when (task.action) {
            OutboxActions.PLAY_COUNT -> handlePlayCount(task, payload, provider)
            OutboxActions.FAVORITE -> handleSimple(task) {
                payload.isFavorite?.let { provider.setFavorite(mediaType, task.externalId, it) }
            }

            OutboxActions.USER_RATING -> handleSimple(task) {
                payload.userRating?.let { provider.setUserRating(mediaType, task.externalId, it) }
            }

            OutboxActions.RESUME_POINT -> handleSimple(task) {
                payload.resumeMs?.let { position ->
                    if (position > 0) provider.saveBookmark(task.externalId, position) else provider.deleteBookmark(task.externalId)
                }
            }

            else -> {
                logger.warn(TAG, "outbox task ${task.id} has unknown action ${task.action}; dropping")
                TaskOutcome.Submitted()
            }
        }
    }

    private suspend fun handlePlayCount(task: OutboxTask, payload: OutboxActionPayload, provider: MediaProvider): TaskOutcome {
        val delta = payload.playCountDelta
        if (delta == null || delta <= 0) {
            return parkEmptyPayload(task)
        }
        val timestamps = payload.playTimestampsForSubmission()
        if (timestamps.isEmpty()) {
            return parkEmptyPayload(task)
        }
        var acceptedEarlier = false
        for (chunk in timestamps.chunked(PLAY_SUBMISSION_CHUNK_SIZE)) {
            val accepted = provider.submitPlays(task.externalId, chunk)
            if (accepted < chunk.size) {
                val acceptedTimestamps = chunk.take(accepted)
                if (acceptedTimestamps.isEmpty()) {
                    store.fail(task.id, task.attempts + 1, clock() + backoffMs(task.attempts + 1), "server accepted no submissions")
                    return if (acceptedEarlier) TaskOutcome.Progressed else TaskOutcome.Deferred
                }
                return recordPlayProgress(task, acceptedTimestamps)
            }
            when (store.progress(task, chunk, clock())) {
                OutboxProgress.Cleared -> return TaskOutcome.Finished
                is OutboxProgress.Retained -> acceptedEarlier = true
                OutboxProgress.Unreadable -> return TaskOutcome.Progressed
            }
        }
        return TaskOutcome.Progressed
    }

    private suspend fun recordPlayProgress(task: OutboxTask, submitted: List<Long>): TaskOutcome =
        when (store.progress(task, submitted, clock())) {
            OutboxProgress.Cleared -> TaskOutcome.Finished
            is OutboxProgress.Retained, OutboxProgress.Unreadable -> TaskOutcome.Progressed
        }

    private suspend fun handleSimple(task: OutboxTask, action: suspend () -> Boolean?): TaskOutcome =
        when (val applied = action.invoke()) {
            null -> parkEmptyPayload(task)

            true -> TaskOutcome.Submitted()

            false -> {
                store.fail(task.id, task.attempts + 1, clock() + backoffMs(task.attempts + 1), "provider rejected the change")
                TaskOutcome.Deferred
            }
        }

    /**
     * A known action whose payload carries no usable value is structural
     * corruption, not success: the row stays parked with a visible error
     * instead of being dropped as COMPLETED (which would silently lose the
     * user's favorite/rating/resume change).
     */
    private suspend fun parkEmptyPayload(task: OutboxTask): TaskOutcome {
        logger.warn(TAG, "outbox task ${task.id} carries an empty payload; parking as failed")
        store.fail(task.id, task.attempts + 1, clock() + backoffMs(task.attempts + 1), EMPTY_PAYLOAD_ERROR)
        return TaskOutcome.Deferred
    }

    /** Unknown media-type values are parked; they are never guessed as songs. */
    private fun mediaTypeOf(task: OutboxTask): ProviderMediaType {
        val parsed = ProviderMediaType.entries.firstOrNull { it.name == task.mediaType }
        if (parsed == null) {
            logger.warn(TAG, "outbox task ${task.id} has unknown mediaType ${task.mediaType}")
            throw UnknownMediaTypeException(task.mediaType)
        }
        return parsed
    }

    private fun backoffMs(attempts: Int): Long =
        (BACKOFF_BASE_MS * shlExponent(attempts - 1)).coerceAtMost(BACKOFF_MAX_MS)

    private fun shlExponent(exponent: Int): Long =
        if (exponent <= 0) 1L else MIN_BACKOFF_MULTIPLIER shl (exponent.coerceAtMost(RETRY_EXPONENT_MAX))

    private fun decodePayload(encoded: String?): OutboxActionPayload =
        encoded?.let { runCatching { payloadAdapter.fromJson(it) }.getOrNull() } ?: OutboxActionPayload()

    private val payloadAdapter = moshi.adapter(OutboxActionPayload::class.java)

    private sealed interface TaskOutcome {
        /**
         * The provider accepted the payload; [submittedTimestamps] carries the
         * play timestamps the drain sent, or null for single-value actions.
         * The row is deleted only if its payload is still the submitted one.
         */
        data class Submitted(val submittedTimestamps: List<Long>? = null) : TaskOutcome

        /** Work remains queued in the row. */
        data object Progressed : TaskOutcome

        /** The row was deleted; everything the payload carried is consumed. */
        data object Finished : TaskOutcome

        data object Deferred : TaskOutcome
    }

    private class UnknownMediaTypeException(raw: String) : IllegalStateException("unknown media type: $raw")

    companion object {
        private const val TAG = "OutboxDrainer"
        const val EMPTY_PAYLOAD_ERROR = "empty payload"

        /** Each accepted chunk is persisted before the next one is submitted. */
        const val PLAY_SUBMISSION_CHUNK_SIZE = 100
        const val BACKOFF_BASE_MS = 15_000L
        const val BACKOFF_MAX_MS = 3_600_000L
        private const val MIN_BACKOFF_MULTIPLIER = 1L
        private const val RETRY_EXPONENT_MAX = 13
        private val moshi: Moshi = Moshi.Builder().build()
    }
}
