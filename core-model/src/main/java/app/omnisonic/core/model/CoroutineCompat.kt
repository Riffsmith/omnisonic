package app.omnisonic.core.model

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspend blocks: catches everything EXCEPT
 * [CancellationException], which is rethrown so structured cancellation is
 * never converted into a logged failure, a backoff, or a swallowed result.
 * Every `runCatching` (or `catch (Exception)`) that wraps a suspend call must
 * use this instead.
 */
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Throwable) {
    Result.failure(failure)
}
