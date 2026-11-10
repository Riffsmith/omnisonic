package app.omnisonic.core.data.sync

import kotlinx.coroutines.flow.StateFlow

/** Outcome recorded for a provider's most recent walk; `NEVER` = no row yet. */
enum class LibrarySyncLastResult {
    NEVER,
    COMPLETED,
    FAILED,
}

/** Persisted sync record plus the provider identity the screens need (D-16). */
data class LibrarySyncProvider(
    val providerId: Long,
    val name: String,
    val supportsLibrarySync: Boolean,
    val lastFullSyncAt: Long? = null,
    val lastDiffSyncAt: Long? = null,
    val lastResult: LibrarySyncLastResult = LibrarySyncLastResult.NEVER,
    val lastMessage: String? = null,
    val lastCount: Int = 0,
    val lastDurationMs: Long? = null,
) {

    /** Newest recorded walk of either kind; null when the provider never ran one. */
    val lastSyncAt: Long?
        get() = listOfNotNull(lastFullSyncAt, lastDiffSyncAt).maxOrNull()
}

/**
 * Screen-facing seam over the library walker (task 4c): the first-run sync step
 * watches [progress] and can restart a failed walk, while the settings manager
 * reads recorded outcomes and triggers a diff or full walk. The launcher owns
 * the run gate, so repeated calls while a walk is live are no-ops.
 */
interface LibrarySyncControl {

    val progress: StateFlow<LibrarySyncProgress?>

    suspend fun providers(): List<LibrarySyncProvider>

    fun startFullSync(providerId: Long)

    fun startDiffSync(providerId: Long)
}
