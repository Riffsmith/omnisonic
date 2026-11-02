package app.omnisonic.provider.api

import app.omnisonic.core.model.log.ConsoleLogSink
import app.omnisonic.core.model.log.OmnisonicLogger
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

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
data class OutboxActionPayload(
    val playCountDelta: Int? = null,
    val isFavorite: Boolean? = null,
    val userRating: Int? = null,
    val resumePointMs: Long? = null,
    val lastPlayedAt: Long? = null,
    val playTimestamps: List<Long>? = null,
)

interface OutboxStore {
    suspend fun dueTasks(now: Long, providerId: Long?): List<OutboxTask>
    suspend fun complete(id: Long)
    suspend fun fail(id: Long, attempts: Int, nextAttemptAt: Long, lastError: String)
    suspend fun progress(id: Long, payloadJson: String?, nextAttemptAt: Long)
}

class OutboxDrainer(
    private val store: OutboxStore,
    private val providerLocator: (Long) -> MediaProvider?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val runGate: ProviderRunGate = AllowAllRunGate,
    private val logger: OmnisonicLogger = OmnisonicLogger(ConsoleLogSink()),
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
        for (task in store.dueTasks(clock(), providerId)) {
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
            if (task.action == ACTION_PLAY_COUNT) probeOnce(task.providerId, provider)
            val outcome = runCatching { execute(task, provider) }
                .getOrElse { error ->
                    logger.error(TAG, "outbox task ${task.id} failed", error)
                    store.fail(task.id, task.attempts + 1, clock() + backoffMs(task.attempts + 1), error.message ?: error.javaClass.simpleName)
                    null
                }
            when (outcome) {
                is TaskOutcome.Completed -> {
                    store.complete(task.id)
                    completed++
                }
                is TaskOutcome.Progressed -> progressed++
                null, TaskOutcome.Deferred -> deferred++
            }
        }
        return DrainReport(completed, progressed, deferred)
    }

    /** Probes a provider once per drainer instance before its first play-count task. */
    private suspend fun probeOnce(providerId: Long, provider: MediaProvider) {
        if (providerId in probedProviders) return
        probedProviders += providerId
        try {
            provider.probe()
        } catch (failure: Exception) {
            logger.warn(TAG, "capability probe failed for provider $providerId: ${failure.message}")
        }
    }

    private suspend fun execute(task: OutboxTask, provider: MediaProvider): TaskOutcome {
        val payload = decodePayload(task.payloadJson)
        return when (task.action) {
            ACTION_PLAY_COUNT -> handlePlayCount(task, payload, provider)
            ACTION_FAVORITE -> handleSimple(task) {
                payload.isFavorite?.let { provider.setFavorite(mediaTypeOf(task.mediaType), task.externalId, it) }
            }

            ACTION_USER_RATING -> handleSimple(task) {
                payload.userRating?.let { provider.setUserRating(mediaTypeOf(task.mediaType), task.externalId, it) }
            }

            ACTION_RESUME_POINT -> handleSimple(task) {
                payload.resumePointMs?.let { position ->
                    if (position > 0) provider.saveBookmark(task.externalId, position) else provider.deleteBookmark(task.externalId)
                }
            }

            else -> {
                logger.warn(TAG, "outbox task ${task.id} has unknown action ${task.action}; dropping")
                TaskOutcome.Completed
            }
        }
    }

    private suspend fun handlePlayCount(task: OutboxTask, payload: OutboxActionPayload, provider: MediaProvider): TaskOutcome {
        val delta = payload.playCountDelta
        if (delta == null || delta <= 0) {
            logger.warn(TAG, "outbox task ${task.id} has no positive play delta; dropping")
            return TaskOutcome.Completed
        }
        val timestamps = payload.playTimestamps
            ?.takeIf { it.isNotEmpty() }
            ?: payload.lastPlayedAt?.let { lastPlayedAt -> List(delta) { index -> lastPlayedAt + index } }
            ?: emptyList()
        if (timestamps.isEmpty()) {
            logger.warn(TAG, "outbox task ${task.id} carries no play timestamps; dropping")
            return TaskOutcome.Completed
        }
        val submitted = provider.submitPlays(task.externalId, timestamps)
        return when {
            submitted >= timestamps.size -> TaskOutcome.Completed
            submitted > 0 -> {
                val remaining = timestamps.drop(submitted)
                store.progress(
                    task.id,
                    payloadAdapter.toJson(
                        OutboxActionPayload(
                            playCountDelta = remaining.size,
                            lastPlayedAt = remaining.firstOrNull(),
                            playTimestamps = remaining,
                        ),
                    ),
                    clock(),
                )
                TaskOutcome.Progressed
            }

            else -> {
                store.fail(task.id, task.attempts + 1, clock() + backoffMs(task.attempts + 1), "server accepted no submissions")
                TaskOutcome.Deferred
            }
        }
    }

    private suspend fun handleSimple(task: OutboxTask, action: suspend () -> Boolean?): TaskOutcome =
        when (val applied = action.invoke()) {
            null -> {
                logger.warn(TAG, "outbox task ${task.id} carries no payload value; dropping")
                TaskOutcome.Completed
            }

            true -> TaskOutcome.Completed

            false -> {
                store.fail(task.id, task.attempts + 1, clock() + backoffMs(task.attempts + 1), "provider rejected the change")
                TaskOutcome.Deferred
            }
        }

    private fun mediaTypeOf(raw: String): ProviderMediaType =
        runCatching { ProviderMediaType.valueOf(raw) }.getOrDefault(ProviderMediaType.SONG)

    private fun backoffMs(attempts: Int): Long =
        (BACKOFF_BASE_MS * shlExponent(attempts - 1)).coerceAtMost(BACKOFF_MAX_MS)

    private fun shlExponent(exponent: Int): Long =
        if (exponent <= 0) 1L else MIN_BACKOFF_MULTIPLIER shl (exponent.coerceAtMost(RETRY_EXPONENT_MAX))

    private fun decodePayload(encoded: String?): OutboxActionPayload =
        encoded?.let { runCatching { payloadAdapter.fromJson(it) }.getOrNull() } ?: OutboxActionPayload()

    private val payloadAdapter = moshi.adapter(OutboxActionPayload::class.java)

    private sealed interface TaskOutcome {
        data object Completed : TaskOutcome
        data object Progressed : TaskOutcome
        data object Deferred : TaskOutcome
    }

    companion object {
        private const val TAG = "OutboxDrainer"
        private const val ACTION_PLAY_COUNT = "play_count"
        private const val ACTION_FAVORITE = "favorite"
        private const val ACTION_USER_RATING = "user_rating"
        private const val ACTION_RESUME_POINT = "resume_point"
        const val BACKOFF_BASE_MS = 15_000L
        const val BACKOFF_MAX_MS = 3_600_000L
        private const val MIN_BACKOFF_MULTIPLIER = 1L
        private const val RETRY_EXPONENT_MAX = 13
        private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    }
}
