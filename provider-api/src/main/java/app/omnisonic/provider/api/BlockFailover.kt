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
 * blocks share the same credentials. Learned preference is surfaced through
 * [onPreferredBlockChange] with [ProviderConnection.PRIMARY_BLOCK] or
 * [ProviderConnection.SECONDARY_BLOCK] for the caller to persist.
 */
class BlockFailover(
    private val connection: ProviderConnection,
    private val onPreferredBlockChange: (Int) -> Unit = {},
) {
    var preferredBlock: Int = connection.lastWorkingBlock ?: ProviderConnection.PRIMARY_BLOCK
        private set

    suspend fun <T> attempt(operation: suspend (ConnectionBlock) -> BlockAttempt<T>): BlockAttempt<T> {
        val blocks = buildList {
            add(ProviderConnection.PRIMARY_BLOCK to connection.primary)
            connection.secondary?.let { add(ProviderConnection.SECONDARY_BLOCK to it) }
        }
        val ordered = if (blocks.size > 1 && blocks.any { it.first == preferredBlock }) {
            blocks.sortedBy { if (it.first == preferredBlock) 0 else 1 }
        } else {
            blocks
        }

        for (index in ordered.indices) {
            val (order, block) = ordered[index]
            when (val outcome = operation(block)) {
                is BlockAttempt.Success -> {
                    if (order != preferredBlock) {
                        preferredBlock = order
                        onPreferredBlockChange(order)
                    }
                    return outcome
                }

                is BlockAttempt.FatalError -> return outcome

                else -> {
                    val worthFlipping = outcome is BlockAttempt.TransportError ||
                        (outcome is BlockAttempt.StatusError && outcome.httpCode >= FLIP_STATUS_MIN)
                    if (!worthFlipping || index == ordered.lastIndex) return outcome
                }
            }
        }
        return BlockAttempt.FatalError(IllegalStateException("no connection blocks configured"))
    }

    companion object {
        const val FLIP_STATUS_MIN = 500
    }
}
