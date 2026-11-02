package app.omnisonic.provider.api

/** Outcome of one attempt against a single connection block. */
sealed interface BlockAttempt<out T> {
    data class Success<T>(val value: T) : BlockAttempt<T>
    data class StatusError(val httpCode: Int, val message: String? = null) : BlockAttempt<Nothing>
    data class TransportError(val cause: Throwable) : BlockAttempt<Nothing>
    data class FatalError(val cause: Throwable) : BlockAttempt<Nothing>
}

/**
 * Tries the remembered working block first, then the other configured block on
 * transport errors or >=500 responses; 4xx responses are terminal because both
 * blocks share the same credentials. Hysteresis: only [FLIP_FAILURE_THRESHOLD]
 * consecutive primary failures flip the preference to the secondary. While the
 * secondary is preferred, the primary is re-probed after
 * [DEFAULT_PREFER_PRIMARY_RETRY_INTERVAL_MS]; a successful probe reclaims the
 * primary. Learned preference is surfaced through [onPreferredBlockChange]
 * with [ProviderConnection.PRIMARY_BLOCK] or [ProviderConnection.SECONDARY_BLOCK]
 * for the caller to persist.
 */
class BlockFailover(
    private val connection: ProviderConnection,
    private val onPreferredBlockChange: suspend (Int) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val preferPrimaryRetryIntervalMs: Long = DEFAULT_PREFER_PRIMARY_RETRY_INTERVAL_MS,
) {
    var preferredBlock: Int = connection.lastWorkingBlock ?: ProviderConnection.PRIMARY_BLOCK
        private set

    private val remote = connection.endpoint as? ProviderEndpoint.Remote
    private var primaryFailures = 0
    private var lastPrimaryProbeAtMs = clock()

    suspend fun <T> attempt(operation: suspend (ConnectionBlock) -> BlockAttempt<T>): BlockAttempt<T> {
        val remote = this.remote ?: return BlockAttempt.FatalError(
            IllegalStateException("provider ${connection.providerId} has no remote endpoint"),
        )
        val secondary = remote.secondary
        if (secondary != null && preferredBlock == ProviderConnection.SECONDARY_BLOCK) {
            if (clock() - lastPrimaryProbeAtMs >= preferPrimaryRetryIntervalMs) {
                when (val probe = operation(remote.primary)) {
                    is BlockAttempt.Success -> {
                        primaryFailures = 0
                        adopt(ProviderConnection.PRIMARY_BLOCK)
                        return probe
                    }

                    is BlockAttempt.FatalError -> return probe
                    else -> lastPrimaryProbeAtMs = clock()
                }
            }
            return operation(secondary)
        }

        return when (val outcome = operation(remote.primary)) {
            is BlockAttempt.Success -> {
                primaryFailures = 0
                outcome
            }

            is BlockAttempt.FatalError -> outcome

            else -> {
                val worthFlipping = outcome is BlockAttempt.TransportError ||
                    (outcome is BlockAttempt.StatusError && outcome.httpCode >= FLIP_STATUS_MIN)
                primaryFailures += 1
                if (!worthFlipping || secondary == null || primaryFailures < FLIP_FAILURE_THRESHOLD) {
                    return outcome
                }
                adopt(ProviderConnection.SECONDARY_BLOCK)
                operation(secondary)
            }
        }
    }

    private suspend fun adopt(block: Int) {
        preferredBlock = block
        if (block == ProviderConnection.SECONDARY_BLOCK) {
            lastPrimaryProbeAtMs = clock()
        }
        onPreferredBlockChange(block)
    }

    companion object {
        const val FLIP_STATUS_MIN = 500
        const val FLIP_FAILURE_THRESHOLD = 2
        const val DEFAULT_PREFER_PRIMARY_RETRY_INTERVAL_MS = 5 * 60_000L
    }
}
