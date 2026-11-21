package app.omnisonic.core.data.downloads

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes offline-row ownership conversion (pin/unpin) with the automatic
 * pruner's filesystem-first deletion. Revalidating the persisted row closes
 * most of the race, but only a shared lock closes the gap between that
 * revalidation and the file delete, so a row pinned while a pass runs can never
 * lose its file. Only ownership conversions and the pruner take this lock; the
 * pruner acquires it for offline work only, so no coordinator/pass lock is held
 * underneath it.
 */
class OfflineOwnershipGuard {

    private val mutex = Mutex()

    suspend fun <T> withOwnershipLock(block: suspend () -> T): T = mutex.withLock { block() }
}
